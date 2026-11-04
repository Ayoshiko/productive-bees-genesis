package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.util.CompiledBeeTypeFilter;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class MyriadJeiOutputsMinecraftTest {
    @BeforeAll
    static void loadConfigs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unloadConfigs() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void realSpecialCombsFullComponentsAndFiltersMatchProductionEligibility() {
        ItemStack ghostly = comb("honeycomb_ghostly");
        ItemStack milky = comb("honeycomb_milky");
        ItemStack powdery = comb("honeycomb_powdery");
        ItemStack named = new ItemStack(Items.HONEYCOMB);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("完整组件候选"));
        var recipes = List.of(
                recipe("ghostly", type("ghostly"), ghostly, 1),
                recipe("rancher", new BeeIngredient(ModEntities.RANCHER_BEE.get()), milky, 1),
                recipe("creeper", new BeeIngredient(ModEntities.CREEPER_BEE.get()), powdery, 1),
                recipe("plain", type("variants"), new ItemStack(Items.HONEYCOMB), 1),
                recipe("named", type("variants"), named, 1),
                recipe("duplicate", type("variants"), named, 1),
                recipe("not_comb", type("diamond"), new ItemStack(Items.DIAMOND), 1),
                recipe("zero_chance", type("zero"), new ItemStack(Items.HONEYCOMB), 0),
                recipe("self", new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), PBConstants.MYRIADCREATIONS_TYPE),
                        ghostly, 1));
        ServerLevel level = level(recipes);
        var preview = MyriadJeiOutputs.buildPreview(level, CompiledBeeTypeFilter.compile("DISABLED", List.of()), List.of());
        assertEquals(5, preview.honeycombs().size());
        for (ItemStack expected : List.of(ghostly, milky, powdery, named, new ItemStack(Items.HONEYCOMB))) {
            assertTrue(preview.honeycombs().stream().anyMatch(actual -> ItemStack.isSameItemSameComponents(expected, actual)));
        }
        assertTrue(preview.blocks().stream().noneMatch(ItemStack::isEmpty));
        assertEquals(List.of(), MyriadJeiOutputs.buildPreview(level,
                CompiledBeeTypeFilter.compile("WHITELIST", List.of()), List.of()).honeycombs());
        var filtered = MyriadJeiOutputs.buildPreview(level,
                CompiledBeeTypeFilter.compile("WHITELIST", List.of("productivebees:ghostly")), List.of());
        assertEquals(1, filtered.honeycombs().size());
        assertTrue(ItemStack.isSameItemSameComponents(ghostly, filtered.honeycombs().getFirst()));
        assertEquals(1, filtered.blocks().size());
    }

    @Test
    void centrifugeHasThreeCandidatesAndHiveKeepsItsOwnCombBeforeThreeCandidates() {
        var candidates = List.of(new ItemStack(Items.HONEYCOMB), comb("honeycomb_ghostly"), comb("honeycomb_milky"));
        var centrifuge = mock(IRecipeLayoutBuilder.class, RETURNS_DEEP_STUBS);
        MyriadJeiOutputs.addRandomSlots(centrifuge, candidates, false, 0);
        verify(centrifuge).addSlot(RecipeIngredientRole.OUTPUT, 69, 27);
        verify(centrifuge).addSlot(RecipeIngredientRole.OUTPUT, 87, 27);
        verify(centrifuge).addSlot(RecipeIngredientRole.OUTPUT, 105, 27);
        var hive = mock(IRecipeLayoutBuilder.class, RETURNS_DEEP_STUBS);
        MyriadJeiOutputs.addRandomSlots(hive, candidates, false, 1);
        verify(hive).addSlot(RecipeIngredientRole.OUTPUT, 87, 27);
        verify(hive).addSlot(RecipeIngredientRole.OUTPUT, 105, 27);
        verify(hive).addSlot(RecipeIngredientRole.OUTPUT, 69, 45);
        verify(hive, never()).addSlot(RecipeIngredientRole.OUTPUT, 69, 27);
        var empty = mock(IRecipeLayoutBuilder.class, RETURNS_DEEP_STUBS);
        MyriadJeiOutputs.addRandomSlots(empty, List.of(), false, 0);
        verify(empty, never()).addSlot(eq(RecipeIngredientRole.OUTPUT), anyInt(), anyInt());
        verify(empty).addSlot(RecipeIngredientRole.RENDER_ONLY, 69, 27);
    }

    private static ServerLevel level(List<RecipeHolder<AdvancedBeehiveRecipe>> recipes) {
        var level = mock(ServerLevel.class);
        var manager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(manager.getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get())).thenReturn(recipes);
        return level;
    }

    private static RecipeHolder<AdvancedBeehiveRecipe> recipe(String name, BeeIngredient bee, ItemStack stack, float chance) {
        return new RecipeHolder<>(id(name), new AdvancedBeehiveRecipe(() -> bee,
                List.of(new ChancedOutput(Ingredient.of(stack), 1, 1, chance))));
    }

    private static BeeIngredient type(String path) {
        return new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id(path));
    }

    private static ItemStack comb(String path) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id(path)));
        assertFalse(stack.isEmpty(), path);
        return stack;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("productivebees", path);
    }
}
