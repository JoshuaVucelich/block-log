package com.vwsdigital.blocklog.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.vwsdigital.blocklog.SourceContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Blocks destroyed by an explosion are attributed to the player who caused it (e.g. lit the TNT), else "#explosion". */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow public abstract LivingEntity getIndirectSourceEntity();

	@WrapMethod(method = "explode")
	private int blocklog$explode(Operation<Integer> original) {
		LivingEntity src = null;
		try { src = getIndirectSourceEntity(); } catch (Throwable ignored) {}
		SourceContext.push(src instanceof ServerPlayer sp ? SourceContext.of(sp) : SourceContext.EXPLOSION);
		try {
			return original.call();
		} finally {
			SourceContext.pop();
		}
	}
}
