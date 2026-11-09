package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.MyriadRecipeGate;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 不修改 PB 群系缓存；每次取候选时只过滤万象，重新启用后仍可使用原候选。 */
@Mixin(BeeFishingRecipe.class)
public abstract class MyriadDisabledFishingMixin {
	@Inject(method = "getRecipeList", at = @At("RETURN"), cancellable = true, remap = false)
	private static void productivebeesgenesis$disabledMyriadFishing(
			CallbackInfoReturnable<List<BeeFishingRecipe>> result) {
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			result.setReturnValue(result.getReturnValue().stream()
					.filter(recipe -> !MyriadRecipeGate.isMyriad(recipe.output)).toList());
		}
	}
}
