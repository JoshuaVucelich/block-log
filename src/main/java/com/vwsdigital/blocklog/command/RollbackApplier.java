package com.vwsdigital.blocklog.command;

import com.vwsdigital.blocklog.BlockLog;
import com.vwsdigital.blocklog.SourceContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.List;

/** Applies rollbacks on the server thread a few thousand blocks per tick so huge rollbacks don't freeze the server. */
public final class RollbackApplier {
	public static final int BLOCKS_PER_TICK = 4096;

	public record Change(ServerLevel level, BlockPos pos, BlockState state) {}

	private static final class Job {
		final List<Change> changes;
		final CommandSourceStack source;
		int index = 0, changed = 0, skipped = 0;
		final long started = System.currentTimeMillis();
		Job(List<Change> changes, CommandSourceStack source) { this.changes = changes; this.source = source; }
	}

	private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();

	private RollbackApplier() {}

	public static void submit(List<Change> changes, CommandSourceStack source) {
		JOBS.add(new Job(changes, source));
	}

	public static int pendingJobs() { return JOBS.size(); }

	public static void clear() { JOBS.clear(); }

	public static void tick(MinecraftServer server) {
		Job job = JOBS.peek();
		if (job == null) return;
		int budget = BLOCKS_PER_TICK;
		SourceContext.push(SourceContext.ROLLBACK);
		try {
			while (budget-- > 0 && job.index < job.changes.size()) {
				Change c = job.changes.get(job.index++);
				try {
					if (c.level().getBlockState(c.pos()) == c.state()) { job.skipped++; continue; }
					if (c.level().setBlock(c.pos(), c.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) job.changed++;
					else job.skipped++;
				} catch (Throwable t) {
					job.skipped++;
					BlockLog.LOGGER.error("[block-log] Rollback failed at {}", c.pos(), t);
				}
			}
		} finally {
			SourceContext.pop();
		}
		if (job.index >= job.changes.size()) {
			JOBS.poll();
			long secs = (System.currentTimeMillis() - job.started) / 1000;
			job.source.sendSuccess(() -> Component.literal("[Block Log] Rollback done: " + job.changed + " blocks restored, "
				+ job.skipped + " already matched/skipped (" + secs + "s)."), true);
		}
	}
}
