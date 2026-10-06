package com.vwsdigital.blocklog.db;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.state.BlockState;

/** One logged block change. States are stored as full block-state strings, e.g. minecraft:netherrack or minecraft:oak_stairs[facing=north,...]. */
public record ChangeRecord(long time, String world, int x, int y, int z, String oldState, String newState, String playerUuid, String playerName) {
	public static String serialize(BlockState state) {
		return BlockStateParser.serialize(state);
	}
}
