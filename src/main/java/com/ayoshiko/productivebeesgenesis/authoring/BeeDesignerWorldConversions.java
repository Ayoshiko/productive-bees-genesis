package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

/** PB 环境转化保留原生序列化与执行；多蜂共享声明不能由单蜂编辑静默覆盖。 */
public final class BeeDesignerWorldConversions {
	private BeeDesignerWorldConversions() {
	}

	public static boolean supports(String type) {
		return type.equals("productivebees:item_conversion") || type.equals("productivebees:block_conversion");
	}

	public static Set<String> owners(JsonObject recipe) {
		if (!supports(BeeDesignerDocument.text(recipe, "type", "")) || !recipe.has("bees") || !recipe.get("bees").isJsonArray()) {
			return Set.of();
		}
		Set<String> result = new LinkedHashSet<>();
		for (var bee : recipe.getAsJsonArray("bees")) {
			if (bee.isJsonPrimitive() && bee.getAsJsonPrimitive().isString()) {
				result.add(bee.getAsString());
			}
		}
		return Set.copyOf(result);
	}

	public static boolean shared(JsonObject recipe) {
		return owners(recipe).size() > 1;
	}

	static void retarget(JsonObject recipe, String bee) {
		JsonArray owners = new JsonArray();
		owners.add(bee);
		recipe.add("bees", owners);
	}

	static void initialize(JsonObject recipe, String bee) {
		retarget(recipe, bee);
		recipe.addProperty("chance", 1);
		recipe.addProperty("pollinates", false);
		if (recipe.get("type").getAsString().equals("productivebees:item_conversion")) {
			JsonObject ingredient = new JsonObject();
			ingredient.addProperty("item", "minecraft:raw_copper");
			recipe.add("ingredients", ingredient);
			JsonObject result = new JsonObject();
			result.addProperty("id", "minecraft:copper_ingot");
			result.addProperty("count", 1);
			recipe.add("result", result);
		} else {
			JsonObject from = new JsonObject();
			from.addProperty("Name", "minecraft:copper_block");
			JsonObject to = new JsonObject();
			to.addProperty("Name", "minecraft:exposed_copper");
			recipe.add("from", from);
			recipe.add("to", to);
		}
	}

	static void validate(JsonObject recipe, String bee) {
		Set<String> owners = owners(recipe);
		if (!owners.contains(bee) || owners.size() > 128 || owners.size() != recipe.getAsJsonArray("bees").size()) {
			throw new IllegalArgumentException("designer.error.conversion_bees");
		}
		owners.forEach(ResourceLocation::parse);
		if (recipe.has("pollinates") && (!recipe.get("pollinates").isJsonPrimitive() || !recipe.getAsJsonPrimitive("pollinates").isBoolean())) {
			throw new IllegalArgumentException("designer.error.field_boolean|pollinates");
		}
		if (recipe.get("type").getAsString().equals("productivebees:block_conversion")) {
			if (recipe.has("from")) {
				validateState(recipe.getAsJsonObject("from"));
			}
			validateState(recipe.getAsJsonObject("to"));
		}
	}

	private static void validateState(JsonObject state) {
		if (state == null || !state.has("Name")) {
			throw new IllegalArgumentException("designer.error.conversion_state");
		}
		ResourceLocation id = new ResourceLocation(state.get("Name").getAsString());
		if (!BuiltInRegistries.BLOCK.containsKey(id)) {
			throw new IllegalArgumentException("designer.error.conversion_state");
		}
		var block = BuiltInRegistries.BLOCK.get(id);
		if (state.has("Properties")) {
			for (var entry : state.getAsJsonObject("Properties").entrySet()) {
				var property = block.getStateDefinition().getProperty(entry.getKey());
				if (property == null || property.getValue(entry.getValue().getAsString()).isEmpty()) {
					throw new IllegalArgumentException("designer.error.conversion_state");
				}
			}
		}
	}

	static void validateChanges(JsonObject original, JsonObject document) {
		JsonObject before = original == null ? new JsonObject() : original.getAsJsonObject("recipes");
		JsonObject after = document.getAsJsonObject("recipes");
		for (var entry : before.entrySet()) {
			if (shared(entry.getValue().getAsJsonObject()) && !entry.getValue().equals(after.get(entry.getKey()))) {
				throw new IllegalArgumentException("designer.error.shared_conversion|" + entry.getKey());
			}
		}
		for (var entry : after.entrySet()) {
			if (shared(entry.getValue().getAsJsonObject()) && !entry.getValue().equals(before.get(entry.getKey()))) {
				throw new IllegalArgumentException("designer.error.shared_conversion|" + entry.getKey());
			}
		}
	}

	static JsonObject active(JsonObject recipe, Set<String> retired) {
		JsonArray remaining = new JsonArray();
		for (var bee : recipe.getAsJsonArray("bees")) {
			if (!retired.contains(bee.getAsString())) {
				remaining.add(bee.deepCopy());
			}
		}
		if (remaining.isEmpty()) {
			return null;
		}
		JsonObject active = recipe.deepCopy();
		active.add("bees", remaining);
		return active;
	}
}
