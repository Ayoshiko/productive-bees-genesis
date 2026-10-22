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

	private BeeDesignerMyriad() {
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
		JsonObject values = snapshot().getAsJsonObject("values");
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
		ModConfig.SERVER.beeAttributes().traits.keySet().forEach(key -> definition.add(key, values.get(key).deepCopy()));
		JsonObject genes = new JsonObject();
		for (String key : List.of("temper", "behavior", "endurance", "productivity")) {
			genes.add(key, values.get(key).deepCopy());
		}
		genes.add("weather_tolerance", values.get("weatherTolerance").deepCopy());
		definition.add("attributes", genes);
		return document;
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
