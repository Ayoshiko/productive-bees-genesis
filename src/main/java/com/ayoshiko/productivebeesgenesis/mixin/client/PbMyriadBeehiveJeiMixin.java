package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.jei.MyriadJeiOutputs;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "cy.jdkdigital.productivebees.compat.jei.AdvancedBeehiveRecipeCategory", remap = false)
public abstract class PbMyriadBeehiveJeiMixin implements IRecipeCategory<AdvancedBeehiveRecipe> {
    @Inject(method = "setRecipe(Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lcy/jdkdigital/productivebees/common/recipe/AdvancedBeehiveRecipe;Lmezz/jei/api/recipe/IFocusGroup;)V",
            at = @At("RETURN"), require = 0)
    private void productivebeesgenesis$random(IRecipeLayoutBuilder builder, AdvancedBeehiveRecipe recipe,
            IFocusGroup focuses, CallbackInfo ci) {
        var bee = recipe.ingredient.get();
        if (bee != null && PBConstants.MYRIADCREATIONS_TYPE.equals(bee.getBeeType())) {
            MyriadJeiOutputs.addBeehiveSlots(builder, recipe.getRecipeOutputs().size());
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, AdvancedBeehiveRecipe recipe, IFocusGroup focuses) {
        var bee = recipe.ingredient.get();
        if (bee != null && PBConstants.MYRIADCREATIONS_TYPE.equals(bee.getBeeType())) {
            MyriadJeiOutputs.addCaptions(builder);
        }
    }
}
