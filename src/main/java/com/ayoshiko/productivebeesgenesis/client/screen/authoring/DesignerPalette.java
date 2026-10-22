package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

/** 主题只包含颜色声明；导入不加载脚本、材质路径或第三方类。 */
record DesignerPalette(String name, boolean light, int panel, int card, int input, int button, int hover,
        int text, int muted, int accent, int border, int error, int success) {
    private static final Set<String> COLOR_KEYS = Set.of("panel", "card", "input", "button", "hover",
            "text", "muted", "accent", "border", "error", "success");

    static DesignerPalette preset(String id) {
        return switch (id) {
            case "light" -> new DesignerPalette("Modern light", true, 0xFFF5F6F8, 0xFFEAEDF2, 0xFFFFFFFF,
                    0xFFECEFF4, 0xFFDCE5F3, 0xFF253140, 0xFF657284, 0xFF536FD1, 0xFFD1D8E2, 0xFFBD3B42, 0xFF248263);
            case "ae_light" -> new DesignerPalette("AE light", true, 0xFFE7E9ED, 0xFFD8DBE1, 0xFFF9FAFC,
                    0xFFD5D9E1, 0xFFE1DDF2, 0xFF303343, 0xFF64687C, 0xFF7860AE, 0xFFB5BAC7, 0xFFB93645, 0xFF357B64);
            default -> new DesignerPalette("Modern dark", false, 0xFF151B24, 0xFF1E2733, 0xFF111720,
                    0xFF283344, 0xFF35445A, 0xFFE8ECF3, 0xFFA0ADC0, 0xFF93ACF6, 0xFF3A475A, 0xFFFF9298, 0xFF79CBA5);
        };
    }

    JsonObject json() {
        JsonObject result = new JsonObject();
        result.addProperty("version", 1);
        result.addProperty("name", name);
        result.addProperty("light", light);
        JsonObject colors = new JsonObject();
        values().forEach((key, value) -> colors.addProperty(key, String.format(java.util.Locale.ROOT, "#%06X", value & 0xFFFFFF)));
        result.add("colors", colors);
        return result;
    }

    static DesignerPalette parse(JsonObject json) {
        if (json.has("version")) {
            try {
                if (!json.get("version").isJsonPrimitive() || !json.getAsJsonPrimitive("version").isNumber()
                        || json.get("version").getAsBigDecimal().intValueExact() != 1) {
                    throw new IllegalArgumentException("designer.error.theme_version");
                }
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("designer.error.theme_version", exception);
            }
        }
        for (String key : json.keySet()) {
            if (!Set.of("version", "name", "light", "colors").contains(key)) {
                throw new IllegalArgumentException("designer.error.theme_field|" + key);
            }
        }
        if (json.has("light") && (!json.get("light").isJsonPrimitive() || !json.getAsJsonPrimitive("light").isBoolean())) {
            throw new IllegalArgumentException("designer.error.theme_field|light");
        }
        if (json.has("name") && (!json.get("name").isJsonPrimitive() || !json.getAsJsonPrimitive("name").isString())) {
            throw new IllegalArgumentException("designer.error.theme_name");
        }
        if (json.has("colors") && !json.get("colors").isJsonObject()) {
            throw new IllegalArgumentException("designer.error.theme_field|colors");
        }
        boolean light = json.has("light") && json.get("light").getAsBoolean();
        DesignerPalette base = preset(light ? "light" : "dark");
        JsonObject colors = json.has("colors") && json.get("colors").isJsonObject() ? json.getAsJsonObject("colors") : new JsonObject();
        for (String key : colors.keySet()) {
            if (!COLOR_KEYS.contains(key)) {
                throw new IllegalArgumentException("designer.error.theme_field|" + key);
            }
            color(colors, key, 0);
        }
        String name = json.has("name") ? json.get("name").getAsString() : "Custom theme";
        if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("designer.error.theme_name");
        }
        return new DesignerPalette(name, light, color(colors, "panel", base.panel), color(colors, "card", base.card),
                color(colors, "input", base.input), color(colors, "button", base.button), color(colors, "hover", base.hover),
                color(colors, "text", base.text), color(colors, "muted", base.muted), color(colors, "accent", base.accent),
                color(colors, "border", base.border), color(colors, "error", base.error), color(colors, "success", base.success));
    }

    private static int color(JsonObject colors, String key, int fallback) {
        if (!colors.has(key)) {
            return fallback;
        }
        if (!colors.get(key).isJsonPrimitive() || !colors.getAsJsonPrimitive(key).isString()
                || !colors.get(key).getAsString().matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("designer.error.theme_color|" + key);
        }
        return 0xFF000000 | Integer.parseInt(colors.get(key).getAsString().substring(1), 16);
    }

    Map<String, Integer> values() {
        return Map.ofEntries(Map.entry("panel", panel), Map.entry("card", card), Map.entry("input", input),
                Map.entry("button", button), Map.entry("hover", hover), Map.entry("text", text), Map.entry("muted", muted),
                Map.entry("accent", accent), Map.entry("border", border), Map.entry("error", error), Map.entry("success", success));
    }

    int selected() {
        return mix(button, accent, light ? 0.18F : 0.24F);
    }

    static int mix(int first, int second, float amount) {
        float ratio = Math.max(0, Math.min(1, amount));
        int red = Math.round(((first >> 16) & 255) * (1 - ratio) + ((second >> 16) & 255) * ratio);
        int green = Math.round(((first >> 8) & 255) * (1 - ratio) + ((second >> 8) & 255) * ratio);
        int blue = Math.round((first & 255) * (1 - ratio) + (second & 255) * ratio);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static int contrast(int color) {
        double luminance = ((color >> 16) & 255) * 0.2126 + ((color >> 8) & 255) * 0.7152 + (color & 255) * 0.0722;
        return luminance > 154 ? 0xFF17212C : 0xFFFFFFFF;
    }
}
