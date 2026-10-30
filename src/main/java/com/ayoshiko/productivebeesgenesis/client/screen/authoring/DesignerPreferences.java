package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 偏好仅在客户端线程修改；文件任务读取封闭快照，迟到加载不覆盖当前编辑。 */
final class DesignerPreferences {
    private static JsonObject values = defaults();
    private static DesignerPalette palette = DesignerPalette.preset("light");
    private static Path directory;
    private static long revision;
    private static long savedRevision;
    private static String problem = "";

    private DesignerPreferences() {
    }

    static JsonObject defaults() {
        JsonObject result = new JsonObject();
        result.addProperty("version", 1);
        result.addProperty("theme", "light");
        result.addProperty("density", "comfortable");
        result.addProperty("ui_scale", 1);
        result.addProperty("backdrop", 0.58);
        result.addProperty("animations", true);
        result.addProperty("reduce_motion", false);
        result.addProperty("animation_ms", 180);
		result.addProperty("motion_amount", 8);
		result.addProperty("motion_style", "responsive");
        result.addProperty("tooltip_ms", 300);
        result.addProperty("preview_sensitivity", 0.6);
        result.addProperty("auto_save", true);
        result.addProperty("auto_save_ms", 60000);
        result.addProperty("context_hints", true);
        result.addProperty("alternating_rows", true);
		result.addProperty("navigation_layout", "adaptive");
		result.addProperty("field_layout", "adaptive");
		result.addProperty("collapse_sections", true);
		result.addProperty("catalog_help", true);
		result.addProperty("tutorial_seen", false);
		result.addProperty("skin", "smooth");
		result.addProperty("font_mode", "auto");
		result.addProperty("surface_material", "solid");
		result.addProperty("motion_feedback", "subtle");
		result.addProperty("motion_transition", "slide");
		result.addProperty("motion_menus", true);
		result.addProperty("motion_selection", true);
        result.add("custom_dark", DesignerPalette.preset("dark").json());
        result.add("custom_light", DesignerPalette.preset("light").json());
        return result;
    }

    static JsonObject normalize(JsonObject supplied) {
        JsonObject result = defaults();
        for (String key : supplied.keySet()) {
            if (!result.has(key)) {
                throw new IllegalArgumentException("designer.error.preference_field|" + key);
            }
            result.add(key, supplied.get(key).deepCopy());
        }
        range(result, "version", 1, 1);
        choice(result, "theme", Set.of("dark", "light", "ae_light", "ae_dark", "custom_dark", "custom_light"));
        choice(result, "density", Set.of("compact", "comfortable", "spacious"));
		choice(result, "navigation_layout", Set.of("adaptive", "menus", "expanded"));
		choice(result, "field_layout", Set.of("adaptive", "single", "columns"));
		choice(result, "skin", Set.of("smooth", "ae"));
		choice(result, "font_mode", Set.of("auto", "sdf", "vanilla"));
		choice(result, "surface_material", Set.of("solid", "glass"));
		choice(result, "motion_feedback", Set.of("subtle", "ripple", "none"));
		choice(result, "motion_transition", Set.of("slide", "reveal", "none"));
        range(result, "ui_scale", 0.85, 1.15);
        range(result, "backdrop", 0.1, 0.9);
        range(result, "animation_ms", 80, 400);
		range(result, "motion_amount", 0, 24);
		if (result.get("motion_style").isJsonPrimitive() && result.getAsJsonPrimitive("motion_style").isString()
				&& result.get("motion_style").getAsString().equals("fluid")) {
			result.addProperty("motion_style", "responsive");
		}
		choice(result, "motion_style", Set.of("responsive", "smooth"));
        range(result, "tooltip_ms", 0, 1500);
        range(result, "preview_sensitivity", 0.15, 1.5);
        if (supplied.has("auto_save_ms") && supplied.get("auto_save_ms").isJsonPrimitive()
                && supplied.getAsJsonPrimitive("auto_save_ms").isNumber()) {
            double previous = supplied.get("auto_save_ms").getAsDouble();
            if (previous >= 1000 && previous < 15000) {
                result.addProperty("auto_save_ms", 15000);
            }
        }
        range(result, "auto_save_ms", 15000, 600000);
        for (String key : List.of("animations", "reduce_motion", "auto_save", "context_hints", "alternating_rows",
				"collapse_sections", "catalog_help", "tutorial_seen", "motion_menus", "motion_selection")) {
            if (!result.get(key).isJsonPrimitive() || !result.getAsJsonPrimitive(key).isBoolean()) {
                throw new IllegalArgumentException("designer.error.preference_field|" + key);
            }
        }
        for (String key : List.of("custom_dark", "custom_light")) {
            if (!result.get(key).isJsonObject() || DesignerPalette.parse(result.getAsJsonObject(key)).light() != key.equals("custom_light")) {
                throw new IllegalArgumentException("designer.error.preference_field|" + key);
            }
        }
        return result;
    }

    private static void choice(JsonObject value, String key, Set<String> options) {
        if (!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()
                || !options.contains(value.get(key).getAsString())) {
            throw new IllegalArgumentException("designer.error.preference_field|" + key);
        }
    }

    private static void range(JsonObject value, String key, double min, double max) {
        if (!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("designer.error.preference_field|" + key);
        }
        double number = value.get(key).getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max) {
            throw new IllegalArgumentException("designer.error.preference_field|" + key);
        }
    }

    static void load() {
        Path target = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer").toAbsolutePath().normalize();
        if (target.equals(directory)) {
            return;
        }
        directory = target;
        values = defaults();
        palette = DesignerPalette.preset("light");
        problem = "";
        long requested = ++revision;
        savedRevision = requested;
        DesignerProjectFiles.readPreferences(target, loaded -> {
            if (requested != revision || !target.equals(directory)) {
                return;
            }
			try {
				values = normalize(loaded);
                refreshPalette();
				problem = "";
				DesignerTheme.apply();
				if (Minecraft.getInstance().screen instanceof DesignerFormScreen screen) {
					screen.rebuild();
				}
			} catch (RuntimeException exception) {
				problem = exception.getMessage();
			}
        }, message -> {
            if (requested == revision && target.equals(directory)) {
                problem = message;
            }
        });
    }

    static JsonObject snapshot() {
        return values.deepCopy();
    }

    static void set(String key, String value) {
        JsonObject copy = snapshot();
        copy.addProperty(key, value);
        apply(copy);
    }

    static void set(String key, double value) {
        JsonObject copy = snapshot();
        copy.addProperty(key, value);
        apply(copy);
    }

    static void set(String key, boolean value) {
        JsonObject copy = snapshot();
        copy.addProperty(key, value);
        apply(copy);
    }

    static void apply(JsonObject supplied) {
        values = normalize(supplied);
        revision++;
        refreshPalette();
        DesignerTheme.apply();
    }

    static void theme(JsonObject json) {
        DesignerPalette palette = DesignerPalette.parse(json);
        JsonObject copy = snapshot();
        String key = palette.light() ? "custom_light" : "custom_dark";
        copy.add(key, palette.json());
        copy.addProperty("theme", key);
        apply(copy);
    }

    private static void refreshPalette() {
        String id = text("theme");
        palette = id.startsWith("custom_") ? DesignerPalette.parse(values.getAsJsonObject(id)) : DesignerPalette.preset(id);
    }

    static DesignerPalette palette() {
        return palette;
    }

    static String text(String key) {
        return values.get(key).getAsString();
    }

    static double number(String key) {
        return values.get(key).getAsDouble();
    }

    static boolean enabled(String key) {
        return values.get(key).getAsBoolean();
    }

	static boolean compactNavigation(int width) {
		String mode = text("navigation_layout");
		return mode.equals("menus") || width < 280 || mode.equals("adaptive") && width < 480;
	}

    static int rowGap() {
        return switch (text("density")) {
            case "compact" -> 2;
            case "spacious" -> 6;
            default -> 4;
        };
    }

    static boolean animate() {
        return enabled("animations") && !enabled("reduce_motion");
    }

    static String problem() {
        return problem;
    }

    static boolean dirty() {
        return revision != savedRevision;
    }

    static void save(java.util.function.Consumer<String> complete) {
        if (directory == null) {
            load();
        }
        JsonObject snapshot = snapshot();
        long requested = revision;
        Path target = directory;
        DesignerProjectFiles.savePreferences(target, snapshot, () -> {
            if (requested == revision && target.equals(directory)) {
                problem = "";
                savedRevision = requested;
            }
            complete.accept("designer.status.preferences_saved");
        }, message -> {
            if (requested == revision && target.equals(directory)) {
                problem = message;
            }
            complete.accept(message);
        });
    }
}
