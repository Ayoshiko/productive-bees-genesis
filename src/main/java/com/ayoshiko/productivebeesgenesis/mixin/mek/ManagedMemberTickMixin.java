package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 必须在 frequency／upgrade／chunkloader 和子类 onUpdateServer 之前拦截。 */
@Mixin(value = TileEntityMekanism.class, remap = false)
public abstract class ManagedMemberTickMixin {
	@Inject(method = "tickServer", at = @At("HEAD"), cancellable = true)
	private static void pbg$ownedTick(Level level, BlockPos pos, BlockState state, TileEntityMekanism tile, CallbackInfo callback) {
		if (MemberBinding.isolated(tile)) callback.cancel();
	}
}
