package com.vwsdigital.blocklog.db;

import com.vwsdigital.blocklog.BlockLog;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded queue + single background writer thread.
 * <ul>
 *   <li>{@link #enqueue} is called on the server thread and only does a queue offer (O(1), no I/O).</li>
 *   <li>If the queue is full, the caller (server thread) BLOCKS until the writer frees space, logging an error every 5 s.
 *       Memory can therefore never grow past {@code capacity} entries.</li>
 *   <li>The writer drains up to {@code batchSize} records per SQLite transaction (WAL mode).</li>
 *   <li>{@link #shutdownAndFlush} waits until every queued record is written - there is no give-up timeout.</li>
 * </ul>
 */
public final class BlockLogWriter {
	static final String SCHEMA = """
		CREATE TABLE IF NOT EXISTS changes (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			time INTEGER NOT NULL,
			world TEXT NOT NULL,
			x INTEGER NOT NULL,
			y INTEGER NOT NULL,
			z INTEGER NOT NULL,
			old_state TEXT NOT NULL,
			new_state TEXT NOT NULL,
			player_uuid TEXT,
			player_name TEXT NOT NULL
		)""";

	private final Path dbFile;
	private final ArrayBlockingQueue<ChangeRecord> queue;
	private final int batchSize;
	private final Thread thread;
	private final Object lock = new Object();

	private volatile boolean running = true;
	private volatile int inFlight = 0;
	private volatile long lastSuccessfulWrite = 0L;
	private volatile long totalWritten = 0L;
	private volatile String lastError = null;
	private volatile long lastFullWarning = 0L;
	private volatile List<ChangeRecord> leftover = List.of();
	private final Connection conn;

	public BlockLogWriter(Path dbFile, int capacity, int batchSize) throws SQLException, java.io.IOException {
		this.dbFile = dbFile;
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.batchSize = batchSize;
		Files.createDirectories(dbFile.getParent());
		this.conn = open(dbFile, false);
		try (Statement st = conn.createStatement()) {
			st.execute(SCHEMA);
			st.execute("CREATE INDEX IF NOT EXISTS idx_changes_pos ON changes(world, x, z, y)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_changes_time ON changes(time)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_changes_player ON changes(player_name COLLATE NOCASE, time)");
		}
		this.thread = new Thread(this::run, "BlockLog-Writer");
		this.thread.setDaemon(true); // joined explicitly in shutdownAndFlush()
	}

	static Connection open(Path dbFile, boolean readOnly) throws SQLException {
		SQLiteConfig cfg = new SQLiteConfig();
		cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
		cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
		cfg.setBusyTimeout(30_000);
		cfg.setReadOnly(readOnly);
		// Use the driver class directly instead of DriverManager (avoids ServiceLoader/classloader issues inside Fabric).
		return cfg.createConnection("jdbc:sqlite:" + dbFile);
	}

	public Path dbFile() { return dbFile; }

	public void start() { thread.start(); }

	/** Changes not yet committed to SQLite (queued + the batch currently being written). */
	public int queueSize() { return queue.size() + inFlight; }
	public int capacity() { return queue.size() + queue.remainingCapacity(); }
	public long lastSuccessfulWrite() { return lastSuccessfulWrite; }
	public long totalWritten() { return totalWritten; }
	public String lastError() { return lastError; }
	public boolean writerAlive() { return thread.isAlive(); }

	/** Server-thread entry point. Returns immediately unless the queue is at its cap. */
	public void enqueue(ChangeRecord r) {
		if (queue.offer(r)) return;
		long now = System.currentTimeMillis();
		if (now - lastFullWarning > 5_000) {
			lastFullWarning = now;
			BlockLog.LOGGER.error("[block-log] Write queue is FULL ({} entries) - blocking the server thread until the DB writer catches up. Last DB error: {}",
				queue.size(), lastError);
		}
		try {
			while (!queue.offer(r, 5, TimeUnit.SECONDS)) {
				BlockLog.LOGGER.error("[block-log] Still blocked: write queue full ({} entries), writer alive={}, last DB error: {}",
					queue.size(), thread.isAlive(), lastError);
				if (!thread.isAlive()) {
					BlockLog.LOGGER.error("[block-log] Writer thread is not running - dropping change to avoid freezing the server forever.");
					return;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Blocks the CALLING thread until the queue is empty and nothing is in flight. No timeout. Never call on the server thread. */
	public void awaitFlush() throws InterruptedException {
		synchronized (lock) {
			while (!queue.isEmpty() || inFlight > 0) {
				if (!thread.isAlive()) throw new IllegalStateException("block-log writer thread is not running");
				lock.wait(250);
			}
		}
	}

	/** Write everything still queued, stop the writer, close the DB. No give-up timeout. */
	public void shutdownAndFlush() {
		try {
			awaitFlush();
		} catch (Exception e) {
			BlockLog.LOGGER.error("[block-log] Error while waiting for flush, finishing synchronously", e);
		}
		running = false;
		thread.interrupt();
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// Anything left (un-written batch or late arrivals) is written here, synchronously, until done.
		List<ChangeRecord> rest = new ArrayList<>(leftover);
		queue.drainTo(rest);
		inFlight = 0;
		int attempts = 0;
		while (!rest.isEmpty()) {
			List<ChangeRecord> chunk = rest.subList(0, Math.min(batchSize, rest.size()));
			try {
				writeBatch(chunk);
				chunk.clear();
			} catch (SQLException e) {
				attempts++;
				BlockLog.LOGGER.error("[block-log] Final flush failed (attempt {}), retrying in 1s", attempts, e);
				try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
			}
		}
		try {
			try (Statement st = conn.createStatement()) { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (SQLException ignored) {}
			conn.close();
		} catch (SQLException e) {
			BlockLog.LOGGER.warn("[block-log] Error closing DB", e);
		}
	}

	private void run() {
		List<ChangeRecord> batch = new ArrayList<>(batchSize);
		while (running) {
			try {
				if (batch.isEmpty()) {
					ChangeRecord first = queue.poll(500, TimeUnit.MILLISECONDS);
					if (first == null) {
						synchronized (lock) { lock.notifyAll(); }
						continue;
					}
					batch.add(first);
					queue.drainTo(batch, batchSize - 1);
					inFlight = batch.size();
				}
				writeBatch(batch);
				batch.clear();
				inFlight = 0;
				synchronized (lock) { lock.notifyAll(); }
			} catch (InterruptedException e) {
				if (!running) break;
			} catch (Throwable t) {
				lastError = t.toString();
				BlockLog.LOGGER.error("[block-log] Batch insert of {} rows failed, retrying in 2s (batch kept in memory)", batch.size(), t);
				try { Thread.sleep(2000); } catch (InterruptedException ie) { if (!running) break; }
			}
		}
		// Hand any un-written batch to the final synchronous flush in shutdownAndFlush().
		leftover = new ArrayList<>(batch);
		synchronized (lock) { lock.notifyAll(); }
	}

	private void writeBatch(List<ChangeRecord> batch) throws SQLException {
		conn.setAutoCommit(false);
		try (PreparedStatement ps = conn.prepareStatement(
			"INSERT INTO changes(time, world, x, y, z, old_state, new_state, player_uuid, player_name) VALUES (?,?,?,?,?,?,?,?,?)")) {
			for (ChangeRecord r : batch) {
				ps.setLong(1, r.time());
				ps.setString(2, r.world());
				ps.setInt(3, r.x());
				ps.setInt(4, r.y());
				ps.setInt(5, r.z());
				ps.setString(6, r.oldState());
				ps.setString(7, r.newState());
				ps.setString(8, r.playerUuid());
				ps.setString(9, r.playerName());
				ps.addBatch();
			}
			ps.executeBatch();
			conn.commit();
			lastSuccessfulWrite = System.currentTimeMillis();
			totalWritten += batch.size();
			lastError = null;
		} catch (SQLException e) {
			try { conn.rollback(); } catch (SQLException ignored) {}
			throw e;
		} finally {
			conn.setAutoCommit(true);
		}
	}
}
