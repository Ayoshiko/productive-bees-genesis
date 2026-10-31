package com.ayoshiko.productivebeesgenesis.compat.kubejs;

import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.google.gson.JsonElement;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.RecipesEventJS;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.ClassFilter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** KubeJS 6 / Minecraft 1.20.1 integration; loaded only by KubeJS discovery. */
public class ProductiveBeesGenesisKubeJSPlugin extends KubeJSPlugin {
    @Override public void registerClasses(ScriptType type, ClassFilter filter) {
        filter.allow("com.ayoshiko.productivebeesgenesis.compat.kubejs");
    }

    @Override public void registerBindings(BindingsEvent event) {
        event.add("MYRIAD_CREATIONS", PBConstants.MYRIADCREATIONS_TYPE_STRING);
        event.add("PB_MOD_ID", PBConstants.PRODUCTIVE_BEES_MOD_ID);
    }

    @Override public void registerEvents() { MyriadBeeEvents.GROUP.register(); }

    @Override public void injectRuntimeRecipes(RecipesEventJS event, RecipeManager manager,
            Map<ResourceLocation, Recipe<?>> recipesByName) {
        Map<ResourceLocation, JsonElement> pending = new HashMap<>();
        var removed = new HashSet<ResourceLocation>();
        MyriadBeeEvents.REGISTER.post(ScriptType.SERVER, new MyriadBeeRegisterEventJS(pending, removed));
        Map<ResourceLocation, Recipe<?>> parsed = new HashMap<>();
        // Validate every addition before changing the live map; failed parsing cannot partly apply removals.
        pending.forEach((id, element) -> {
            var json = element.getAsJsonObject();
            var serializerId = new ResourceLocation(GsonHelper.getAsString(json, "type"));
            var serializer = BuiltInRegistries.RECIPE_SERIALIZER.getOptional(serializerId).orElseThrow(
                    () -> new IllegalArgumentException("Unknown recipe serializer " + serializerId));
            Recipe<?> recipe = serializer.fromJson(id, json);
            if (recipe == null) throw new IllegalArgumentException("Recipe serializer returned null for " + id);
            parsed.put(id, recipe);
        });
        removed.forEach(recipesByName::remove);
        recipesByName.putAll(parsed);
    }
}
