package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 配方模板只改产出的蜂身份，目标定义、输入和应用基准保持原值。 */
public final class BeeDesignerRecipeTemplates {
	public record Plan(BeeDesignerMigration.Plan drafts, int replaced) {
	}

	private BeeDesignerRecipeTemplates() {
	}

	public static boolean supports(JsonObject recipe) {
		if (BeeDesignerLifecycle.recipeOwners(recipe).size() > 1) {
			return false;
		}
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			return true;
		}
		return switch (BeeDesignerDocument.text(recipe, "type", "")) {
			case "minecraft:crafting_shaped", "minecraft:crafting_shapeless", "productivebees:bottler",
					"productivebees:bee_breeding", "productivebees:bee_conversion",
					"productivebees:bee_spawning", "productivebees:bee_fishing" -> true;
			default -> false;
		};
	}

	public static Plan plan(JsonObject source, String recipeId, Map<String, String> targets, boolean replaceMatching) {
		String sourceId = source.get("id").getAsString();
		JsonObject template = source.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		if (!supports(template) || !BeeDesignerReader.belongs(template, sourceId)) {
			throw new IllegalArgumentException("designer.error.recipe_template");
		}
		Map<String, String> documents = new LinkedHashMap<>();
		Map<String, String> identities = new LinkedHashMap<>();
		int replaced = 0;
		long bytes = 0;
		for (var target : targets.entrySet()) {
			JsonObject document = BeeDesignerProtocol.parse(target.getValue());
			String id = document.get("id").getAsString();
			if (document.has("_editor_recovery") || BeeDesignerLifecycle.deleted(document)) {
				throw new IllegalArgumentException("designer.error.collection_unfinished");
			}
			JsonObject recipe = template.deepCopy();
			retarget(recipe, sourceId, id);
			ResourceLocation bee = ResourceLocation.parse(id);
			ResourceLocation templateId = ResourceLocation.parse(recipeId);
			String nextId = sourceId.equals(id) ? recipeId : bee.getNamespace() + ":bee_designer/" + bee.getPath()
					+ "/template/" + templateId.getNamespace() + "/" + templateId.getPath();
			JsonObject recipes = document.getAsJsonObject("recipes");
			List<String> removed = new ArrayList<>();
			if (replaceMatching) {
				for (var entry : recipes.entrySet()) {
					JsonObject existing = entry.getValue().getAsJsonObject();
					var owners = BeeDesignerLifecycle.recipeOwners(existing);
					if (!entry.getKey().equals(nextId) && sameRoute(existing, recipe)
							&& BeeDesignerReader.belongs(existing, id) && (owners.isEmpty() || owners.equals(java.util.Set.of(id)))) {
						removed.add(entry.getKey());
					}
				}
			}
			for (String removedId : removed) {
				recipes.remove(removedId);
				if (document.has("machine_sources")) {
					document.getAsJsonObject("machine_sources").remove(removedId);
				}
				JsonArray disabled = document.has("disabled_recipes") ? document.getAsJsonArray("disabled_recipes") : new JsonArray();
				JsonPrimitive value = new JsonPrimitive(removedId);
				if (!disabled.contains(value)) {
					disabled.add(value);
				}
				document.add("disabled_recipes", disabled);
			}
			replaced += removed.size();
			BeeDesignerMachineRecipes.recipeIdentity(recipe, nextId);
			recipes.add(nextId, recipe);
			if (recipes.size() > 64) {
				throw new IllegalArgumentException("designer.error.machine_entries");
			}
			if (source.has("machine_sources") && source.getAsJsonObject("machine_sources").has(recipeId)) {
				JsonObject metadata = source.getAsJsonObject("machine_sources").getAsJsonObject(recipeId).deepCopy();
				metadata.addProperty("copied_from_recipe", recipeId);
				BeeDesignerDocument.object(document, "machine_sources").add(nextId, metadata);
			}
			BeeDesignerCompiler.validateStructure(document);
			if (!BeeDesignerReader.belongs(recipe, id)) {
				throw new IllegalArgumentException("designer.error.recipe_template");
			}
			String snapshot = document.toString();
			bytes += snapshot.getBytes(StandardCharsets.UTF_8).length;
			if (bytes > BeeDesignerStore.MAX_PACK_BYTES) {
				throw new IllegalArgumentException("designer.error.collection_bytes");
			}
			documents.put(id, snapshot);
			identities.put(id, id);
		}
		return new Plan(new BeeDesignerMigration.Plan(documents, identities, List.of()), replaced);
	}

	private static boolean sameRoute(JsonObject existing, JsonObject template) {
		return BeeDesignerDocument.text(existing, "type", "").equals(BeeDesignerDocument.text(template, "type", ""))
				&& BeeDesignerDocument.text(existing, "machine", "").equals(BeeDesignerDocument.text(template, "machine", ""));
	}

	private static void retarget(JsonObject recipe, String previous, String next) {
		BeeDesignerDocument.rewriteSelfConditions(recipe, previous, next);
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			BeeDesignerMachineRecipes.retargetOutputs(recipe, previous, next);
			return;
		}
		switch (recipe.get("type").getAsString()) {
			case "minecraft:crafting_shaped", "minecraft:crafting_shapeless", "productivebees:bottler" ->
					recipe.add("result", BeeDesignerEggs.retarget(recipe.getAsJsonObject("result"), next));
			case "productivebees:bee_breeding" -> recipe.addProperty("offspring", next);
			case "productivebees:bee_conversion" -> recipe.addProperty("result", next);
			case "productivebees:bee_fishing" -> recipe.addProperty("bee", next);
			case "productivebees:bee_spawning" -> {
				JsonArray results = recipe.getAsJsonArray("results");
				for (int index = 0; index < results.size(); index++) {
					if (results.get(index).isJsonPrimitive() && previous.equals(results.get(index).getAsString())) {
						results.set(index, new JsonPrimitive(next));
					}
				}
			}
			default -> throw new IllegalArgumentException("designer.error.recipe_template");
		}
	}
}
