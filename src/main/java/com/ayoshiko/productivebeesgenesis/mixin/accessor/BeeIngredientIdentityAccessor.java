package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BeeIngredient.class)
public interface BeeIngredientIdentityAccessor {
	@Accessor("beeType")
	ResourceLocation productivebeesgenesis$originalBeeType();
}
