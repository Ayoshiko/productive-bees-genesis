package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保留配方语义与完整材料，只有已核对的 KubeJS 字段使用普通合成简写。 */
public final class BeeDesignerCraftingExport {
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final Set<String> FIELDS = Set.of("type", "result", "pattern", "key", "ingredients", "group",
			"category", "show_notification", "productivebeesgenesis:designer_recipe");
	private static final List<String> METADATA = List.of("group", "category", "show_notification", "productivebeesgenesis:designer_recipe");

	private BeeDesignerCraftingExport() {
	}

	public static JsonObject compact(JsonObject source) {
		JsonObject recipe = source.deepCopy();
		if (!BeeDesignerDocument.text(recipe, "type", "").equals("minecraft:crafting_shaped")
				|| !recipe.has("pattern") || !recipe.has("key")) {
			return recipe;
		}
		JsonObject originalKeys = recipe.getAsJsonObject("key");
		Map<JsonElement, String> symbols = new LinkedHashMap<>();
		JsonObject keys = new JsonObject();
		JsonArray pattern = new JsonArray();
		for (JsonElement row : recipe.getAsJsonArray("pattern")) {
			StringBuilder line = new StringBuilder();
			for (char character : row.getAsString().toCharArray()) {
				if (character == ' ') {
					line.append(' ');
				} else {
					JsonElement ingredient = originalKeys.get(Character.toString(character));
					String symbol = symbols.computeIfAbsent(ingredient, unused -> Character.toString('A' + symbols.size()));
					line.append(symbol);
					keys.add(symbol, ingredient.deepCopy());
				}
			}
			pattern.add(line.toString());
		}
		recipe.add("pattern", pattern);
		recipe.add("key", keys);
		return recipe;
	}

	static String script(JsonObject source, String id) {
		JsonObject recipe = compact(source);
		String reason = customReason(recipe);
		if (!reason.isEmpty()) {
			return "  // " + reason + "\n  event.custom(" + json(recipe) + ").id(" + quote(id) + ");\n";
		}
		JsonObject result = recipe.getAsJsonObject("result");
		String output;
		if (result.has("components")) {
			output = "Item.of(" + json(result) + ")";
		} else {
			String item = BeeDesignerDocument.text(result, "id", BeeDesignerDocument.text(result, "item", ""));
			int count = result.has("count") ? result.get("count").getAsInt() : 1;
			output = "Item.of(" + quote(item) + ", " + count + ")";
		}
		StringBuilder script = new StringBuilder("  event.");
		if (recipe.get("type").getAsString().equals("minecraft:crafting_shaped")) {
			script.append("shaped(\n    ").append(output).append(",\n    ").append(PRETTY.toJson(recipe.get("pattern")));
			script.append(",\n    {\n");
			int index = 0;
			for (var key : recipe.getAsJsonObject("key").entrySet()) {
				if (index++ > 0) {
					script.append(",\n");
				}
				script.append("      ").append(quote(key.getKey())).append(": ").append(ingredient(key.getValue()));
			}
			script.append("\n    }\n  )");
		} else {
			script.append("shapeless(\n    ").append(output).append(",\n    [\n");
			int index = 0;
			for (JsonElement value : recipe.getAsJsonArray("ingredients")) {
				if (index++ > 0) {
					script.append(",\n");
				}
				script.append("      ").append(ingredient(value));
			}
			script.append("\n    ]\n  )");
		}
		JsonObject metadata = new JsonObject();
		for (String field : METADATA) {
			if (recipe.has(field)) {
				metadata.add(field, recipe.get(field).deepCopy());
			}
		}
		if (!metadata.isEmpty()) {
			script.append(".merge(").append(json(metadata)).append(")");
		}
		return script.append(".id(").append(quote(id)).append(");\n").toString();
	}

	private static String customReason(JsonObject recipe) {
		String type = BeeDesignerDocument.text(recipe, "type", "");
		if (!type.equals("minecraft:crafting_shaped") && !type.equals("minecraft:crafting_shapeless")) {
			return "Full JSON preserves this recipe serializer and its fields.";
		}
		if (!FIELDS.containsAll(recipe.keySet())) {
			return "Full JSON preserves recipe conditions or additional fields.";
		}
		JsonObject result = recipe.getAsJsonObject("result");
		if (!Set.of("id", "item", "count", "components").containsAll(result.keySet())) {
			return "Full JSON preserves additional result data.";
		}
		Iterable<JsonElement> inputs = type.equals("minecraft:crafting_shaped")
				? recipe.getAsJsonObject("key").asMap().values() : recipe.getAsJsonArray("ingredients");
		for (JsonElement input : inputs) {
			if (ingredient(input) == null) {
				return "Full JSON preserves exact component or alternative ingredients.";
			}
		}
		return "";
	}

	private static String ingredient(JsonElement value) {
		if (value.isJsonObject() && value.getAsJsonObject().size() == 1) {
			JsonObject ingredient = value.getAsJsonObject();
			if (ingredient.has("item") && ingredient.get("item").isJsonPrimitive()) {
				return quote(ingredient.get("item").getAsString());
			}
			if (ingredient.has("tag") && ingredient.get("tag").isJsonPrimitive()) {
				return quote("#" + ingredient.get("tag").getAsString());
			}
		}
		return null;
	}

	private static String quote(String value) {
		return new JsonPrimitive(value).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
	}

	private static String json(JsonElement value) {
		String delimiter = Character.toString(96);
		String text = PRETTY.toJson(value).replace("\\", "\\\\").replace(delimiter, "\\" + delimiter)
				.replace("$" + "{", "\\$" + "{");
		return "JSON.parse(" + delimiter + text + delimiter + ")";
	}
}
