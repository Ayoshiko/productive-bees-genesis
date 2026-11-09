package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.init.ModEntities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Set;

/** 原生蜜蜂的实体与 AI 由上游注册；工程只能覆盖其已有数据配方。 */
public final class BeeDesignerNativeBees {
	public static final String RECIPES_ONLY = "native_recipes_only";
	private static final Set<String> RECIPE_TYPES = Set.of("productivebees:advanced_beehive",
			"productivebees:centrifuge", "productivebees:item_conversion", "productivebees:block_conversion",
			"productivebees:bee_breeding", "productivebees:bee_conversion", "productivebees:bee_spawning",
			"productivebees:bee_fishing", "productivebees:bottler", "minecraft:crafting_shaped", "minecraft:crafting_shapeless");

	private BeeDesignerNativeBees() {
	}

	public static boolean contains(ResourceLocation bee) {
		if (EntityType.getKey(EntityType.BEE).equals(bee)) {
			return true;
		}
		if (!BuiltInRegistries.ENTITY_TYPE.containsKey(bee) || ModEntities.CONFIGURABLE_BEE.getId().equals(bee)) {
			return false;
		}
		return ModEntities.HIVE_BEES.getEntries().stream().anyMatch(holder -> holder.getId().equals(bee))
				|| ModEntities.SOLITARY_BEES.getEntries().stream().anyMatch(holder -> holder.getId().equals(bee));
	}

	public static boolean recipesOnly(JsonObject document) {
		var marker = document.get(RECIPES_ONLY);
		return marker != null && marker.isJsonPrimitive() && marker.getAsJsonPrimitive().isBoolean() && marker.getAsBoolean();
	}

	static JsonObject document(ResourceLocation bee) {
		if (!contains(bee)) {
			throw new IllegalArgumentException("designer.error.bee_missing|" + bee);
		}
		JsonObject document = BeeDesignerDocument.create(bee.toString());
		document.addProperty(RECIPES_ONLY, true);
		document.add("definition", new JsonObject());
		return document;
	}

	public static boolean supportsRecipe(JsonObject recipe) {
		return RECIPE_TYPES.contains(BeeDesignerDocument.text(recipe, "type", "")) || BeeDesignerMachineRecipes.host(recipe) != null;
	}

	static void validate(JsonObject document) {
		ResourceLocation bee = ResourceLocation.parse(document.get("id").getAsString());
		if (!contains(bee) || !recipesOnly(document) || !document.get("definition_path").getAsString().equals(bee.toString())
				|| !document.getAsJsonObject("definition").isEmpty() || !document.getAsJsonObject("names").isEmpty()
				|| document.has(BeeDesignerLifecycle.FIELD) || document.has("production_nodes")) {
			throw new IllegalArgumentException("designer.error.native_recipe_scope|" + bee);
		}
		for (var recipe : document.getAsJsonObject("recipes").entrySet()) {
			if (!supportsRecipe(recipe.getValue().getAsJsonObject())) {
				throw new IllegalArgumentException("designer.error.native_recipe_scope|" + bee);
			}
		}
	}
}
