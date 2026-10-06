package com.vwsdigital.blocklog;

import com.vwsdigital.blocklog.command.BlogCommand;
import com.vwsdigital.blocklog.command.RollbackApplier;
import com.vwsdigital.blocklog.db.BlockLogQueries;
import com.vwsdigital.blocklog.db.BlockLogWriter;
import com.vwsdigital.blocklog.db.ChangeRecord;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class BlockLog implements ModInitializer {
	public static final String MOD_ID = "block-log";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile BlockLogWriter writer;
	private static volatile BlockLogQueries queries;
	private static BlockLogConfig config;

	public static BlockLogWriter writer() { return writer; }
	public static BlockLogQueries queries() { return queries; }
	public static BlockLogConfig config() { return config; }

	@Override
	public void onInitialize() {
		config = BlockLogConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			BlogCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path dbFile = server.getWorldPath(LevelResource.ROOT).resolve("block-log").resolve("blocklog.sqlite").toAbsolutePath().normalize();
			try {
				BlockLogWriter w = new BlockLogWriter(dbFile, config.queueCapacity, config.batchSize);
				w.start();
				writer = w;
				queries = new BlockLogQueries(dbFile, w);
				LOGGER.info("[{}] Logging block changes to {} (queue cap {}, batch {})", MOD_ID, dbFile, config.queueCapacity, config.batchSize);
			} catch (Exception e) {
				LOGGER.error("[{}] Could not open block log database {} - block changes will NOT be logged", MOD_ID, dbFile, e);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(RollbackApplier::tick);

		// SERVER_STOPPED runs after all worlds have been saved, so every change made during shutdown is already queued.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RollbackApplier.clear();
			BlockLogWriter w = writer;
			if (w != null) {
				LOGGER.info("[{}] Server stopped - flushing {} queued block changes to disk (no timeout)...", MOD_ID, w.queueSize());
				w.shutdownAndFlush();
				LOGGER.info("[{}] Block log flushed and closed.", MOD_ID);
			}
			BlockLogQueries q = queries;
			if (q != null) q.close();
			writer = null;
			queries = null;
		});

		LOGGER.info("[{}] Loaded. Commands: /blog status | flush | search | rollback | inspect", MOD_ID);
	}

	/** Called from the Level#setBlock mixin, on whatever thread changed the block (normally the server thread). Never touches the DB. */
	public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		BlockLogWriter w = writer;
		if (w == null || oldState == newState) return;

		SourceContext.Source src = SourceContext.current();
		if (src == null) {
			if (!config.logNonPlayerChanges) return;
			// Skip the high-volume natural noise: property-only changes (crop age, redstone power, ...) and fluid flow.
			if (oldState.getBlock() == newState.getBlock()) return;
			if (isAirOrFluid(oldState) && isAirOrFluid(newState)) return;
			src = SourceContext.WORLD;
		}

		w.enqueue(new ChangeRecord(
			System.currentTimeMillis(),
			level.dimension().identifier().toString(),
			pos.getX(), pos.getY(), pos.getZ(),
			ChangeRecord.serialize(oldState),
			ChangeRecord.serialize(newState),
			src.uuid(),
			src.name()));
	}

	private static boolean isAirOrFluid(BlockState s) {
		return s.isAir() || s.getBlock() instanceof LiquidBlock;
	}
}
