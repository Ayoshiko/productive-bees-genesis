package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/** 已知编辑字段只校验表示类型；未知扩展保持原文，值域和注册身份仍由编译器检查。 */
public final class BeeDesignerFieldTypes {
    private static final Set<String> BOOLEANS = Set.of("selfbreed", "createComb", "fireproof", "waterproof",
            "coldResistant", "selfheal", "irradiated", "isSolitary", "isTranslucent", "isGlowing",
            "colorCycle", "useGlowLayer", "translucent", "withered", "blinding", "draconic", "slimy",
            "teleporting", "munchies", "redstoned", "stringy", "stingless", "inverseFlower");
    private static final Set<String> NUMBERS = Set.of("size", "pollinatedSize", "speed", "attack", "breedingItemCount");
    private static final Set<String> STRINGS = Set.of("primaryColor", "secondaryColor", "tertiaryColor",
            "particleColor", "glowColor", "flowerItem", "flowerBlock", "flowerTag", "flowerFluid",
            "flowerType", "breedingItem", "description", "beeTexture", "renderer", "model", "animation",
            "renderTransform", "attackResponse", "nestingPreference", "postPollination", "particleType");

    private BeeDesignerFieldTypes() {
    }

    public static void validateDefinition(JsonObject definition) {
        for (var entry : definition.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            String expected = null;
            if (BOOLEANS.contains(key)) {
                expected = "boolean";
            } else if (NUMBERS.contains(key)) {
                expected = "number";
            } else if (STRINGS.contains(key)) {
                expected = "string";
            } else if (key.equals("attributes") || key.equals("flowerResource")) {
                expected = "object";
            } else if (key.equals("conditions") || key.equals("invulnerability")) {
                expected = "array";
            }
            if (expected != null) {
                require(value, key, expected);
            }
        }
        if (definition.has("attributes")) {
            JsonObject attributes = definition.getAsJsonObject("attributes");
            for (String key : Set.of("weather_tolerance", "temper", "behavior", "endurance", "productivity")) {
                if (attributes.has(key)) {
                    require(attributes.get(key), "attributes/" + key, "string");
                }
            }
        }
        if (definition.has("invulnerability")) {
            for (JsonElement value : definition.getAsJsonArray("invulnerability")) {
                require(value, "invulnerability", "string");
            }
        }
        if (definition.has("conditions")) {
            for (JsonElement value : definition.getAsJsonArray("conditions")) {
                require(value, "conditions", "object");
            }
        }
    }

    public static boolean matches(JsonElement value, String expected) {
        if (value == null) {
            return true;
        }
        return switch (expected) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            default -> throw new IllegalArgumentException("Unknown field type: " + expected);
        };
    }

    private static void require(JsonElement value, String key, String expected) {
        if (!matches(value, expected)) {
            throw new IllegalArgumentException("designer.error.field_type|" + key + "|" + expected);
        }
    }
}
