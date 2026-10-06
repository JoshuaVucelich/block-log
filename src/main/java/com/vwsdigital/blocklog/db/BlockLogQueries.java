package com.vwsdigital.blocklog.db;

import com.vwsdigital.blocklog.BlockLog;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read side. Runs on its own single background thread with its own SQLite connection (WAL allows concurrent reads),
 * so lookups never block the server thread. Every query first waits for the writer queue to drain, so results include
 * changes made right up to the moment the command was run.
 */
public final class BlockLogQueries {
	public static final int MAX_ROLLBACK_ROWS = 2_000_000;

	private final Path dbFile;
	private final BlockLogWriter writer;
	private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "BlockLog-Query");
		t.setDaemon(true);
		return t;
	});
	private Connection conn; // only touched on exec thread

	public record SearchResult(long total, List<LoggedChange> rows) {}

	public BlockLogQueries(Path dbFile, BlockLogWriter writer) {
		this.dbFile = dbFile;
		this.writer = writer;
	}

	private Connection conn() throws SQLException {
		if (conn == null || conn.isClosed()) conn = BlockLogWriter.open(dbFile, false);
		return conn;
	}

	public CompletableFuture<Void> flush() {
		return CompletableFuture.runAsync(() -> {
			try { writer.awaitFlush(); } catch (InterruptedException e) { throw new RuntimeException(e); }
		}, exec);
	}

	public CompletableFuture<SearchResult> search(Filter f, int limit) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				writer.awaitFlush();
				Where w = where(f);
				long total;
				try (PreparedStatement ps = conn().prepareStatement("SELECT COUNT(*) FROM changes" + w.sql)) {
					w.bind(ps);
					try (ResultSet rs = ps.executeQuery()) { total = rs.next() ? rs.getLong(1) : 0; }
				}
				List<LoggedChange> rows = select(w, "ORDER BY id DESC LIMIT " + limit);
				return new SearchResult(total, rows);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, exec);
	}

	/** All matching rows, OLDEST first (by insertion id, which follows real change order). */
	public CompletableFuture<List<LoggedChange>> rollbackRows(Filter f) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				writer.awaitFlush();
				return select(where(f), "ORDER BY id ASC LIMIT " + MAX_ROLLBACK_ROWS);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, exec);
	}

	public CompletableFuture<SearchResult> inspect(String world, int x, int y, int z, int limit) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				writer.awaitFlush();
				Where w = new Where(" WHERE world = ? AND x = ? AND z = ? AND y = ?", List.of(world, x, z, y));
				long total;
				try (PreparedStatement ps = conn().prepareStatement("SELECT COUNT(*) FROM changes" + w.sql)) {
					w.bind(ps);
					try (ResultSet rs = ps.executeQuery()) { total = rs.next() ? rs.getLong(1) : 0; }
				}
				return new SearchResult(total, select(w, "ORDER BY id DESC LIMIT " + limit));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, exec);
	}

	private List<LoggedChange> select(Where w, String tail) throws SQLException {
		List<LoggedChange> out = new ArrayList<>();
		try (PreparedStatement ps = conn().prepareStatement(
			"SELECT id, time, world, x, y, z, old_state, new_state, player_uuid, player_name FROM changes" + w.sql + " " + tail)) {
			w.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new LoggedChange(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
						rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)));
				}
			}
		}
		return out;
	}

	private record Where(String sql, List<Object> args) {
		void bind(PreparedStatement ps) throws SQLException {
			for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
		}
	}

	private static Where where(Filter f) {
		StringBuilder sb = new StringBuilder();
		List<Object> args = new ArrayList<>();
		if (f.world() != null) { add(sb, "world = ?"); args.add(f.world()); }
		if (f.source() != null) { add(sb, "player_name = ? COLLATE NOCASE"); args.add(f.source()); }
		if (f.after() != null) { add(sb, "time >= ?"); args.add(f.after()); }
		if (f.before() != null) { add(sb, "time <= ?"); args.add(f.before()); }
		if (f.range() != null && f.cx() != null) {
			int r = f.range();
			add(sb, "x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ?");
			args.add(f.cx() - r); args.add(f.cx() + r);
			args.add(f.cz() - r); args.add(f.cz() + r);
			args.add(f.cy() - r); args.add(f.cy() + r);
		}
		return new Where(sb.toString(), args);
	}

	private static void add(StringBuilder sb, String clause) {
		sb.append(sb.isEmpty() ? " WHERE " : " AND ").append(clause);
	}

	public void close() {
		exec.submit(() -> {
			try { if (conn != null) conn.close(); } catch (SQLException e) { BlockLog.LOGGER.warn("[block-log] close read conn", e); }
		});
		exec.shutdown();
	}
}
