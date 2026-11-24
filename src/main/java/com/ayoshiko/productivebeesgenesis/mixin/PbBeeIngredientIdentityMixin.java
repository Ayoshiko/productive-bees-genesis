package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BeeIngredient.class)
public abstract class PbBeeIngredientIdentityMixin {
	@Inject(method = "getBeeType", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$resolveRecipeIdentity(CallbackInfoReturnable<ResourceLocation> ci) {
		ci.setReturnValue(BeeDesignerIdentity.resolve(ci.getReturnValue()));
	}
}
