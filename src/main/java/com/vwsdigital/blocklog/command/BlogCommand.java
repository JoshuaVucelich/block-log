package com.vwsdigital.blocklog.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vwsdigital.blocklog.BlockLog;
import com.vwsdigital.blocklog.db.BlockLogQueries;
import com.vwsdigital.blocklog.db.BlockLogWriter;
import com.vwsdigital.blocklog.db.Filter;
import com.vwsdigital.blocklog.db.LoggedChange;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class BlogCommand {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private BlogCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("blog")
			.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
			.executes(BlogCommand::help)
			.then(literal("help").executes(BlogCommand::help))
			.then(literal("status").executes(BlogCommand::status))
			.then(literal("flush").executes(BlogCommand::flush))
			.then(literal("search")
				.executes(ctx -> search(ctx, ""))
				.then(argument("params", StringArgumentType.greedyString())
					.executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "params")))))
			.then(literal("rollback")
				.executes(ctx -> rollback(ctx, ""))
				.then(argument("params", StringArgumentType.greedyString())
					.executes(ctx -> rollback(ctx, StringArgumentType.getString(ctx, "params")))))
			.then(literal("inspect")
				.executes(ctx -> inspect(ctx, BlockPos.containing(ctx.getSource().getPosition())))
				.then(argument("pos", BlockPosArgument.blockPos())
					.executes(ctx -> inspect(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
		);
	}

	// ------------------------------------------------------------------ helpers

	private static Component msg(String s) {
		return Component.literal("[Block Log] ").withStyle(ChatFormatting.GOLD).append(Component.literal(s).withStyle(ChatFormatting.WHITE));
	}

	private static void reply(CommandSourceStack src, Component c) {
		MinecraftServer server = src.getServer();
		server.execute(() -> src.sendSuccess(() -> c, false));
	}

	private static void fail(CommandSourceStack src, String s) {
		src.getServer().execute(() -> src.sendFailure(Component.literal("[Block Log] " + s)));
	}

	private static boolean ready(CommandSourceStack src) {
		if (BlockLog.writer() == null || BlockLog.queries() == null) {
			src.sendFailure(Component.literal("[Block Log] Database is not open (see server log)."));
			return false;
		}
		return true;
	}

	private static String shortState(String s) {
		return s.replace("minecraft:", "");
	}

	private static Component row(LoggedChange c, String executorWorld, boolean withPos) {
		MutableComponent m = Component.literal(TIME.format(Instant.ofEpochMilli(c.time())) + " ").withStyle(ChatFormatting.GRAY);
		m.append(Component.literal(c.playerName()).withStyle(c.playerUuid() == null ? ChatFormatting.DARK_AQUA : ChatFormatting.AQUA));
		m.append(Component.literal(" " + shortState(c.oldState())).withStyle(ChatFormatting.RED));
		m.append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY));
		m.append(Component.literal(shortState(c.newState())).withStyle(ChatFormatting.GREEN));
		if (withPos) {
			String w = c.world().equals(executorWorld) ? "" : " " + shortState(c.world());
			m.append(Component.literal(" @ " + c.x() + " " + c.y() + " " + c.z() + w).withStyle(ChatFormatting.YELLOW));
		}
		return m;
	}

	private static String ago(long millis) {
		long s = (System.currentTimeMillis() - millis) / 1000;
		if (s < 60) return s + "s ago";
		if (s < 3600) return (s / 60) + "m ago";
		if (s < 86400) return (s / 3600) + "h ago";
		return (s / 86400) + "d ago";
	}

	// ------------------------------------------------------------------ commands

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack s = ctx.getSource();
		s.sendSuccess(() -> msg("Commands (op only):"), false);
		s.sendSuccess(() -> Component.literal(" /blog status").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - queue size, last write").withStyle(ChatFormatting.GRAY)), false);
		s.sendSuccess(() -> Component.literal(" /blog flush").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - wait until every queued change is on disk").withStyle(ChatFormatting.GRAY)), false);
		s.sendSuccess(() -> Component.literal(" /blog search source:<name> after:<time> range:<blocks>").withStyle(ChatFormatting.YELLOW), false);
		s.sendSuccess(() -> Component.literal(" /blog rollback source:<name> after:<time> range:<blocks>").withStyle(ChatFormatting.YELLOW), false);
		s.sendSuccess(() -> Component.literal(" /blog inspect [x y z]").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - history of one block (air too, ~ ~ ~ ok)").withStyle(ChatFormatting.GRAY)), false);
		s.sendSuccess(() -> Component.literal(" Time: 30m, 2h, 3d, 1d12h, 2026-10-05T14:30. Also before:, world:<id|all>, limit:. Sources: player names, #world, #explosion, #rollback").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack s = ctx.getSource();
		if (!ready(s)) return 0;
		BlockLogWriter w = BlockLog.writer();
		long last = w.lastSuccessfulWrite();
		String lastStr = last == 0 ? "never (this session)" : TIME.format(Instant.ofEpochMilli(last)) + " (" + ago(last) + ")";
		s.sendSuccess(() -> msg("Queue: " + w.queueSize() + " / " + w.capacity() + " pending"), false);
		s.sendSuccess(() -> msg("Last successful write: " + lastStr), false);
		s.sendSuccess(() -> msg("Written this session: " + w.totalWritten() + " | writer thread: " + (w.writerAlive() ? "running" : "STOPPED")), false);
		if (w.lastError() != null) s.sendSuccess(() -> msg("Last DB error: " + w.lastError()), false);
		if (RollbackApplier.pendingJobs() > 0) s.sendSuccess(() -> msg("Rollbacks in progress: " + RollbackApplier.pendingJobs()), false);
		s.sendSuccess(() -> msg("DB: " + w.dbFile()), false);
		return 1;
	}

	private static int flush(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack s = ctx.getSource();
		if (!ready(s)) return 0;
		int pending = BlockLog.writer().queueSize();
		long start = System.currentTimeMillis();
		s.sendSuccess(() -> msg("Flushing " + pending + " queued changes..."), false);
		BlockLog.queries().flush().whenComplete((v, err) -> {
			if (err != null) fail(s, "Flush failed: " + err.getMessage());
			else reply(s, msg("Flushed. Queue is " + BlockLog.writer().queueSize() + " (" + (System.currentTimeMillis() - start) + " ms)."));
		});
		return 1;
	}

	private static int search(CommandContext<CommandSourceStack> ctx, String params) {
		CommandSourceStack s = ctx.getSource();
		if (!ready(s)) return 0;
		QueryParams q;
		try { q = QueryParams.parse(params); } catch (IllegalArgumentException e) { s.sendFailure(Component.literal("[Block Log] " + e.getMessage())); return 0; }
		String world = s.getLevel().dimension().identifier().toString();
		Filter f = q.toFilter(world, BlockPos.containing(s.getPosition()));
		s.sendSuccess(() -> msg("Searching..."), false);
		BlockLog.queries().search(f, q.limit).whenComplete((res, err) -> {
			if (err != null) { fail(s, "Search failed: " + err.getMessage()); BlockLog.LOGGER.error("[block-log] search", err); return; }
			s.getServer().execute(() -> {
				s.sendSuccess(() -> msg(res.total() + " matching changes" + (res.total() > res.rows().size() ? " (newest " + res.rows().size() + " shown)" : "") + ":"), false);
				for (LoggedChange c : res.rows()) s.sendSuccess(() -> row(c, world, true), false);
			});
		});
		return 1;
	}

	private static int rollback(CommandContext<CommandSourceStack> ctx, String params) {
		CommandSourceStack s = ctx.getSource();
		if (!ready(s)) return 0;
		QueryParams q;
		try { q = QueryParams.parse(params); } catch (IllegalArgumentException e) { s.sendFailure(Component.literal("[Block Log] " + e.getMessage())); return 0; }
		if (q.after == null) {
			s.sendFailure(Component.literal("[Block Log] Rollback needs after:<time> (e.g. /blog rollback source:Steve after:2h range:30)"));
			return 0;
		}
		String world = s.getLevel().dimension().identifier().toString();
		Filter f = q.toFilter(world, BlockPos.containing(s.getPosition()));
		MinecraftServer server = s.getServer();
		s.sendSuccess(() -> msg("Rollback: flushing queue and collecting matching changes..."), true);
		BlockLog.queries().rollbackRows(f).whenComplete((rows, err) -> {
			if (err != null) { fail(s, "Rollback query failed: " + err.getMessage()); BlockLog.LOGGER.error("[block-log] rollback", err); return; }
			server.execute(() -> applyRollback(s, rows));
		});
		return 1;
	}

	/** rows are OLDEST first. For each position the oldest matching change wins: we restore the state from BEFORE it. */
	private static void applyRollback(CommandSourceStack s, List<LoggedChange> rows) {
		MinecraftServer server = s.getServer();
		HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
		Map<String, LoggedChange> oldestPerPos = new LinkedHashMap<>();
		for (LoggedChange c : rows) {
			oldestPerPos.putIfAbsent(c.world() + "|" + c.x() + "|" + c.y() + "|" + c.z(), c);
		}
		Map<String, BlockState> stateCache = new HashMap<>();
		List<RollbackApplier.Change> changes = new ArrayList<>(oldestPerPos.size());
		int bad = 0;
		for (LoggedChange c : oldestPerPos.values()) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(c.world())));
			if (level == null) { bad++; continue; }
			BlockState st = stateCache.get(c.oldState());
			if (st == null) {
				try {
					st = BlockStateParser.parseForBlock(blocks, c.oldState(), false).blockState();
					stateCache.put(c.oldState(), st);
				} catch (Exception e) {
					bad++;
					continue;
				}
			}
			changes.add(new RollbackApplier.Change(level, new BlockPos(c.x(), c.y(), c.z()), st));
		}
		int badF = bad;
		s.sendSuccess(() -> msg(rows.size() + " logged changes matched, " + changes.size() + " positions to restore"
			+ (badF > 0 ? " (" + badF + " unreadable/unknown world skipped)" : "") + ". Restoring "
			+ RollbackApplier.BLOCKS_PER_TICK + " blocks/tick, oldest change first..."), true);
		if (!changes.isEmpty()) RollbackApplier.submit(changes, s);
	}

	private static int inspect(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
		CommandSourceStack s = ctx.getSource();
		if (!ready(s)) return 0;
		ServerLevel level = s.getLevel();
		String world = level.dimension().identifier().toString();
		String current = level.isLoaded(pos) ? shortState(BlockStateParser.serialize(level.getBlockState(pos))) : "(chunk not loaded)";
		s.sendSuccess(() -> msg("Block " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + shortState(world) + " is now: " + current), false);
		BlockLog.queries().inspect(world, pos.getX(), pos.getY(), pos.getZ(), 10).whenComplete((res, err) -> {
			if (err != null) { fail(s, "Inspect failed: " + err.getMessage()); return; }
			s.getServer().execute(() -> {
				if (res.total() == 0) { s.sendSuccess(() -> msg("No logged changes at this position."), false); return; }
				s.sendSuccess(() -> msg(res.total() + " logged changes" + (res.total() > res.rows().size() ? " (newest " + res.rows().size() + ")" : "") + ":"), false);
				for (LoggedChange c : res.rows()) s.sendSuccess(() -> row(c, world, false), false);
			});
		});
		return 1;
	}

	@SuppressWarnings("unused")
	private static ResourceKey<Level> key(String id) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
	}
}
