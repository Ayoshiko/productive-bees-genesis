package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

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
				DesignerFormScreen.tr("random_filter"), () -> mc.setScreen(new DesignerFilterScreen(screen)));
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
			if (group.equals("attributes")) {
				flower(screen, values);
			}
			if (group.equals("production")) {
				screen.note("", DesignerFormScreen.tr("myriad_random_production"));
			}
			if (group.equals("acquisition") && screen instanceof BeeDesignerScreen editor) {
				screen.section("myriad_additional_recipes");
				screen.note("", DesignerFormScreen.tr("myriad_additional_recipes_help"));
				screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.primaryButton(x, y, w,
						DesignerFormScreen.tr("myriad_edit_recipes"), editor::openMyriadRecipes)));
				screen.section("myriad_builtin_recipes");
			}
			if (uncertain) {
				screen.note("", DesignerFormScreen.tr("error.myriad_unknown"));
			}
			for (var entry : values.entrySet()) {
				if (group.equals(group(entry.getKey())) && !java.util.Set.of("renderer", "flowerItem", "flowerBlock", "flowerTag", "flowerFluid").contains(entry.getKey())) {
					field(screen, values, entry.getKey(), snapshot.getAsJsonObject("ranges"));
				}
			}
			screen.scope("");
		}
		screen.primaryButton(screen.left, screen.footerY(), saveWidth,
				DesignerFormScreen.tr(uncertain ? "verify_myriad" : "save_myriad"), save).active = canSave && snapshot != null;
		screen.button(screen.left + screen.contentWidth - 80, screen.footerY(), 80, Component.translatable("gui.done"), close);
	}

	private void flower(DesignerFormScreen screen, JsonObject values) {
		String selected = "flowerItem";
		for (String key : new String[]{"flowerFluid", "flowerBlock", "flowerTag"}) {
			if (!values.get(key).getAsString().isEmpty()) {
				selected = key;
			}
		}
		String field = selected;
		screen.section("flower_section");
		screen.rows.add(new DesignerFormScreen.Row("flower_type", (x, y, w) -> screen.selection(x, y, w, field,
				new String[]{"flowerItem", "flowerBlock", "flowerTag", "flowerFluid"}, value -> {
			for (String key : new String[]{"flowerBlock", "flowerTag", "flowerFluid"}) {
				values.addProperty(key, "");
			}
			if (!value.equals("flowerItem")) {
				values.addProperty(value, switch (value) {
					case "flowerBlock" -> "minecraft:gold_block";
					case "flowerTag" -> "minecraft:flowers";
					default -> "minecraft:water";
				});
			}
		}), 20, true));
		if (field.equals("flowerItem")) {
			screen.item("flower", values, field, "productivebees:honey_treat");
		} else if (field.equals("flowerBlock")) {
			screen.blockFlower(values);
		} else {
			screen.field("flower", values, field, "");
			String kind = "block_tag";
			if (field.equals("flowerFluid")) {
				kind = "fluid";
			} else if (values.get("flowerType").getAsString().equals("entity_types")) {
				kind = "entity_tag";
			}
			String browser = kind;
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("pick_item"), () ->
					Minecraft.getInstance().setScreen(DesignerResourcePicker.open(screen, browser,
							value -> values.add(field, value.get("id")))))));
		}
	}

	private void field(DesignerFormScreen screen, JsonObject values, String key, JsonObject ranges) {
		screen.anchor(key);
		var value = values.get(key);
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
			screen.toggle(key, values, key, false);
		} else if (key.equals("invulnerability")) {
			screen.rows.add(new DesignerFormScreen.Row(key, (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("edit_invulnerability"), () -> Minecraft.getInstance().setScreen(new DesignerImmunityScreen(screen, values, true)))));
		} else if (key.equals("attackResponse")) {
			screen.rows.add(new DesignerFormScreen.Row(key, (x, y, w) -> screen.dropdown(x, y, w,
					"attack." + value.getAsString(), new String[]{"attack.none", "attack.fire", "attack.lava"},
					selected -> values.addProperty(key, selected.substring("attack.".length())))));
		} else if (key.equals("flowerType")) {
			screen.choice(key, values, key, "blocks", "blocks", "entity_types");
		} else if (key.equals("renderTransform")) {
			DesignerBeeFields.renderTransform(screen, values);
		} else if (key.equals("breedingItem")) {
			DesignerBeeFields.breedingFood(screen, values);
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
		if (key.endsWith("Color") || key.equals("size") || key.equals("pollinatedSize") || key.equals("useGlowLayer") || key.equals("renderTransform")
				|| key.equals("createComb") || key.equals("renderer") || key.equals("model")
				|| key.equals("animation") || key.equals("beeTexture") || key.equals("particleType") || key.equals("translucent") || key.equals("colorCycle")) {
			return "appearance";
		}
		return "attributes";
	}

	private static JsonObject preview(JsonObject values) {
		JsonObject document = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.create("productivebees:myriadcreations");
		JsonObject definition = values.deepCopy();
		for (String key : new String[]{"model", "animation", "beeTexture", "nestingPreference", "postPollination", "tertiaryColor"}) {
			if (definition.has(key) && definition.get(key).getAsString().isBlank()) {
				definition.remove(key);
			}
		}
		if (definition.has("pollinatedSize") && definition.get("pollinatedSize").getAsDouble() <= 0) {
			definition.remove("pollinatedSize");
		}
		document.add("definition", definition);
		return document;
	}
}
