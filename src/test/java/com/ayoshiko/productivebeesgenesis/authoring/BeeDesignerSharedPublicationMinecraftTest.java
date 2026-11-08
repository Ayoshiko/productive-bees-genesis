package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerSharedPublicationMinecraftTest {
    private static final String FIRST = "test:shared_first";
    private static final String SECOND = "test:shared_second";

    @Test
    void staleMetadataCannotRestoreRetiredOrPurgedBee() {
        var original = BeeDesignerDocument.create(FIRST);
        original.getAsJsonObject("definition").addProperty("createComb", false);
        original.addProperty("retained_extension", "original");
        assertTrue(BeeDesignerReader.sameManagedState(original, original.deepCopy()));
        var retired = BeeDesignerLifecycle.retire(original, 1, new UUID(0, 1));
        assertFalse(BeeDesignerReader.sameManagedState(original, retired));
        assertFalse(BeeDesignerReader.sameManagedState(original, BeeDesignerLifecycle.purge(retired)));
        assertFalse(BeeDesignerReader.sameManagedState(retired, BeeDesignerLifecycle.purge(retired)));
        var disabled = original.deepCopy();
        BeeDesignerDocument.array(disabled, "disabled_recipes").add("test:removed_acquisition");
        assertFalse(BeeDesignerReader.sameManagedState(original, disabled));
        var changed = original.deepCopy();
        changed.addProperty("retained_extension", "updated");
        assertFalse(BeeDesignerReader.sameManagedState(original, changed));
        assertEquals("original", original.get("retained_extension").getAsString());
        var definitions = BeeDesignerSources.definitions;
        var managed = BeeDesignerSources.managed;
        var bees = new HashMap<>(BeeReloadListener.INSTANCE.getData());
        var server = mock(MinecraftServer.class);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        when(server.registryAccess()).thenReturn(registries);
        when(server.isSameThread()).thenReturn(true);
        when(server.getRecipeManager()).thenReturn(new RecipeManager(registries));
        var resources = mock(ResourceManager.class);
        when(server.getResourceManager()).thenReturn(resources);
        try {
            var id = ResourceLocation.parse(FIRST);
            BeeDesignerSources.definitions = Map.of(id, original.get("definition").toString());
            BeeDesignerSources.managed = Map.of(FIRST, original.toString());
            var current = new HashMap<>(bees);
            current.put(id, BeeCreator.create(id, original.getAsJsonObject("definition")));
            BeeReloadListener.INSTANCE.setData(current);
            assertTrue(BeeDesignerReader.unchanged(server, original));
            BeeDesignerSources.managed = Map.of(FIRST, retired.toString());
            assertFalse(BeeDesignerReader.unchanged(server, original));
            BeeDesignerSources.managed = Map.of(FIRST, BeeDesignerLifecycle.purge(retired).toString());
            assertFalse(BeeDesignerReader.unchanged(server, original));
        } finally {
            BeeDesignerSources.definitions = definitions;
            BeeDesignerSources.managed = managed;
            BeeReloadListener.INSTANCE.setData(bees);
            BeeDesignerRecipeIndex.clear(server);
        }
    }

    @Test
    void sharedNestRemovalRequiresAllOwnersAndEmitsOneDisabledRecipe() {
        var first = BeeDesignerDocument.create(FIRST);
        var second = BeeDesignerDocument.create(SECOND);
        String id = BeeDesignerDocument.addRecipe(first, "productivebees:bee_spawning");
        var recipe = first.getAsJsonObject("recipes").getAsJsonObject(id);
        recipe.getAsJsonArray("results").add(SECOND);
        second.getAsJsonObject("recipes").add(id, recipe.deepCopy());
        assertEquals(Set.of(FIRST, SECOND), BeeDesignerLifecycle.recipeOwners(recipe));
        var changedFirst = first.deepCopy();
        changedFirst.getAsJsonObject("recipes").remove(id);
        BeeDesignerDocument.array(changedFirst, "disabled_recipes").add(id);
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.validateSharedChanges(
                first, changedFirst, Map.of(FIRST, changedFirst)));
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(
                Map.of(FIRST, changedFirst.toString(), SECOND, second.toString())));
        var changedSecond = second.deepCopy();
        changedSecond.getAsJsonObject("recipes").remove(id);
        BeeDesignerDocument.array(changedSecond, "disabled_recipes").add(id);
        var batch = Map.of(FIRST, changedFirst, SECOND, changedSecond);
        BeeDesignerLifecycle.validateSharedChanges(first, changedFirst, batch);
        BeeDesignerLifecycle.validateSharedChanges(second, changedSecond, batch);
        var files = BeeDesignerCompiler.files(Map.of(FIRST, changedFirst.toString(), SECOND, changedSecond.toString()));
        String file = "data/" + id.replace(":", "/recipe/") + ".json";
        assertTrue(files.get(file).contains("neoforge:false"));
        assertEquals(1, files.keySet().stream().filter(file::equals).count());
        assertEquals(2, recipe.getAsJsonArray("results").size());
        var remainingSecond = second.deepCopy();
        var remainingRecipe = recipe.deepCopy();
        var remainingOwners = new JsonArray();
        remainingOwners.add(SECOND);
        remainingRecipe.add("results", remainingOwners);
        remainingSecond.getAsJsonObject("recipes").add(id, remainingRecipe);
        var releaseFirst = Map.of(FIRST, changedFirst, SECOND, remainingSecond);
        BeeDesignerLifecycle.validateSharedChanges(first, changedFirst, releaseFirst);
        BeeDesignerLifecycle.validateSharedChanges(second, remainingSecond, releaseFirst);
        var remaining = BeeDesignerCompiler.files(Map.of(FIRST, changedFirst.toString(), SECOND, remainingSecond.toString()));
        assertEquals(remainingOwners, JsonParser.parseString(remaining.get(file)).getAsJsonObject().get("results"));
    }

    @Test
    void sharedMachineCannotLoseAnotherBeesAcquisitionWhenOnlyOneIsRetired() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var first = BeeDesignerDocument.create(FIRST);
        var second = BeeDesignerDocument.create(SECOND);
        var recipe = BeeDesignerMachineRecipes.create(BeeDesignerMachineRecipes.Host.MI,
                "modern_industrialization:assembler", "", FIRST);
        var egg = BeeDesignerSelections.item(BeeDesignerEggs.egg(SECOND), registries);
        BeeDesignerMachineRecipes.append(recipe, "item", false, BeeDesignerMachineRecipes.material(egg, registries));
        String id = "test:shared_machine";
        first.getAsJsonObject("recipes").add(id, recipe);
        second.getAsJsonObject("recipes").add(id, recipe.deepCopy());
        assertEquals(Set.of(FIRST, SECOND), BeeDesignerLifecycle.recipeOwners(recipe));
        String original = recipe.toString();
        var retiredFirst = BeeDesignerLifecycle.retire(first, 1, new UUID(0, 1));
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of(FIRST, retiredFirst.toString())));
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(
                Map.of(FIRST, retiredFirst.toString(), SECOND, second.toString())));
        var retiredSecond = BeeDesignerLifecycle.retire(second, 1, new UUID(0, 2));
        var disabled = BeeDesignerCompiler.files(Map.of(FIRST, retiredFirst.toString(), SECOND, retiredSecond.toString()));
        assertTrue(disabled.get("data/test/recipe/shared_machine.json").contains("neoforge:false"));
        var active = BeeDesignerCompiler.files(Map.of(FIRST, first.toString(), SECOND, second.toString()));
        var expected = recipe.deepCopy();
        expected.addProperty("productivebeesgenesis:designer_recipe", true);
        assertEquals(expected, JsonParser.parseString(active.get("data/test/recipe/shared_machine.json")));
        assertEquals(original, recipe.toString());
        var edited = first.deepCopy();
        edited.getAsJsonObject("recipes").getAsJsonObject(id).addProperty("duration", 80);
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.validateSharedChanges(
                first, edited, Map.of(FIRST, edited)));
        var consistentSecond = second.deepCopy();
        consistentSecond.getAsJsonObject("recipes").add(id, edited.getAsJsonObject("recipes").get(id).deepCopy());
        BeeDesignerLifecycle.validateSharedChanges(first, edited, Map.of(FIRST, edited, SECOND, consistentSecond));
    }
}
