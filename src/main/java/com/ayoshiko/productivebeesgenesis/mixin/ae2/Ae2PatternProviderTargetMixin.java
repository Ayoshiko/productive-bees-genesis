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
@Mixin(targets = "appeng.helpers.patternprovider.PatternProviderTarget", remap = false)
public interface Ae2PatternProviderTargetMixin {

	@Inject(method = "wrapMeStorage(Lappeng/api/storage/MEStorage;Lappeng/api/networking/security/IActionSource;)Lappeng/helpers/patternprovider/PatternProviderTarget;",
			at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void productivebeesgenesis$includeBufferedInputs(
			MEStorage storage, IActionSource source,
			CallbackInfoReturnable<PatternProviderTarget> callback) {
		callback.setReturnValue(CentrifugePatternProviderTarget.wrap(storage, callback.getReturnValue()));
	}
}
