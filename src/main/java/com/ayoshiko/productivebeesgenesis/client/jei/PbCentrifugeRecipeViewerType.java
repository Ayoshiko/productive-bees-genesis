package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.client.jei.MekanismJEIRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

/**
 * The 10.4 recipe type stores only UID and class; the JEI category supplies its icon and title.
 * <p>
 * Mekanism finalises {@code MekanismJEIRecipeType}'s static registry during class init and then
 * nulls it, so constructing a new type afterwards throws inside its own constructor. JEI linking
 * is optional for the progress bar, so the failure is downgraded to a warning instead of crashing
 * the machine GUI.
 */
public final class PbCentrifugeRecipeViewerType {
    private static boolean warned;

    private PbCentrifugeRecipeViewerType() {
    }

    @Nullable
    public static MekanismJEIRecipeType<CentrifugeRecipe> create(ResourceLocation id, ItemLike icon,
            Component title, ItemLike... workstations) {
        try {
            return new MekanismJEIRecipeType<>(id, CentrifugeRecipe.class);
        } catch (Throwable failure) {
            if (!warned) {
                warned = true;
                ProductiveBeesGenesis.LOGGER.warn(
                        "无法注册 PB 离心机 JEI 配方类型，进度条将不提供 JEI 跳转（不影响机器功能）", failure);
            }
            return null;
        }
    }
}
