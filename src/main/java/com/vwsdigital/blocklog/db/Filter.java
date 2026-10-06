package com.vwsdigital.blocklog.db;

/** Query filter. Null fields are ignored. Range is a cube (|dx|,|dy|,|dz| <= range) around the center. */
public record Filter(String world, String source, Long after, Long before, Integer cx, Integer cy, Integer cz, Integer range) {
	public boolean isEmpty() {
		return source == null && after == null && before == null && range == null;
	}
}
