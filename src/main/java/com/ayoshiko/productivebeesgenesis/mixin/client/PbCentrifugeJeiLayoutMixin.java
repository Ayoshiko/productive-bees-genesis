package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.jei.PbCentrifugeRecipeCategory;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PB 在第三项后清零索引，导致后续物品和流体重叠；只替换 JEI 展示布局。 */
@Mixin(targets = "cy.jdkdigital.productivebees.compat.jei.CentrifugeRecipeCategory", remap = false)
public abstract class PbCentrifugeJeiLayoutMixin {
    @Inject(method = "setRecipe(Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lcy/jdkdigital/productivebees/common/recipe/CentrifugeRecipe;Lmezz/jei/api/recipe/IFocusGroup;Z)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void productivebeesgenesis$layout(IRecipeLayoutBuilder builder, CentrifugeRecipe recipe,
                                             IFocusGroup focuses, boolean stripWax, CallbackInfo ci) {
        PbCentrifugeRecipeCategory.setRecipeLayout(builder, recipe, stripWax);
        ci.cancel();
    }
}
