package com.vwsdigital.blocklog.command;

import com.vwsdigital.blocklog.db.Filter;
import net.minecraft.core.BlockPos;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses "source:Steve after:2h range:20 before:30m world:minecraft:the_nether limit:20".
 * Times: durations like 30s, 15m, 2h, 3d, 1w, 1d12h (meaning "that long ago"), or a local date/time
 * like 2026-10-05T14:30 / 2026-10-05, or epoch milliseconds.
 */
public final class QueryParams {
	private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])");
	private static final Pattern DURATION_FULL = Pattern.compile("(\\d+[smhdw])+");

	public String source;
	public Long after;
	public Long before;
	public Integer range;
	public String world;      // null = executor's dimension
	public boolean allWorlds;
	public int limit = 15;

	public static QueryParams parse(String input) throws IllegalArgumentException {
		QueryParams q = new QueryParams();
		if (input == null || input.isBlank()) return q;
		for (String tok : input.trim().split("\\s+")) {
			int i = tok.indexOf(':');
			if (i <= 0 || i == tok.length() - 1) throw new IllegalArgumentException("Expected key:value, got '" + tok + "'");
			String key = tok.substring(0, i).toLowerCase(Locale.ROOT);
			String val = tok.substring(i + 1);
			switch (key) {
				case "source", "s", "player", "p", "user", "u" -> q.source = val;
				case "after", "a", "since" -> q.after = parseTime(val);
				case "before", "b" -> q.before = parseTime(val);
				case "range", "r", "radius" -> {
					try { q.range = Integer.parseInt(val); } catch (NumberFormatException e) { throw new IllegalArgumentException("range must be a whole number of blocks"); }
					if (q.range < 0 || q.range > 512) throw new IllegalArgumentException("range must be 0-512 blocks");
				}
				case "world", "w", "dim", "dimension" -> {
					if (val.equalsIgnoreCase("all") || val.equals("*")) q.allWorlds = true;
					else q.world = val.contains(":") ? val : "minecraft:" + val;
				}
				case "limit", "l" -> {
					try { q.limit = Math.max(1, Math.min(100, Integer.parseInt(val))); } catch (NumberFormatException e) { throw new IllegalArgumentException("limit must be a number"); }
				}
				default -> throw new IllegalArgumentException("Unknown parameter '" + key + "' (use source:, after:, before:, range:, world:, limit:)");
			}
		}
		return q;
	}

	static long parseTime(String v) {
		String s = v.toLowerCase(Locale.ROOT);
		if (DURATION_FULL.matcher(s).matches()) {
			long ms = 0;
			Matcher m = DURATION_PART.matcher(s);
			while (m.find()) {
				long n = Long.parseLong(m.group(1));
				ms += switch (m.group(2)) {
					case "s" -> n * 1_000L;
					case "m" -> n * 60_000L;
					case "h" -> n * 3_600_000L;
					case "d" -> n * 86_400_000L;
					case "w" -> n * 604_800_000L;
					default -> 0L;
				};
			}
			return System.currentTimeMillis() - ms;
		}
		if (v.matches("\\d{11,}")) return Long.parseLong(v);
		try {
			return LocalDateTime.parse(v).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (DateTimeParseException ignored) {}
		try {
			return LocalDate.parse(v).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (DateTimeParseException ignored) {}
		throw new IllegalArgumentException("Can't read time '" + v + "'. Use e.g. 30m, 2h, 3d, 1d12h, 2026-10-05T14:30 or 2026-10-05");
	}

	public Filter toFilter(String executorWorld, BlockPos center) {
		String w = allWorlds ? null : (world != null ? world : executorWorld);
		boolean useRange = range != null && center != null && (allWorlds ? false : (world == null || world.equals(executorWorld)));
		return new Filter(w, source, after, before,
			useRange ? center.getX() : null, useRange ? center.getY() : null, useRange ? center.getZ() : null,
			useRange ? range : null);
	}
}
