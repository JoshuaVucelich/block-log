package com.vwsdigital.blocklog;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;

/** Thread-local "who is causing block changes right now" stack, set by mixins around player actions and explosions. */
public final class SourceContext {
	public record Source(String uuid, String name) {}

	public static final Source WORLD = new Source(null, "#world");
	public static final Source EXPLOSION = new Source(null, "#explosion");
	public static final Source ROLLBACK = new Source(null, "#rollback");

	private static final ThreadLocal<ArrayDeque<Source>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

	private SourceContext() {}

	public static Source of(ServerPlayer player) {
		return new Source(player.getUUID().toString(), player.getScoreboardName());
	}

	public static void push(Source s) { STACK.get().push(s); }

	public static void pop() {
		ArrayDeque<Source> d = STACK.get();
		if (!d.isEmpty()) d.pop();
	}

	public static Source current() { return STACK.get().peek(); }
}
