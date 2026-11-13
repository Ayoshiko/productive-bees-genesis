package com.ayoshiko.productivebeesgenesis.client.jei;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.List;
import java.util.Map;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PbCentrifugeDisplayMinecraftTest {

    @Test
    void sharedRecipeLayoutKeepsOriginalRangeAndChanceWhileIndexingBoundedStacks() {
        ItemStack original = new ItemStack(Items.DIAMOND);
        var output = new ChancedOutput(Ingredient.of(original), 1, Integer.MAX_VALUE, 0.25f);
        CentrifugeRecipe recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
            @Override
            public Map<ItemStack, ChancedOutput> getRecipeOutputs() {
                return Map.of(original, output);
            }

            @Override
            public FluidStack getFluidOutputs() {
                return FluidStack.EMPTY;
            }
        };

        IRecipeLayoutBuilder builder = mock(IRecipeLayoutBuilder.class);
        IRecipeSlotBuilder inputSlot = mock(IRecipeSlotBuilder.class, RETURNS_SELF);
        IRecipeSlotBuilder outputSlot = mock(IRecipeSlotBuilder.class, RETURNS_SELF);
        when(builder.addSlot(eq(RecipeIngredientRole.INPUT), anyInt(), anyInt())).thenReturn(inputSlot);
        when(builder.addSlot(eq(RecipeIngredientRole.OUTPUT), anyInt(), anyInt())).thenReturn(outputSlot);
        PbCentrifugeRecipeCategory.setRecipeLayout(builder, recipe, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ItemStack>> candidates = ArgumentCaptor.forClass(List.class);
        verify(outputSlot).addItemStacks(candidates.capture());
        assertEquals(PbCentrifugeRecipeCategory.MAX_DISPLAY_COUNTS, candidates.getValue().size());
        assertEquals(Integer.MAX_VALUE, candidates.getValue().getLast().getCount());
        ArgumentCaptor<IRecipeSlotRichTooltipCallback> callback =
                ArgumentCaptor.forClass(IRecipeSlotRichTooltipCallback.class);
        verify(outputSlot).addRichTooltipCallback(callback.capture());
        ITooltipBuilder tooltip = mock(ITooltipBuilder.class, RETURNS_SELF);
        callback.getValue().onRichTooltip(null, tooltip);
        verify(tooltip).add(Component.translatable("productivebees.centrifuge.tooltip.chance", "25.0%"));
        verify(tooltip).add(Component.translatable("productivebees.centrifuge.tooltip.amount", "1 - 2147483647"));
        assertEquals(0.25f, output.chance());
    }
}
