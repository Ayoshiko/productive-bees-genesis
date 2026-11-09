package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 所有 Mek/ME/EM/EME 安装器共用的前置门；Mekanism 此方法为 final，不能在宿主覆盖。 */
@Mixin(value = TileEntityMekanism.class, remap = false)
public abstract class ResourceUpgradeGuardMixin {
	@Inject(method = "canBeUpgraded", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$guardResourceUpgrade(CallbackInfoReturnable<Boolean> result) {
		var host = (BlockEntity) (Object) this;
		if (host.getLevel() instanceof ServerLevel
				&& (host instanceof TileEntityMekApiary || host instanceof IMekCentrifugeTile)
				&& !ResourceHiveHost.canUpgrade(host)) result.setReturnValue(false);
	}
}
