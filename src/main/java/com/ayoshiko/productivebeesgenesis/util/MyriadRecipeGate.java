package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;
import java.util.function.Supplier;

/** 配方身份与执行许可分开；共享蜂巢只移除万象结果，不修改其它结果及材料。 */
public final class MyriadRecipeGate {
	private MyriadRecipeGate() {}

	public static boolean isMyriad(Supplier<BeeIngredient> supplier) {
		BeeIngredient ingredient = supplier == null ? null : supplier.get();
		return ingredient != null
				&& MyriadCreationsEventHandler.isMyriadCreationsBeeType(ingredient.getBeeType());
	}

	public static boolean blocked(Recipe<?> recipe) {
		if (MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return false;
		}
		if (recipe instanceof BeeConversionRecipe conversion) {
			return isMyriad(conversion.source) || isMyriad(conversion.result);
		}
		if (recipe instanceof BeeBreedingRecipe breeding) {
			return isMyriad(breeding.parent1) || isMyriad(breeding.parent2) || isMyriad(breeding.offspring);
		}
		return recipe instanceof AdvancedBeehiveRecipe produce && isMyriad(produce.ingredient);
	}

	public static RecipeHolder<BeeSpawningRecipe> withoutMyriad(RecipeHolder<BeeSpawningRecipe> holder) {
		BeeSpawningRecipe recipe = holder.value();
		List<Supplier<BeeIngredient>> outputs = recipe.output.stream()
				.filter(output -> !isMyriad(output)).toList();
		if (outputs.size() == recipe.output.size()) {
			return holder;
		}
		if (outputs.isEmpty()) {
			return null;
		}
		return new RecipeHolder<>(holder.id(),
				new BeeSpawningRecipe(recipe.ingredient, recipe.spawnItem, outputs, recipe.biomes));
	}
}
