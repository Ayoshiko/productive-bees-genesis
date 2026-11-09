package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;

import java.util.Map;

/** 已加载 PB 旧产物按原声明保留；编辑后重新校验，不改写概率或数量语义。 */
final class BeeDesignerLegacyOutputs {
	private static final String FIELD = "_editor_preserved_output_recipes";

	private BeeDesignerLegacyOutputs() {
	}

	static void capture(JsonObject document) {
		if (document.has(FIELD)) {
			return;
		}
		JsonObject preserved = new JsonObject();
		for (var entry : document.getAsJsonObject("recipes").entrySet()) {
			JsonObject recipe = entry.getValue().getAsJsonObject();
			if (hasLegacyOutput(recipe)) {
				preserved.addProperty(entry.getKey(), BeeDesignerMachineScripts.hash(recipe.toString()));
			}
		}
		if (!preserved.isEmpty()) {
			document.add(FIELD, preserved);
		}
	}

	static boolean unchanged(JsonObject document, String id, JsonObject recipe) {
		if (!document.has(FIELD) || !document.get(FIELD).isJsonObject()) {
			return false;
		}
		JsonObject preserved = document.getAsJsonObject(FIELD);
		return preserved.has(id) && preserved.get(id).isJsonPrimitive()
				&& preserved.getAsJsonPrimitive(id).isString()
				&& preserved.get(id).getAsString().equals(BeeDesignerMachineScripts.hash(recipe.toString()));
	}

	static void rebase(JsonObject original, JsonObject copy, Map<String, String> recipeIds) {
		JsonObject preserved = new JsonObject();
		for (var entry : recipeIds.entrySet()) {
			JsonObject source = original.getAsJsonObject("recipes").getAsJsonObject(entry.getKey());
			if (source != null && unchanged(original, entry.getKey(), source)) {
				JsonObject recipe = copy.getAsJsonObject("recipes").getAsJsonObject(entry.getValue());
				if (recipe != null && hasLegacyOutput(recipe)) {
					preserved.addProperty(entry.getValue(), BeeDesignerMachineScripts.hash(recipe.toString()));
				}
			}
		}
		copy.remove(FIELD);
		if (!preserved.isEmpty()) {
			copy.add(FIELD, preserved);
		}
	}

	private static boolean hasLegacyOutput(JsonObject recipe) {
		String type = BeeDesignerDocument.text(recipe, "type", "");
		String field;
		if (type.equals("productivebees:advanced_beehive")) {
			field = "results";
		} else if (type.equals("productivebees:centrifuge")) {
			field = "outputs";
		} else {
			return false;
		}
		if (!recipe.has(field) || !recipe.get(field).isJsonArray()) {
			return false;
		}
		for (var output : recipe.getAsJsonArray(field)) {
			if (output.isJsonObject()) {
				JsonObject item = output.getAsJsonObject();
				var min = item.get("min");
				var max = item.get("max");
				if (min != null && max != null && min.isJsonPrimitive() && max.isJsonPrimitive()
						&& min.getAsJsonPrimitive().isNumber() && max.getAsJsonPrimitive().isNumber()
						&& min.getAsDouble() >= 1 && max.getAsDouble() >= 1 && max.getAsDouble() < min.getAsDouble()) {
					return true;
				}
				var chance = item.get("chance");
				if (chance != null && chance.isJsonPrimitive() && chance.getAsJsonPrimitive().isNumber()) {
					double value = chance.getAsDouble();
					if (Double.isFinite(value) && value > 1 && Float.isFinite((float) value)) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
