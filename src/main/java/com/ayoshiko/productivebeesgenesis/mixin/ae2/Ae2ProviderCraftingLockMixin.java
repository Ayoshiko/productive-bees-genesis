package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.ProviderCraftingLockVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 插件先核对所有锁字段写入口；包括同值重建，避免相同配方两轮之间的 ABA。 */
@Mixin(targets = "appeng.helpers.patternprovider.PatternProviderLogic", remap = false)
public abstract class Ae2ProviderCraftingLockMixin implements ProviderCraftingLockVersion {
	@Unique private long productivebeesgenesis$lockVersion;
	@Override public long productivebeesgenesis$lockVersion() { return productivebeesgenesis$lockVersion; }

	@Inject(method = {
			"resetCraftingLock()V",
			"onPushPatternSuccess(Lappeng/api/crafting/IPatternDetails;)V",
			"onStackReturnedToNetwork(Lappeng/api/stacks/GenericStack;)V",
			"updateRedstoneState()V",
			"readFromNBT(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"
	}, at = @At("HEAD"), remap = false, require = 5)
	private void productivebeesgenesis$invalidateLockView(CallbackInfo callback) {
		if (productivebeesgenesis$lockVersion < Long.MAX_VALUE) productivebeesgenesis$lockVersion++;
	}
}
