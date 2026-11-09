package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.MyriadRecipeGate;
import cy.jdkdigital.productivebees.common.block.SolitaryNest;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** 共享蜂巢的缓存配方只过滤万象结果，保留其它蜂种的原始生成条件。 */
@Mixin(SolitaryNest.class)
public abstract class MyriadDisabledNestMixin {
	@Inject(method = "getSpawningRecipes", at = @At("RETURN"), cancellable = true, remap = false)
	private static void productivebeesgenesis$disabledMyriadNest(
			CallbackInfoReturnable<List<RecipeHolder<BeeSpawningRecipe>>> result) {
		if (MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return;
		}
		List<RecipeHolder<BeeSpawningRecipe>> recipes = new ArrayList<>(result.getReturnValue().size());
		for (RecipeHolder<BeeSpawningRecipe> holder : result.getReturnValue()) {
			RecipeHolder<BeeSpawningRecipe> filtered = MyriadRecipeGate.withoutMyriad(holder);
			if (filtered != null) {
				recipes.add(filtered);
			}
		}
		result.setReturnValue(recipes);
	}
}
