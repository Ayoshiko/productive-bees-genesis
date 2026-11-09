package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.Tooltip;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 选择上游已经提供的外观；独立实体的 AI 不随模型改变。 */
final class DesignerBeeModels {
	private static final List<String> STANDARD = List.of("default", "thicc", "default_crystal", "default_shell",
			"default_foliage", "elvis", "tiny", "translucent_with_center");
	private static final List<String> SPECIAL = List.of("beebee", "butcher", "fbi", "royal", "villager");
	private static final Map<String, String> TEXTURES = Map.of("elvis", "phil", "tiny", "sugarbag", "small", "base/default",
			"slim", "base/default", "translucent_with_center", "slimy");

	private DesignerBeeModels() {
	}

	static void add(DesignerFormScreen screen, JsonObject definition) {
		add(screen, definition, false);
	}

	static void add(DesignerFormScreen screen, JsonObject definition, boolean configValues) {
		screen.anchor("renderer");
		screen.anchor("model");
		screen.rows.add(new DesignerFormScreen.Row("bee_model", (x, y, w) -> {
			String selected = selected(definition);
			List<String> choices = new ArrayList<>(STANDARD);
			if (ModList.get().isLoaded("geckolib")) {
				for (String model : SPECIAL) {
					choices.add("model." + model);
				}
			} else {
				choices.add("small");
				choices.add("slim");
			}
			if (!choices.contains(selected)) {
				choices.add(selected);
			}
			screen.dropdown(x, y, w, selected, choices.toArray(String[]::new), value -> {
				select(definition, value);
				if (configValues) {
					definition.addProperty("model", BeeDesignerDocument.text(definition, "model", ""));
					definition.addProperty("beeTexture", BeeDesignerDocument.text(definition, "beeTexture", ""));
				}
			})
					.setTooltip(Tooltip.create(DesignerFormScreen.tr("model_help")));
		}));
		screen.note("", DesignerFormScreen.tr("model_help"));
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
				DesignerFormScreen.tr("model_texture"), () -> {
					String selected = selected(definition);
					if (selected.startsWith("model.") && SPECIAL.contains(selected.substring(6))) {
						definition.addProperty("beeTexture", "productivebees:textures/entity/bee/" + selected.substring(6) + "/bee");
					} else if (STANDARD.contains(selected) || selected.equals("small") || selected.equals("slim")) {
						matchingTexture(definition, selected);
					}
					if (configValues) {
						definition.addProperty("beeTexture", BeeDesignerDocument.text(definition, "beeTexture", ""));
					}
					screen.rebuild();
				}).setTooltip(Tooltip.create(DesignerFormScreen.tr("model_texture_help")))));
	}

	static String selected(JsonObject definition) {
		String model = BeeDesignerDocument.text(definition, "model", "");
		for (String preset : SPECIAL) {
			if (model.equals("productivebees:geo/entity/" + preset + ".geo.json")) {
				return "model." + preset;
			}
		}
		if (!model.isEmpty()) {
			return model;
		}
		return BeeDesignerDocument.text(definition, "renderer", "default");
	}

	static void select(JsonObject definition, String selected) {
		String previous = selected(definition);
		String texture = BeeDesignerDocument.text(definition, "beeTexture", "");
		boolean automatic = texture.isEmpty() || texture.equals(texture(previous));
		if (selected.startsWith("model.") && SPECIAL.contains(selected.substring(6))) {
			definition.addProperty("model", "productivebees:geo/entity/" + selected.substring(6) + ".geo.json");
			definition.addProperty("renderer", "default");
		} else if (STANDARD.contains(selected) || selected.equals("small") || selected.equals("slim")) {
			definition.remove("model");
			definition.addProperty("renderer", selected);
		}
		if (automatic) {
			matchingTexture(definition, selected);
		}
	}

	private static String texture(String selected) {
		if (selected.startsWith("model.") && SPECIAL.contains(selected.substring(6))) {
			return "productivebees:textures/entity/bee/" + selected.substring(6) + "/bee";
		}
		return TEXTURES.containsKey(selected) ? "productivebees:textures/entity/bee/" + TEXTURES.get(selected) + "/bee" : "";
	}

	private static void matchingTexture(JsonObject definition, String selected) {
		String texture = texture(selected);
		if (texture.isEmpty()) {
			definition.remove("beeTexture");
		} else {
			definition.addProperty("beeTexture", texture);
		}
	}
}
