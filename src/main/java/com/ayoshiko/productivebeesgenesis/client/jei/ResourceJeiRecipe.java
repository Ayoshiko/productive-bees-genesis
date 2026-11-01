package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeView;

/** 蜜脾块只增加独立抽样份数，不把概率乘四或把 long 产量截断。 */
public record ResourceJeiRecipe(ResourceRecipeView recipe, boolean block) {
	public int portions() { return block ? 4 : 1; }
}
