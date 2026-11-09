package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.util.MyriadRecipeGate;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 旧配方对象也必须在物品消耗及实体创建之前核对万象总开关。 */
@Mixin({BeeConversionRecipe.class, BeeBreedingRecipe.class, AdvancedBeehiveRecipe.class})
public abstract class MyriadDisabledRecipeMixin {
	@Inject(method = "matches(Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Z",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$disabledMyriadRecipe(CallbackInfoReturnable<Boolean> result) {
		if (MyriadRecipeGate.blocked((Recipe<?>) (Object) this)) {
			result.setReturnValue(false);
		}
	}
}
