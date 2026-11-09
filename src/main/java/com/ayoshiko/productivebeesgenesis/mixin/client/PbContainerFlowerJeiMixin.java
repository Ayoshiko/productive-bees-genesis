package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.jei.ContainerFlowerJei;
import cy.jdkdigital.productivebees.common.recipe.BeeFloweringRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "cy.jdkdigital.productivebees.compat.jei.BeeFloweringRecipeCategory", remap = false)
public abstract class PbContainerFlowerJeiMixin {
    @Inject(method = "setRecipe(Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lcy/jdkdigital/productivebees/common/recipe/BeeFloweringRecipe;Lmezz/jei/api/recipe/IFocusGroup;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void productivebeesgenesis$container(IRecipeLayoutBuilder builder, BeeFloweringRecipe recipe,
            IFocusGroup focuses, CallbackInfo ci) {
        if (ContainerFlowerJei.setRecipe(builder, recipe)) ci.cancel();
    }
}
