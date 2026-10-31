package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** 表单直接修改原始对象的已知字段，未展示字段与配方 ID 一并保管。 */
public final class BeeDesignerDocument {
	private BeeDesignerDocument() {
	}

	public static String translationKey(String id) {
		// PB ProductiveBee#getBeeName 移除路径中的 _bee 后再由蜂蛋追加后缀。
		return "entity.productivebees." + ResourceLocation.parse(id).getPath().replace("_bee", "") + "_bee";
	}

	public static JsonObject create(String id) {
		JsonObject document = new JsonObject();
		document.addProperty("schema_version", 1);
		document.addProperty("id", id);
		document.addProperty("definition_path", id);
		document.addProperty("baseline", "new");
		JsonObject origin = new JsonObject();
		origin.addProperty("kind", "editor");
		origin.addProperty("source_pack", "");
		document.add("_editor_origin", origin);
		JsonObject definition = new JsonObject();
		definition.addProperty("primaryColor", "#E8BC39");
		definition.addProperty("secondaryColor", "#383D43");
		definition.addProperty("flowerItem", "minecraft:poppy");
		definition.addProperty("breedingItem", "minecraft:poppy");
		definition.addProperty("selfbreed", true);
		definition.addProperty("createComb", true);
		document.add("definition", definition);
		document.add("names", new JsonObject());
		document.add("recipes", new JsonObject());
		return document;
	}

	public static String text(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	public static JsonObject object(JsonObject json, String key) {
		if (!json.has(key)) {
			json.add(key, new JsonObject());
		}
		return json.getAsJsonObject(key);
	}

	public static JsonArray array(JsonObject json, String key) {
		if (!json.has(key)) {
			json.add(key, new JsonArray());
		}
		return json.getAsJsonArray(key);
	}

	public static String fingerprint(JsonObject document) {
		JsonObject data = document.deepCopy();
		data.remove("baseline");
		data.remove("_editor_recovery");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical(data).toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static JsonElement canonical(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject sorted = new JsonObject();
			value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
					.forEach(entry -> sorted.add(entry.getKey(), canonical(entry.getValue())));
			return sorted;
		}
		if (value.isJsonArray()) {
			JsonArray array = new JsonArray();
			value.getAsJsonArray().forEach(entry -> array.add(canonical(entry)));
			return array;
		}
		return value.deepCopy();
	}

	public static JsonObject copyAs(JsonObject source, String newId) {
		ResourceLocation id = ResourceLocation.parse(newId);
		String previous = source.get("id").getAsString();
		if (newId.equals(previous) || !id.getPath().matches("[a-z0-9_.-]{1,96}")) {
			throw new IllegalArgumentException("A copy requires a different, single-segment bee ID");
		}
		JsonObject copy = source.deepCopy();
		copy.addProperty("id", newId);
		copy.addProperty("definition_path", newId);
		copy.addProperty("baseline", "new");
		copy.remove("disabled_recipes");
		copy.remove("_editor_recovery");
		copy.remove(BeeDesignerLifecycle.FIELD);
		copy.remove(BeeDesignerMyriad.RECIPES_ONLY);
		JsonObject origin = new JsonObject();
		origin.addProperty("kind", "editor");
		origin.addProperty("source_pack", "");
		origin.addProperty("copied_from", previous);
		if (source.has("_editor_origin") && source.get("_editor_origin").isJsonObject()) {
			JsonObject original = source.getAsJsonObject("_editor_origin");
			String sourcePack = text(original, "copied_source_pack", text(original, "source_pack", ""));
			if (!sourcePack.isBlank()) {
				origin.addProperty("copied_source_pack", sourcePack);
			}
		}
		copy.add("_editor_origin", origin);
		JsonObject recipes = new JsonObject();
		rewriteSelfConditions(copy.getAsJsonObject("definition"), previous, newId);
		Map<String, String> ids = new LinkedHashMap<>();
		int index = 0;
		for (var entry : source.getAsJsonObject("recipes").entrySet()) {
			JsonObject recipe = entry.getValue().getAsJsonObject().deepCopy();
			recipe.remove(BeeDesignerMyriad.CUSTOM_RECIPE);
			String type = recipe.get("type").getAsString();
			rewriteSelfConditions(recipe, previous, newId);
			if (BeeDesignerWorldConversions.supports(type)) {
				BeeDesignerWorldConversions.retarget(recipe, newId);
			}
			String field = switch (type) {
				case "productivebees:advanced_beehive" -> "ingredient";
				case "productivebees:bee_breeding" -> "offspring";
				case "productivebees:bee_conversion" -> "result";
				case "productivebees:bee_fishing", "productivebeesgenesis:resource_production",
						"productivebeesgenesis:resource_centrifuging" -> "bee";
				default -> "";
			};
			if (!field.isEmpty() && recipe.has(field) && previous.equals(text(recipe, field, ""))) {
				recipe.addProperty(field, newId);
			}
			if (type.equals("productivebees:bee_spawning")) {
				JsonArray results = recipe.getAsJsonArray("results");
				for (int position = 0; position < results.size(); position++) {
					if (results.get(position).isJsonPrimitive() && previous.equals(results.get(position).getAsString())) {
						results.set(position, new com.google.gson.JsonPrimitive(newId));
					}
				}
			}
			if (BeeDesignerMachineRecipes.host(recipe) == null) {
				rewriteIdentityComponents(recipe, previous, newId);
			} else {
				BeeDesignerMachineRecipes.retargetOutputs(recipe, previous, newId);
			}
			String base = id.getNamespace() + ":bee_designer/" + id.getPath();
			String oldBase = ResourceLocation.parse(previous).getNamespace() + ":bee_designer/" + ResourceLocation.parse(previous).getPath();
			String next = entry.getKey().startsWith(oldBase + "/production/")
					? base + entry.getKey().substring(oldBase.length()) : base + "/imported/" + ++index;
			String candidate = next;
			int suffix = 2;
			while (recipes.has(next)) {
				next = candidate + "_" + suffix++;
			}
			ids.put(entry.getKey(), next);
			if (BeeDesignerMachineRecipes.host(recipe) == BeeDesignerMachineRecipes.Host.MBD2) {
				recipe.addProperty("id", next);
			}
			recipes.add(next, recipe);
		}
		copy.add("recipes", recipes);
		if (copy.has("machine_sources")) {
			JsonObject sources = new JsonObject();
			for (var entry : copy.getAsJsonObject("machine_sources").entrySet()) {
				if (ids.containsKey(entry.getKey())) {
					JsonObject sourceInfo = entry.getValue().getAsJsonObject().deepCopy();
					sourceInfo.addProperty("copied_from_recipe", entry.getKey());
					sources.add(ids.get(entry.getKey()), sourceInfo);
				}
			}
			copy.add("machine_sources", sources);
		}
		if (copy.has("production_nodes")) {
			copy.getAsJsonObject("production_nodes").entrySet().forEach(entry -> {
				JsonArray mapped = new JsonArray();
				entry.getValue().getAsJsonObject().getAsJsonArray("recipe_ids").forEach(value -> {
					if (!ids.containsKey(value.getAsString())) {
						throw new IllegalArgumentException("Production node refers to a missing recipe");
					}
					mapped.add(ids.get(value.getAsString()));
				});
				entry.getValue().getAsJsonObject().add("recipe_ids", mapped);
			});
		}
		BeeDesignerLegacyOutputs.rebase(source, copy, ids);
		return copy;
	}

	public static String nextCopyId(JsonObject source, Collection<String> bees, Collection<String> recipeIds) {
		ResourceLocation previous = ResourceLocation.parse(source.get("id").getAsString());
		String path = previous.getPath();
		String base = previous.getNamespace() + ":" + path.substring(0, Math.min(80, path.length())) + "_copy";
		Set<String> names = new HashSet<>();
		bees.forEach(id -> names.add(translationKey(id)));
		Set<String> occupied = new HashSet<>();
		String recipePrefix = previous.getNamespace() + ":bee_designer/";
		for (String recipe : recipeIds) {
			if (recipe.startsWith(recipePrefix)) {
				String relative = recipe.substring(recipePrefix.length());
				int slash = relative.indexOf('/');
				if (slash > 0) {
					occupied.add(previous.getNamespace() + ":" + relative.substring(0, slash));
				}
			}
		}
		for (int suffix = 1; suffix <= 10_000; suffix++) {
			String candidate = suffix == 1 ? base : base + "_" + suffix;
			if (names.contains(translationKey(candidate)) || occupied.contains(candidate)) {
				continue;
			}
			return candidate;
		}
		throw new IllegalArgumentException("designer.error.copy_limit");
	}

	private static void rewriteSelfConditions(JsonElement value, String previous, String next) {
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(entry -> rewriteSelfConditions(entry, previous, next));
		} else if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			if (text(object, "type", "").equals("productivebees:bee_exists") && text(object, "bee", "").equals(previous)) {
				object.addProperty("bee", next);
			}
			object.asMap().values().forEach(entry -> rewriteSelfConditions(entry, previous, next));
		}
	}

	private static void rewriteIdentityComponents(JsonElement value, String previous, String next) {
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(entry -> rewriteIdentityComponents(entry, previous, next));
		} else if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			if (object.has("productivebees:bee_type") && previous.equals(text(object, "productivebees:bee_type", ""))) {
				object.addProperty("productivebees:bee_type", next);
			}
			if (object.has("minecraft:entity_data") && object.get("minecraft:entity_data").isJsonObject()) {
				JsonObject entity = object.getAsJsonObject("minecraft:entity_data");
				if (previous.equals(text(entity, "type", ""))) {
					entity.addProperty("type", next);
				}
			}
			object.asMap().values().forEach(entry -> rewriteIdentityComponents(entry, previous, next));
		}
	}

	public static JsonObject recipe(String type) {
		JsonObject recipe = new JsonObject();
		recipe.addProperty("type", type);
		return recipe;
	}

	public static JsonObject comb(String bee) {
		JsonObject stack = new JsonObject();
		stack.addProperty("type", "productivebees:component");
		stack.addProperty("items", "productivebees:configurable_honeycomb");
		JsonObject components = new JsonObject();
		components.addProperty("productivebees:bee_type", bee);
		stack.add("components", components);
		return stack;
	}

	public static JsonObject itemOutput(JsonObject stack) {
		JsonObject output = new JsonObject();
		output.add("item", stack);
		output.addProperty("chance", 1);
		output.addProperty("min", 1);
		output.addProperty("max", 1);
		return output;
	}

	public static String addRecipe(JsonObject document, String type) {
		String bee = document.get("id").getAsString();
		ResourceLocation id = ResourceLocation.parse(bee);
		JsonObject recipes = object(document, "recipes");
		String base = id.getNamespace() + ":bee_designer/" + id.getPath() + "/";
		int next = 1;
		while (recipes.has(base + next)) {
			next++;
		}
		JsonObject recipe = recipe(type);
		switch (type) {
			case "productivebees:item_conversion", "productivebees:block_conversion" -> BeeDesignerWorldConversions.initialize(recipe, bee);
			case "productivebees:bottler" -> {
				JsonObject ingredient = new JsonObject();
				ingredient.addProperty("item", "minecraft:honey_bottle");
				recipe.add("ingredient", ingredient);
				JsonObject fluid = new JsonObject();
				fluid.addProperty("fluid", "minecraft:water");
				fluid.addProperty("amount", 250);
				recipe.add("fluid", fluid);
				recipe.add("result", BeeDesignerEggs.output(bee));
			}
			case "productivebees:advanced_beehive" -> {
				recipe.addProperty("ingredient", bee);
				JsonArray outputs = new JsonArray();
				outputs.add(itemOutput(comb(bee)));
				recipe.add("results", outputs);
			}
			case "productivebees:centrifuge" -> {
				JsonObject input = comb(bee);
				recipe.add("ingredient", input);
				recipe.addProperty("processingTime", 200);
				recipe.add("outputs", new JsonArray());
			}
			case "productivebeesgenesis:resource_production", "productivebeesgenesis:resource_centrifuging" -> {
				recipe.addProperty("bee", bee);
				JsonArray outputs = new JsonArray();
				JsonObject output = new JsonObject();
				output.addProperty("kind", "energy");
				output.addProperty("energy_type", "FE");
				output.addProperty("amount", "1000");
				output.addProperty("chance", 1);
				outputs.add(output);
				recipe.add("outputs", outputs);
				if (type.endsWith("resource_centrifuging")) {
					recipe.addProperty("processing_time", 200);
				}
			}
			case "productivebees:bee_breeding" -> {
				recipe.addProperty("parent1", "minecraft:bee");
				recipe.addProperty("parent2", "minecraft:bee");
				recipe.addProperty("offspring", bee);
			}
			case "productivebees:bee_conversion" -> {
				recipe.addProperty("source", "minecraft:bee");
				recipe.addProperty("result", bee);
				JsonObject ingredient = new JsonObject();
				ingredient.addProperty("item", "minecraft:honey_bottle");
				recipe.add("item", ingredient);
				recipe.addProperty("chance", 1);
			}
			case "minecraft:crafting_shapeless", "minecraft:crafting_shaped" -> {
				JsonArray ingredients = new JsonArray();
				JsonObject ingredient = new JsonObject();
				ingredient.addProperty("item", "minecraft:honey_bottle");
				ingredients.add(ingredient);
				if (type.endsWith("shaped")) {
					JsonObject keys = new JsonObject();
					keys.add("A", ingredient);
					recipe.add("key", keys);
					JsonArray pattern = new JsonArray();
					pattern.add("A");
					recipe.add("pattern", pattern);
				} else {
					recipe.add("ingredients", ingredients);
				}
				recipe.add("result", BeeDesignerEggs.output(bee));
			}
			case "productivebees:bee_spawning" -> {
				JsonObject ingredient = new JsonObject();
				ingredient.addProperty("item", "productivebees:gravel_nest");
				recipe.add("ingredient", ingredient);
				recipe.addProperty("biomes", "#productivebees:is_beach_or_river");
				JsonArray results = new JsonArray();
				results.add(bee);
				recipe.add("results", results);
			}
			case "productivebees:bee_fishing" -> {
				recipe.addProperty("bee", bee);
				recipe.addProperty("chance", 0.05);
				JsonArray biomes = new JsonArray();
				biomes.add("minecraft:river");
				recipe.add("biomes", biomes);
			}
			default -> throw new IllegalArgumentException("Unsupported recipe template: " + type);
		}
		recipes.add(base + next, recipe);
		return base + next;
	}

	public static boolean containsBee(JsonElement json, String bee) {
		if (json.isJsonPrimitive()) {
			return json.getAsJsonPrimitive().isString() && bee.equals(json.getAsString());
		}
		if (json.isJsonArray()) {
			for (JsonElement entry : json.getAsJsonArray()) {
				if (containsBee(entry, bee)) {
					return true;
				}
			}
		} else if (json.isJsonObject()) {
			for (JsonElement entry : json.getAsJsonObject().asMap().values()) {
				if (containsBee(entry, bee)) {
					return true;
				}
			}
		}
		return false;
	}
}
