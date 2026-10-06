package com.vwsdigital.blocklog.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.vwsdigital.blocklog.SourceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Attributes block changes caused while a player breaks a block, uses an item on a block (placing), or uses an item (buckets) to that player. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Shadow @Final protected ServerPlayer player;

	@WrapMethod(method = "destroyBlock")
	private boolean blocklog$destroy(BlockPos pos, Operation<Boolean> original) {
		SourceContext.push(SourceContext.of(this.player));
		try {
			return original.call(pos);
		} finally {
			SourceContext.pop();
		}
	}

	@WrapMethod(method = "useItemOn")
	private InteractionResult blocklog$useItemOn(ServerPlayer p, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
		SourceContext.push(SourceContext.of(p));
		try {
			return original.call(p, level, stack, hand, hit);
		} finally {
			SourceContext.pop();
		}
	}

	@WrapMethod(method = "useItem")
	private InteractionResult blocklog$useItem(ServerPlayer p, Level level, ItemStack stack, InteractionHand hand, Operation<InteractionResult> original) {
		SourceContext.push(SourceContext.of(p));
		try {
			return original.call(p, level, stack, hand);
		} finally {
			SourceContext.pop();
		}
	}
}
