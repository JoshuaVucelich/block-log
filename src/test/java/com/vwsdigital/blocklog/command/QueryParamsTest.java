package com.vwsdigital.blocklog.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryParamsTest {
	@Test
	void parsesKeysInAnyOrder() {
		QueryParams q = QueryParams.parse("limit:5 world:the_nether range:20 source:Steve after:2h");
		assertEquals("Steve", q.source);
		assertEquals(20, q.range);
		assertEquals(5, q.limit);
		assertEquals("minecraft:the_nether", q.world);
		assertTrue(!q.allWorlds);
		long expected = System.currentTimeMillis() - 2 * 3_600_000L;
		assertTrue(Math.abs(q.after - expected) < 2_000, "after:2h should be about two hours ago");
	}

	@Test
	void parsesCompoundDurationAndAbsoluteDay() {
		long start = System.currentTimeMillis();
		long parsed = QueryParams.parseTime("1d12h");
		long expected = start - (86_400_000L + 12 * 3_600_000L);
		assertTrue(Math.abs(parsed - expected) < 2_000);

		QueryParams q = QueryParams.parse("before:2026-10-05 world:all");
		assertTrue(q.allWorlds);
		assertNull(q.world);
		assertEquals(QueryParams.parseTime("2026-10-05"), q.before);
	}

	@Test
	void rejectsUnknownKeysAndRollbackRange() {
		assertThrows(IllegalArgumentException.class, () -> QueryParams.parse("foo:bar"));
		assertThrows(IllegalArgumentException.class, () -> QueryParams.parse("range:900"));
		assertThrows(IllegalArgumentException.class, () -> QueryParams.parse("after:yesterday"));
	}
}
