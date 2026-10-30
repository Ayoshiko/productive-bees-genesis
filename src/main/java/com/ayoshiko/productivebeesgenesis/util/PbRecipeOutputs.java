package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.common.recipe.TagOutputRecipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

/** Productive Bees 12 stores fluid results as a fluid name and amount pair. */
public final class PbRecipeOutputs {
    private PbRecipeOutputs() {}

    public static FluidStack fluid(CentrifugeRecipe recipe) {
        var result = recipe.getFluidOutputs();
        if (result != null && result.getFirst() != null && result.getFirst() != Fluids.EMPTY
                && result.getSecond() > 0) {
            return new FluidStack(result.getFirst(), result.getSecond());
        }
        return fluid(recipe.fluidOutput);
    }

    public static FluidStack fluid(com.mojang.datafixers.util.Pair<String, Integer> output) {
        if (output == null || output.getSecond() <= 0) return FluidStack.EMPTY;
        for (var fluid : TagOutputRecipe.getAllFluidsFromName(output.getFirst())) {
            if (fluid != Fluids.EMPTY) return new FluidStack(fluid, output.getSecond());
        }
        return FluidStack.EMPTY;
    }
}
