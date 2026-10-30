package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerReaderMinecraftTest {

    @BeforeAll
    static void loadConfig() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unloadConfig() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    private static RegistryAccess.Frozen registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
    }

    @Test
    void activePbGroupResolvesConditionalDefinitionAndOriginWithoutGuessingNamespace() {
        var bee = ResourceLocation.parse("productivebees:antimony");
        var runtime = new CompoundTag();
        runtime.putString("group", "Modern_industrialization");
        var definitions = BeeDesignerSources.definitions;
        var packs = BeeDesignerSources.sourcePacks;
        var data = BeeReloadListener.INSTANCE.getData();
        try {
            var mi = ResourceLocation.parse("productivebees:modern_industrialization/antimony");
            var chemistry = ResourceLocation.parse("productivebees:chemlib/antimony");
            BeeDesignerSources.definitions = Map.of(mi, "{}", chemistry, "{}");
            BeeDesignerSources.sourcePacks = Map.of(mi, "mod/productivebees", chemistry, "mod/productivebees");
            BeeReloadListener.INSTANCE.setData(Map.of(bee, runtime));
            assertEquals(mi, BeeDesignerReader.definitionPath(bee));
            assertEquals("pb", BeeDesignerOrigins.describe(bee).get("kind").getAsString());
            runtime.putString("group", "Chemlib");
            assertEquals(chemistry, BeeDesignerReader.definitionPath(bee));
        } finally {
            BeeDesignerSources.definitions = definitions;
            BeeDesignerSources.sourcePacks = packs;
            BeeReloadListener.INSTANCE.setData(data);
        }
    }

    @Test
    void unrelatedUnpackedCraftingCannotPreventReadingAnActiveBee() {
        var bee = ResourceLocation.parse("test:active");
        var data = BeeReloadListener.INSTANCE.getData();
        var definitions = BeeDesignerSources.definitions;
        try {
            BeeReloadListener.INSTANCE.setData(Map.of(bee, new CompoundTag()));
            BeeDesignerSources.definitions = Map.of(bee, "{}");
            Recipe<?> recipe = unpacked(new ItemStack(Items.DIAMOND));
            assertTrue(Recipe.CODEC.encodeStart(registries().createSerializationContext(JsonOps.INSTANCE), recipe).error().isPresent());
            MinecraftServer server = mock(MinecraftServer.class);
            when(server.isSameThread()).thenReturn(true);
            RecipeManager manager = mock(RecipeManager.class);
            ResourceManager resources = mock(ResourceManager.class);
            when(server.registryAccess()).thenReturn(registries());
            when(server.getRecipeManager()).thenReturn(manager);
            when(server.getResourceManager()).thenReturn(resources);
            when(manager.getRecipes()).thenReturn(List.of(new RecipeHolder<>(ResourceLocation.parse("test:unpacked"), recipe)));
            var document = BeeDesignerReader.snapshot(server, bee).document();
            assertTrue(document.getAsJsonObject("recipes").isEmpty());
            assertEquals("test:active", document.get("id").getAsString());
        } finally {
            BeeDesignerSources.definitions = definitions;
            BeeReloadListener.INSTANCE.setData(data);
        }
    }

    @Test
    void targetUnpackedCraftingRetainsGridIngredientsEggComponentsAndBookOptions() {
        ItemStack egg = BeeDesignerEggs.egg("test:active");
        ShapedRecipe original = unpacked(egg);
        var encoded = BeeDesignerReader.encode(new RecipeHolder<>(ResourceLocation.parse("test:egg"), original), registries());
        ShapedRecipe restored = (ShapedRecipe) Recipe.CODEC.parse(registries().createSerializationContext(JsonOps.INSTANCE), encoded).getOrThrow();
        assertEquals(2, restored.getWidth());
        assertEquals(2, restored.getHeight());
        assertEquals("author_group", restored.getGroup());
        assertEquals(CraftingBookCategory.REDSTONE, restored.category());
        assertFalse(restored.showNotification());
        assertTrue(ItemStack.isSameItemSameComponents(egg, restored.getResultItem(registries())));
        assertEquals(egg.getCount(), restored.getResultItem(registries()).getCount());
        for (int index = 0; index < 4; index++) {
            assertEquals(Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, original.getIngredients().get(index)).getOrThrow(),
                    Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, restored.getIngredients().get(index)).getOrThrow());
        }
        assertEquals(encoded, BeeDesignerReader.encode(new RecipeHolder<>(ResourceLocation.parse("test:egg"), restored), registries()));
    }

    @Test
    void localizedErrorKeySurvivesWrappedCauseAndUnknownDetailsHaveTranslatedContext() {
        assertEquals("designer.error.recipe_read|test:egg", BeeDesignerServer.safeMessage(new RuntimeException(
                new IllegalArgumentException("designer.error.recipe_read|test:egg", new IllegalStateException("Cannot encode unpacked recipe")))));
        assertEquals("designer.error.operation_detail|unknown host failure", BeeDesignerServer.safeMessage(new IllegalStateException("unknown host failure")));
    }

    @Test
    void pbZeroProcessingTimeFollowsWorldDefaultWhileResourceTimeRemainsPositive() {
        var json = BeeDesignerDocument.recipe("productivebees:centrifuge");
        json.add("ingredient", com.google.gson.JsonParser.parseString("{\"item\":\"minecraft:honeycomb\"}"));
        json.add("outputs", new com.google.gson.JsonArray());
        json.addProperty("processingTime", 0);
        Recipe<?> recipe = Recipe.CODEC.parse(registries().createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
        assertEquals(cy.jdkdigital.productivebees.ProductiveBeesConfig.GENERAL.centrifugeProcessingTime.get(),
                ((cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe) recipe).getProcessingTime());
        assertDoesNotThrow(() -> BeeDesignerCompiler.validateAmounts(json, registries()));
        json.addProperty("type", "productivebeesgenesis:resource_centrifuging");
        json.remove("processingTime");
        json.addProperty("processing_time", 0);
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateAmounts(json, registries()));
    }

    private static ShapedRecipe unpacked(ItemStack output) {
        var ingredients = NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.HONEY_BOTTLE), Ingredient.EMPTY,
                Ingredient.of(Items.POPPY), Ingredient.of(Items.HONEY_BOTTLE));
        return new ShapedRecipe("author_group", CraftingBookCategory.REDSTONE,
                new ShapedRecipePattern(2, 2, ingredients, Optional.empty()), output, false);
    }
}
