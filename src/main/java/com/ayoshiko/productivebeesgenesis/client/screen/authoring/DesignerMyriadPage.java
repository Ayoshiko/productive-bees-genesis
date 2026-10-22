package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.client.screen.FilterListScreen;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 万象表单按原配置的字段类型编辑，群系和基因复用领域选择器。 */
final class DesignerMyriadPage {
	private String group = "appearance";

	void add(DesignerFormScreen screen, JsonObject snapshot, boolean canSave, boolean uncertain, Runnable save, Runnable close) {
		var mc = Minecraft.getInstance();
		screen.tabs(screen.left, screen.contentTop, screen.contentWidth, group,
				new String[]{"appearance", "attributes", "acquisition", "production"}, value -> true, value -> {
			group = value;
			screen.page = 0;
			screen.rebuild();
		});
		int saveWidth = Math.min(110, screen.contentWidth - 180);
		screen.button(screen.left + saveWidth + 4, screen.footerY(), 88,
				DesignerFormScreen.tr("random_filter"), () -> mc.setScreen(new FilterListScreen(screen)));
		screen.contentTop += 24;
		screen.note("", DesignerFormScreen.tr("reload_warning"));
		if (snapshot == null) {
			screen.note("", DesignerFormScreen.tr("status.loading"));
		} else {
			JsonObject values = snapshot.getAsJsonObject("values");
			screen.scope("myriad");
			if (group.equals("appearance")) {
				DesignerBeeModels.add(screen, values, true);
				screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
						DesignerFormScreen.tr("preview"), () -> mc.setScreen(new DesignerPreviewScreen(screen, preview(values))))));
			}
			if (group.equals("production")) {
				screen.note("", DesignerFormScreen.tr("myriad_random_production"));
			}
			if (uncertain) {
				screen.note("", DesignerFormScreen.tr("error.myriad_unknown"));
			}
			for (var entry : values.entrySet()) {
				if (group.equals(group(entry.getKey())) && !entry.getKey().equals("renderer")) {
					field(screen, values, entry.getKey(), snapshot.getAsJsonObject("ranges"));
				}
			}
			screen.scope("");
		}
		screen.primaryButton(screen.left, screen.footerY(), saveWidth,
				DesignerFormScreen.tr(uncertain ? "verify_myriad" : "save_myriad"), save).active = canSave && snapshot != null;
		screen.button(screen.left + screen.contentWidth - 80, screen.footerY(), 80, Component.translatable("gui.done"), close);
	}

	private void field(DesignerFormScreen screen, JsonObject values, String key, JsonObject ranges) {
		var value = values.get(key);
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
			screen.toggle(key, values, key, false);
		} else if (key.endsWith("Item") || key.equals("spawningNest")) {
			screen.item(key, values, key, "");
		} else if (key.contains("Parent") || key.equals("conversionSource") || key.equals("conversionResult")) {
			screen.bee(key, values, key, "");
		} else if (key.equals("fishingBiomes") || key.equals("spawningBiomes")) {
			boolean single = key.equals("spawningBiomes");
			screen.rows.add(new DesignerFormScreen.Row(key, (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("choose_biomes"), () -> Minecraft.getInstance().setScreen(new DesignerBiomeScreen(
							screen, value, single, single, !single, chosen -> values.add(key, chosen))))));
		} else if (key.equals("temper")) {
			screen.choice(key, values, key, "temper.normal", "temper.passive", "temper.normal", "temper.hostile", "temper.aggressive");
		} else if (key.equals("behavior")) {
			screen.choice(key, values, key, "behavior.diurnal", "behavior.diurnal", "behavior.nocturnal", "behavior.metaturnal");
		} else if (key.equals("weatherTolerance")) {
			screen.choice(key, values, key, "weather_tolerance.none", "weather_tolerance.none", "weather_tolerance.rain", "weather_tolerance.any");
		} else if (key.equals("endurance")) {
			screen.choice(key, values, key, "endurance.normal", "endurance.weak", "endurance.normal", "endurance.medium", "endurance.strong");
		} else if (key.equals("productivity")) {
			screen.choice(key, values, key, "productivity.normal", "productivity.normal", "productivity.medium", "productivity.high", "productivity.very_high");
		} else if (key.equals("particleType")) {
			screen.choice(key, values, key, "drip", "drip", "pop", "lava", "portal", "rising");
		} else if (key.equals("postPollination")) {
			screen.choice(key, values, key, "none", "none", "amber_encase", "bonemeal", "force_grow_crop", "sus");
		} else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			JsonObject bounds = ranges == null ? null : ranges.getAsJsonObject(key);
			DesignerNumberRange range = bounds == null ? DesignerNumberRange.forField(key, key)
					: DesignerNumberRange.of(bounds.get("min").getAsDouble(), bounds.get("max").getAsDouble(), bounds.get("integer").getAsBoolean());
			screen.number(key, values, key, value.getAsString(), range);
		} else {
			screen.field(key, values, key, "");
		}
	}

	private String group(String key) {
		if (key.startsWith("produce")) {
			return "production";
		}
		if (key.startsWith("fishing") || key.startsWith("spawning") || key.startsWith("conversion") || key.startsWith("breedingParent")
				|| key.equals("breedingEnabled")) {
			return "acquisition";
		}
		if (key.endsWith("Color") || key.equals("size") || key.equals("createComb") || key.equals("renderer") || key.equals("model")
				|| key.equals("animation") || key.equals("beeTexture") || key.equals("particleType") || key.equals("translucent") || key.equals("colorCycle")) {
			return "appearance";
		}
		return "attributes";
	}

	private static JsonObject preview(JsonObject values) {
		JsonObject document = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.create("productivebees:myriadcreations");
		JsonObject definition = values.deepCopy();
		for (String key : new String[]{"model", "animation", "beeTexture", "nestingPreference", "postPollination"}) {
			if (definition.has(key) && definition.get(key).getAsString().isBlank()) {
				definition.remove(key);
			}
		}
		document.add("definition", definition);
		return document;
	}
}
