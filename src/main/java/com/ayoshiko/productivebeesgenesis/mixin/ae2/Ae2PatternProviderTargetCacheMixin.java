package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;
import com.ayoshiko.productivebeesgenesis.mek.ae2.CentrifugePatternProviderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2 常驻供应器使用独立缓存入口，必须同样补充原料可见性。 */
@Mixin(targets = "appeng.helpers.patternprovider.PatternProviderTargetCache", remap = false)
public abstract class Ae2PatternProviderTargetCacheMixin {
	@Inject(method = "wrapMeStorage(Lappeng/api/storage/MEStorage;)Lappeng/helpers/patternprovider/PatternProviderTarget;",
			at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$includeBufferedInputs(MEStorage storage,
			CallbackInfoReturnable<PatternProviderTarget> callback) {
		callback.setReturnValue(CentrifugePatternProviderTarget.wrap(storage, callback.getReturnValue()));
	}
}
