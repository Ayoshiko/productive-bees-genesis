package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 万象属性使用唯一的服务端配置所有者，不生成会被配置再次覆盖的蜂种文件。 */
public final class BeeDesignerMyriad {
	private static final Gson GSON = new Gson();
	public static final String RECIPES_ONLY = "myriad_recipes_only";
	public static final String CUSTOM_RECIPE = "productivebeesgenesis:myriad_recipe";

	private BeeDesignerMyriad() {
	}

	public static boolean recipesOnly(JsonObject document) {
		return PBConstants.MYRIADCREATIONS_TYPE_STRING.equals(BeeDesignerDocument.text(document, "id", ""))
				&& document.has(RECIPES_ONLY) && document.get(RECIPES_ONLY).isJsonPrimitive()
				&& document.getAsJsonPrimitive(RECIPES_ONLY).isBoolean() && document.get(RECIPES_ONLY).getAsBoolean();
	}

	static void prepareRecipes(JsonObject document) {
		document.addProperty(RECIPES_ONLY, true);
		JsonObject recipes = document.getAsJsonObject("recipes");
		recipes.entrySet().removeIf(entry -> !customRecipe(entry.getValue().getAsJsonObject())
				&& !additionalType(entry.getValue().getAsJsonObject()));
		document.addProperty("baseline", BeeDesignerDocument.fingerprint(document));
	}

	public static boolean customRecipe(JsonObject recipe) {
		var marker = recipe.get(CUSTOM_RECIPE);
		return marker != null && marker.isJsonPrimitive() && marker.getAsJsonPrimitive().isBoolean() && marker.getAsBoolean();
	}

	static boolean additionalType(JsonObject recipe) {
		String type = BeeDesignerDocument.text(recipe, "type", "");
		return BeeDesignerMachineRecipes.host(recipe) != null || type.startsWith("minecraft:crafting_") || type.equals("productivebees:bottler");
	}

	static void validateRecipes(JsonObject original, JsonObject document) {
		if (!recipesOnly(original) || !recipesOnly(document) || !original.get("definition").equals(document.get("definition"))
				|| !original.get("names").equals(document.get("names")) || document.has("production_nodes")) {
			throw new IllegalArgumentException("designer.error.myriad_recipe_scope");
		}
		for (var recipe : document.getAsJsonObject("recipes").entrySet()) {
			String type = BeeDesignerDocument.text(recipe.getValue().getAsJsonObject(), "type", "");
			if (!additionalType(recipe.getValue().getAsJsonObject()) && !java.util.Set.of("productivebees:bee_breeding",
					"productivebees:bee_conversion", "productivebees:bee_spawning", "productivebees:bee_fishing").contains(type)) {
				throw new IllegalArgumentException("designer.error.myriad_recipe_scope");
			}
			recipe.getValue().getAsJsonObject().addProperty(CUSTOM_RECIPE, true);
		}
	}

	private static Map<String, ModConfigSpec.ConfigValue<?>> fields() {
		var config = ModConfig.SERVER;
		Map<String, ModConfigSpec.ConfigValue<?>> values = new LinkedHashMap<>();
		values.put("primaryColor", config.primaryColor);
		values.put("secondaryColor", config.secondaryColor);
		values.put("particleColor", config.particleColor);
		values.put("glowColor", config.glowColor);
		values.put("flowerItem", config.flowerItem);
		values.put("weatherTolerance", config.weatherTolerance);
		values.put("temper", config.temper);
		values.put("behavior", config.behavior);
		values.put("endurance", config.endurance);
		values.put("productivity", config.productivity);
		values.put("createComb", config.createComb);
		values.put("size", config.size);
		values.put("speed", config.speed);
		values.put("attack", config.attack);
		values.put("breedingItem", config.breedingItem);
		values.put("breedingItemCount", config.breedingItemCount);
		values.put("selfbreed", config.selfbreed);
		values.put("waterproof", config.waterproof);
		values.put("fireproof", config.fireproof);
		var appearance = config.beeAttributes();
		values.put("renderer", appearance.renderer);
		values.put("model", appearance.model);
		values.put("animation", appearance.animation);
		values.put("beeTexture", appearance.beeTexture);
		values.put("particleType", appearance.particleType);
		values.put("nestingPreference", appearance.nestingPreference);
		values.put("postPollination", appearance.postPollination);
		values.put("tertiaryColor", appearance.tertiaryColor);
		values.put("pollinatedSize", appearance.pollinatedSize);
		values.put("useGlowLayer", appearance.useGlowLayer);
		values.put("attackResponse", appearance.attackResponse);
		values.put("invulnerability", appearance.invulnerability);
		values.put("renderTransform", appearance.renderTransform);
		values.put("flowerBlock", appearance.flowerBlock);
		values.put("flowerTag", appearance.flowerTag);
		values.put("flowerFluid", appearance.flowerFluid);
		values.put("flowerType", appearance.flowerType);
		values.putAll(appearance.traits);
		values.put("fishingEnabled", config.fishingEnabled);
		values.put("fishingChance", config.fishingChance);
		values.put("fishingBiomes", config.fishingBiomes);
		values.put("breedingEnabled", config.breedingEnabled);
		values.put("breedingParent1", config.breedingParent1);
		values.put("breedingParent2", config.breedingParent2);
		values.put("spawningEnabled", config.spawningEnabled);
		values.put("spawningNest", config.spawningNest);
		values.put("spawningBiomes", config.spawningBiomes);
		values.put("conversionEnabled", config.conversionEnabled);
		values.put("conversionSource", config.conversionSource);
		values.put("conversionResult", config.conversionResult);
		values.put("conversionItem", config.conversionItem);
		values.put("conversionChance", config.conversionChance);
		values.put("produceEnabled", config.produceEnabled);
		values.put("produceOutputItem", config.produceOutputItem);
		values.put("produceOutputMin", config.produceOutputMin);
		values.put("produceOutputMax", config.produceOutputMax);
		values.put("produceOutputChance", config.produceOutputChance);
		return values;
	}

	public static JsonObject snapshot() {
		if (!ModConfig.areServerSpecsLoaded()) {
			throw new IllegalStateException("Server configuration is not loaded");
		}
		JsonObject data = new JsonObject();
		JsonObject values = new JsonObject();
		fields().forEach((key, value) -> values.add(key, GSON.toJsonTree(value.get())));
		data.add("values", values);
		data.add("ranges", ranges());
		data.addProperty("baseline", BeeDesignerDocument.fingerprint(data));
		return data;
	}

	public static JsonObject ordinaryTemplate() {
		return template(snapshot().getAsJsonObject("values"));
	}

	public static JsonObject template(JsonObject values) {
		JsonObject document = BeeDesignerDocument.create(PBConstants.MYRIADCREATIONS_TYPE_STRING);
		JsonObject definition = document.getAsJsonObject("definition");
		for (String key : List.of("primaryColor", "secondaryColor", "particleColor", "glowColor", "flowerItem", "createComb", "size",
				"speed", "attack", "breedingItem", "breedingItemCount", "selfbreed", "waterproof", "fireproof", "renderer", "particleType")) {
			definition.add(key, values.get(key).deepCopy());
		}
		for (String key : List.of("model", "animation", "beeTexture", "nestingPreference", "postPollination")) {
			if (!values.get(key).getAsString().isBlank() && !values.get(key).getAsString().equals("none")) {
				definition.add(key, values.get(key).deepCopy());
			}
		}
		for (String key : List.of("tertiaryColor", "attackResponse", "renderTransform", "flowerBlock", "flowerTag", "flowerFluid")) {
			if (!values.get(key).getAsString().isEmpty()) {
				definition.add(key, values.get(key).deepCopy());
			}
		}
		definition.add("useGlowLayer", values.get("useGlowLayer"));
		definition.add("invulnerability", values.get("invulnerability").deepCopy());
		definition.add("flowerType", values.get("flowerType"));
		String selectedFlower = "flowerItem";
		for (String key : List.of("flowerFluid", "flowerBlock", "flowerTag")) {
			if (definition.has(key)) {
				selectedFlower = key;
			}
		}
		for (String key : List.of("flowerItem", "flowerFluid", "flowerBlock", "flowerTag")) {
			if (!key.equals(selectedFlower)) {
				definition.remove(key);
			}
		}
		if (values.get("pollinatedSize").getAsDouble() > 0) {
			definition.add("pollinatedSize", values.get("pollinatedSize"));
		}
		ModConfig.SERVER.beeAttributes().traits.keySet().forEach(key -> definition.add(key, values.get(key).deepCopy()));
		JsonObject genes = new JsonObject();
		for (String key : List.of("temper", "behavior", "endurance", "productivity")) {
			genes.add(key, values.get(key).deepCopy());
		}
		genes.add("weather_tolerance", values.get("weatherTolerance").deepCopy());
		definition.add("attributes", genes);
		return document;
	}

	static void validateResources(Map<String, Object> values, net.minecraft.server.MinecraftServer server) {
		BeeDesignerCompiler.validate(template(GSON.toJsonTree(values).getAsJsonObject()), server);
	}

	public static Map<String, Object> validate(JsonObject data) {
		if (!snapshot().get("baseline").equals(data.get("baseline"))) {
			throw new IllegalArgumentException("designer.error.conflict");
		}
		Map<String, Object> parsed = new LinkedHashMap<>();
		JsonObject values = data.getAsJsonObject("values");
		for (var entry : fields().entrySet()) {
			Object current = entry.getValue().get();
			var element = values.get(entry.getKey());
			if (element == null && entry.getKey().equals("invulnerability")) {
				element = new com.google.gson.JsonArray();
			}
			if (element == null && entry.getKey().equals("breedingItem")) {
				element = new com.google.gson.JsonPrimitive("");
			}
			if (element == null) {
				throw invalid(entry.getKey(), entry.getValue());
			}
			Object value;
			try {
				if (current instanceof Boolean) {
					if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
						throw invalid(entry.getKey(), entry.getValue());
					}
					value = element.getAsBoolean();
				} else if (current instanceof Integer) {
					value = Integer.valueOf(element.getAsString());
				} else if (current instanceof Double) {
					double number = Double.parseDouble(element.getAsString());
					if (!Double.isFinite(number)) {
						throw invalid(entry.getKey(), entry.getValue());
					}
					value = number;
				} else if (current instanceof List<?>) {
					value = element.getAsJsonArray().asList().stream().map(item -> item.getAsString()).toList();
				} else {
					value = element.getAsString();
				}
			} catch (NumberFormatException | UnsupportedOperationException | IllegalStateException exception) {
				throw invalid(entry.getKey(), entry.getValue());
			}
			if (!entry.getValue().getSpec().test(value)) {
				throw invalid(entry.getKey(), entry.getValue());
			}
			parsed.put(entry.getKey(), value);
		}
		if (((List<?>) parsed.get("invulnerability")).size() > 128) {
			throw new IllegalArgumentException("designer.error.invulnerability");
		}
		if (parsed.get("flowerType").equals("entity_types") && ((String) parsed.get("flowerTag")).startsWith("!")) {
			throw new IllegalArgumentException("designer.error.flower_entity_tag|" + parsed.get("flowerTag"));
		}
		if ((int) parsed.get("produceOutputMin") > (int) parsed.get("produceOutputMax")) {
			throw new IllegalArgumentException("designer.error.output_range");
		}
		return Map.copyOf(parsed);
	}

	public static JsonObject ranges() {
		JsonObject result = new JsonObject();
		fields().forEach((key, field) -> {
			var range = field.getSpec().getRange();
			if (range != null) {
				JsonObject bounds = new JsonObject();
				bounds.add("min", GSON.toJsonTree(range.getMin()));
				bounds.add("max", GSON.toJsonTree(range.getMax()));
				bounds.addProperty("integer", field.get() instanceof Integer);
				result.add(key, bounds);
			}
		});
		return result;
	}

	private static IllegalArgumentException invalid(String key, ModConfigSpec.ConfigValue<?> field) {
		var range = field.getSpec().getRange();
		if (range != null) {
			return new IllegalArgumentException("designer.error.setting_range|" + key + "|" + range.getMin() + "|" + range.getMax());
		}
		return new IllegalArgumentException("designer.error.setting|" + key);
	}

	public static void apply(Map<String, Object> values) {
		fields().forEach((key, field) -> set(field, values.get(key)));
		BeeConfigApplier.applyOverrides();
		MyriadCreationsEventHandler.invalidateFilterCache();
		MyriadCreationsEventHandler.invalidateEnabledCache();
	}

	@SuppressWarnings("unchecked")
	private static <T> void set(ModConfigSpec.ConfigValue<T> field, Object value) {
		field.set((T) value);
	}
}
