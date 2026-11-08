package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.BlockConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeConversionIndexMinecraftTest {
    private static final ResourceLocation BEE = ResourceLocation.parse("test:conversion_bee");
    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @BeforeEach
    @AfterEach
    void invalidate() {
        BeeConversionQueries.invalidate();
    }

    @Test
    void failedSecondIndexPublishesNothingAndRetriesWithoutDuplicateEntries() {
        var item = itemRecipe("first", () -> new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), BEE));
        var block = blockRecipe();
        var manager = spy(new RecipeManager(REGISTRIES));
        manager.replaceRecipes(List.of(item, block));
        doThrow(new IllegalStateException("block index failure"))
                .when(manager).getAllRecipesFor(ModRecipeTypes.BLOCK_CONVERSION_TYPE.get());
        var level = level(manager, 1_000);

        BeeConversionQueries.ensureLoaded(level);
        int failedVersion = BeeConversionQueries.getVersion();
        assertFalse(BeeConversionQueries.hasAnyConversionRecipe(BEE));
        assertNull(BeeConversionQueries.findItemConversionRecipe(BEE, new ItemStack(Items.STONE)));

        doReturn(List.of(block)).when(manager).getAllRecipesFor(ModRecipeTypes.BLOCK_CONVERSION_TYPE.get());
        when(level.getGameTime()).thenReturn(1_099L);
        BeeConversionQueries.ensureLoaded(level);
        assertEquals(failedVersion, BeeConversionQueries.getVersion());
        assertFalse(BeeConversionQueries.hasAnyConversionRecipe(BEE));

        when(level.getGameTime()).thenReturn(1_100L);
        BeeConversionQueries.ensureLoaded(level);
        assertTrue(BeeConversionQueries.hasItemConversionRecipes(BEE));
        assertTrue(BeeConversionQueries.hasBlockConversionRecipes(BEE));
        assertSame(item, BeeConversionQueries.findItemConversionRecipe(BEE, new ItemStack(Items.STONE)));
        assertSame(block, BeeConversionQueries.findBlockConversionRecipeForItem(BEE, new ItemStack(Blocks.DIRT)));
        assertEquals(failedVersion + 1, BeeConversionQueries.getVersion());
        BeeConversionQueries.ensureLoaded(level);
        assertEquals(failedVersion + 1, BeeConversionQueries.getVersion());
        verify(manager, times(2)).getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get());
    }

    @Test
    void failedGenerationDoesNotDelayLowerClockAfterInvalidateOrManagerReplacement() {
        var manager = spy(new RecipeManager(REGISTRIES));
        doThrow(new IllegalStateException("old world failure"))
                .when(manager).getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get());
        var level = level(manager, 40_000);
        BeeConversionQueries.ensureLoaded(level);

        var item = itemRecipe("replacement", () -> new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), BEE));
        var replacement = new RecipeManager(REGISTRIES);
        replacement.replaceRecipes(List.of(item));
        BeeConversionQueries.invalidate();
        doReturn(List.of(item)).when(manager).getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get());
        when(level.getGameTime()).thenReturn(1L);
        BeeConversionQueries.ensureLoaded(level);
        assertSame(item, BeeConversionQueries.findItemConversionRecipe(BEE, new ItemStack(Items.STONE)));

        BeeConversionQueries.invalidate();
        doThrow(new IllegalStateException("second old world failure"))
                .when(manager).getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get());
        when(level.getGameTime()).thenReturn(80_000L);
        BeeConversionQueries.ensureLoaded(level);
        when(level.getRecipeManager()).thenReturn(replacement);
        when(level.getGameTime()).thenReturn(2L);
        BeeConversionQueries.ensureLoaded(level);
        assertSame(item, BeeConversionQueries.findItemConversionRecipe(BEE, new ItemStack(Items.STONE)));
    }

    @Test
    void failedBeeSupplierPreservesValidRecipesAndRemainsRetryable() {
        var first = itemRecipe("first", () -> new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), BEE));
        var bad = itemRecipe("bad", () -> {
            throw new IllegalStateException("unavailable bee ingredient");
        });
        var manager = spy(new RecipeManager(REGISTRIES));
        manager.replaceRecipes(List.of(first, bad));
        var level = level(manager, 400);
        BeeConversionQueries.ensureLoaded(level);
        int incompleteVersion = BeeConversionQueries.getVersion();
        assertSame(first, BeeConversionQueries.findItemConversionRecipe(BEE, new ItemStack(Items.STONE)));

        manager.replaceRecipes(List.of(first));
        when(level.getGameTime()).thenReturn(5L);
        BeeConversionQueries.ensureLoaded(level);
        assertSame(first, BeeConversionQueries.findItemConversionRecipe(BEE, new ItemStack(Items.STONE)));
        assertEquals(incompleteVersion + 1, BeeConversionQueries.getVersion());
        verify(manager, times(2)).getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get());
    }

    private static RecipeHolder<ItemConversionRecipe> itemRecipe(String name, Supplier<BeeIngredient> bee) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("test", name),
                new ItemConversionRecipe(List.of(bee), Ingredient.of(Items.STONE),
                        new ItemStack(Items.DIAMOND), 1, false));
    }

    private static RecipeHolder<BlockConversionRecipe> blockRecipe() {
        return new RecipeHolder<>(ResourceLocation.parse("test:block"),
                new BlockConversionRecipe(List.of(() -> new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), BEE)),
                        Ingredient.of(Items.DIRT), Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                        1, Optional.empty(), Optional.empty(), false));
    }

    private static Level level(RecipeManager manager, long tick) {
        var level = mock(Level.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.getGameTime()).thenReturn(tick);
        return level;
    }
}
