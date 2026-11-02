package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 保留实体和基因，只停止万象的新繁殖、采蜜与采蜜后的转化。 */
@Mixin(ConfigurableBee.class)
public abstract class MyriadDisabledBeeMixin {
	@Inject(method = {"canSelfBreed", "isFlowerValid", "isFlowerItem", "isFlowerBlock"},
			at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$disabledMyriadWork(CallbackInfoReturnable<Boolean> result) {
		if (MyriadCreationsEventHandler.isMyriadCreationsDisabled(
				((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(false);
		}
	}

	@Inject(method = "getBreedingIngredient", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$disabledMyriadFood(CallbackInfoReturnable<Ingredient> result) {
		if (MyriadCreationsEventHandler.isMyriadCreationsDisabled(
				((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(Ingredient.EMPTY);
		}
	}

	@Inject(method = "postPollinate", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$disabledMyriadPollination(CallbackInfo result) {
		if (MyriadCreationsEventHandler.isMyriadCreationsDisabled(
				((ConfigurableBee) (Object) this).getBeeType())) {
			result.cancel();
		}
	}
}
