package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;
import com.ayoshiko.productivebeesgenesis.mek.ae2.CentrifugePatternProviderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 仅包装本模组原生存储，其他 AE2 目标保持原实现。 */
@Mixin(targets = "appeng.helpers.patternprovider.PatternProviderTarget$1", remap = false)
public abstract class Ae2PatternProviderTargetMixin {
	@org.spongepowered.asm.mixin.Unique
	private MEStorage productivebeesgenesis$storage;
	@Inject(method = "<init>", at = @At("RETURN"))
	private void productivebeesgenesis$captureStorage(MEStorage storage, IActionSource source,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
		productivebeesgenesis$storage = storage;
	}

	@Inject(method = "containsPatternInput", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$includeBufferedInputs(java.util.Set<appeng.api.stacks.AEKey> inputs,
			CallbackInfoReturnable<Boolean> callback) {
		if (CentrifugePatternProviderTarget.containsBufferedInput(productivebeesgenesis$storage, inputs)) callback.setReturnValue(true);
	}
	@Inject(method = "insert", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$limitPushes(appeng.api.stacks.AEKey key, long amount,
			appeng.api.config.Actionable mode, CallbackInfoReturnable<Long> callback) {
		if (CentrifugePatternProviderTarget.rejectExternalPush(productivebeesgenesis$storage, amount, mode)) callback.setReturnValue(0L);
	}
}
