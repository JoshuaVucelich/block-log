package com.vwsdigital.blocklog;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/block-log.properties */
public final class BlockLogConfig {
	public int queueCapacity = 20_000;
	public int batchSize = 1_000;
	public boolean logNonPlayerChanges = true;

	static BlockLogConfig load() {
		BlockLogConfig c = new BlockLogConfig();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("block-log.properties");
		Properties p = new Properties();
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) { p.load(r); }
			}
			c.queueCapacity = Math.max(1_000, Integer.parseInt(p.getProperty("queueCapacity", "" + c.queueCapacity).trim()));
			c.batchSize = Math.max(1, Math.min(10_000, Integer.parseInt(p.getProperty("batchSize", "" + c.batchSize).trim())));
			c.logNonPlayerChanges = Boolean.parseBoolean(p.getProperty("logNonPlayerChanges", "" + c.logNonPlayerChanges).trim());
		} catch (Exception e) {
			BlockLog.LOGGER.warn("[block-log] Bad config {}, using defaults: {}", file, e.toString());
			c = new BlockLogConfig();
		}
		p.setProperty("queueCapacity", "" + c.queueCapacity);
		p.setProperty("batchSize", "" + c.batchSize);
		p.setProperty("logNonPlayerChanges", "" + c.logNonPlayerChanges);
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "Block Log config. queueCapacity = max pending changes in memory before the server thread blocks; "
					+ "batchSize = rows per SQLite transaction; logNonPlayerChanges = also log block-type changes not caused by a player (#world/#explosion).");
			}
		} catch (IOException e) {
			BlockLog.LOGGER.warn("[block-log] Could not write config {}: {}", file, e.toString());
		}
		return c;
	}
}
