package com.vwsdigital.blocklog.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.vwsdigital.blocklog.BlockLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every in-world block change goes through Level#setBlock -> LevelChunk#setBlockState, which returns the OLD state
 * (or null if nothing changed). We record the change right AFTER the chunk was mutated.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@WrapOperation(
		method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState blocklog$recordChange(LevelChunk chunk, BlockPos pos, BlockState newState, int flags, Operation<BlockState> original) {
		BlockState old = original.call(chunk, pos, newState, flags);
		if (old != null && (Object) this instanceof ServerLevel serverLevel) {
			try {
				BlockLog.onBlockChanged(serverLevel, pos, old, newState);
			} catch (Throwable t) {
				BlockLog.LOGGER.error("[block-log] Failed to record block change at {}", pos, t);
			}
		}
		return old;
	}
}
