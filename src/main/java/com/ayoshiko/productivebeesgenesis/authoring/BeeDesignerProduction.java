package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/** 一个生产节点可组合物品、流体和多种扩展资源；生成既有执行器需要的配套配方。 */
public final class BeeDesignerProduction {
	private BeeDesignerProduction() {
	}

	public static void replace(JsonObject document, String entryId, boolean centrifuge, JsonArray items,
			JsonObject fluid, JsonArray resources, int processingTime) {
		if (!entryId.matches("[a-z0-9_.-]{1,64}") || items.size() > 16 || resources.size() > 16
				|| processingTime < 1 || items.size() + resources.size() == 0 && fluid == null) {
			throw new IllegalArgumentException("Invalid production node");
		}
		if (!centrifuge && fluid != null) {
			throw new IllegalArgumentException("Direct PB hive recipes do not support fluid output");
		}
		ResourceLocation bee = new ResourceLocation(document.get("id").getAsString());
		String base = bee.getNamespace() + ":bee_designer/" + bee.getPath() + "/production/" + entryId;
		JsonObject recipes = BeeDesignerDocument.object(document, "recipes");
		JsonObject nodes = BeeDesignerDocument.object(document, "production_nodes");
		JsonObject original = nodes.has(entryId) ? nodes.getAsJsonObject(entryId) : null;
		JsonArray owned = original == null ? new JsonArray() : original.getAsJsonArray("recipe_ids");
		JsonObject generated = new JsonObject();
		boolean ordinary = !items.isEmpty() || fluid != null;
		if (centrifuge) {
			JsonObject comb = BeeDesignerDocument.recipe("productivebees:advanced_beehive");
			comb.addProperty("ingredient", bee.toString());
			JsonArray result = new JsonArray();
			result.add(BeeDesignerDocument.itemOutput(BeeDesignerDocument.comb(bee.toString())));
			comb.add("results", result);
			generated.add(base + "/comb", comb);
		}
		if (ordinary) {
			JsonObject recipe = BeeDesignerDocument.recipe(centrifuge ? "productivebees:centrifuge" : "productivebees:advanced_beehive");
			if (centrifuge) {
				recipe.add("ingredient", BeeDesignerDocument.comb(bee.toString()));
				recipe.addProperty("processingTime", processingTime);
			} else {
				recipe.addProperty("ingredient", bee.toString());
			}
			recipe.add(centrifuge ? "outputs" : "results", items.deepCopy());
			if (fluid != null) {
				recipe.add("fluid", fluid.deepCopy());
			}
			generated.add(base + "/items", recipe);
		}
		if (!resources.isEmpty()) {
			JsonObject recipe = BeeDesignerDocument.recipe(centrifuge
					? "productivebeesgenesis:resource_centrifuging" : "productivebeesgenesis:resource_production");
			recipe.addProperty("bee", bee.toString());
			recipe.add("outputs", resources.deepCopy());
			if (centrifuge) {
				recipe.addProperty("processing_time", processingTime);
				recipe.addProperty("include_pb_outputs", ordinary);
			}
			generated.add(base + "/resources", recipe);
		}
		for (String id : generated.keySet()) {
			if (recipes.has(id) && !owned.asList().stream().anyMatch(value -> id.equals(value.getAsString()))) {
				throw new IllegalArgumentException("Recipe is already owned by other content: " + id);
			}
		}
		JsonArray retired = BeeDesignerDocument.array(document, "disabled_recipes");
		for (var value : owned) {
			recipes.remove(value.getAsString());
			if (!generated.has(value.getAsString()) && !retired.contains(value)) {
				retired.add(value.deepCopy());
			}
		}
		JsonArray ids = new JsonArray();
		generated.entrySet().forEach(entry -> {
			recipes.add(entry.getKey(), entry.getValue());
			ids.add(entry.getKey());
		});
		JsonObject node = new JsonObject();
		node.addProperty("route", centrifuge ? "centrifuge" : "direct");
		node.add("recipe_ids", ids);
		nodes.add(entryId, node);
	}

	public static void remove(JsonObject document, String entryId) {
		JsonObject nodes = BeeDesignerDocument.object(document, "production_nodes");
		if (!nodes.has(entryId)) {
			throw new IllegalArgumentException("Unknown production node: " + entryId);
		}
		JsonObject recipes = BeeDesignerDocument.object(document, "recipes");
		JsonArray retired = BeeDesignerDocument.array(document, "disabled_recipes");
		for (var value : nodes.getAsJsonObject(entryId).getAsJsonArray("recipe_ids")) {
			recipes.remove(value.getAsString());
			if (!retired.contains(value)) {
				retired.add(value.deepCopy());
			}
		}
		nodes.remove(entryId);
	}
}
