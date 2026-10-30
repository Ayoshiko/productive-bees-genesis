package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

final class DesignerBeeFields {
	private DesignerBeeFields() {
	}

	static void appearance(DesignerFormScreen screen, JsonObject document, boolean advanced) {
		var mc = Minecraft.getInstance();
		screen.section("identity");
		screen.note("id", Component.literal(document.get("id").getAsString()));
		JsonObject names = BeeDesignerDocument.object(document, "names");
		screen.scope("names");
		screen.field("name_zh", names, "zh_cn", "");
		screen.field("name_en", names, "en_us", "");
		for (String locale : java.util.List.copyOf(names.keySet())) {
			if (!locale.equals("zh_cn") && !locale.equals("en_us")) {
				screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
					screen.button(x, y, 72, Component.literal(locale), () -> mc.setScreen(new DesignerNamesScreen(screen, names)));
					screen.button(x + w - 24, y, 22, Component.literal("×"), () -> {
						names.remove(locale);
						screen.rebuild();
					});
				}));
				screen.field("localized_name", names, locale, "");
			}
		}
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
				DesignerFormScreen.tr("add_language"), () -> mc.setScreen(new DesignerNamesScreen(screen, names)))));
		JsonObject definition = document.getAsJsonObject("definition");
		screen.scope("definition");
		screen.section("appearance");
		DesignerBeeModels.add(screen, definition);
		screen.group("appearance_colors", () -> {
			screen.field("primaryColor", definition, "primaryColor", "#E8BC39");
			screen.field("secondaryColor", definition, "secondaryColor", BeeDesignerDocument.text(definition, "primaryColor", "#E8BC39"));
			screen.field("particleColor", definition, "particleColor", "#000000");
			screen.field("tertiaryColor", definition, "tertiaryColor", BeeDesignerDocument.text(definition, "primaryColor", "#E8BC39"));
			screen.toggle("colorCycle", definition, "colorCycle", false);
		});
		screen.group("appearance_geometry", () -> {
			screen.number("size", definition, "size", "1");
			screen.number("pollinatedSize", definition, "pollinatedSize", BeeDesignerDocument.text(definition, "size", "1"));
			renderTransform(screen, definition);
			screen.toggle("translucent", definition, "translucent", BeeDesignerDocument.text(definition, "renderer", "default").equals("translucent_with_center"));
			screen.toggle("useGlowLayer", definition, "useGlowLayer", BeeDesignerDocument.text(definition, "renderer", "default").equals("default_crystal"));
		});
		screen.toggle("createComb", definition, "createComb", true);
		screen.note("comb_help", DesignerFormScreen.tr("create_comb_help"));
		if (advanced) {
			screen.group("advanced_appearance", () -> {
				screen.field("description", definition, "description", "");
				screen.field("beeTexture", definition, "beeTexture", "");
				screen.field("renderer", definition, "renderer", "default");
				screen.field("model", definition, "model", "");
				screen.field("animation", definition, "animation", "");
				screen.anchor("advanced");
				screen.rows.add(new DesignerFormScreen.Row("advanced", (x, y, w) -> screen.button(x, y, w,
						DesignerFormScreen.tr("edit_definition"), () -> mc.setScreen(new DesignerJsonScreen(screen, definition,
								document.get("id").getAsString())))));
				screen.rows.add(new DesignerFormScreen.Row("conditions", (x, y, w) -> screen.button(x, y, w,
						DesignerFormScreen.tr("edit_conditions"), () -> mc.setScreen(new DesignerConditionsScreen(screen, definition, "conditions")))));
			});
		}
		screen.scope("");
	}

	static void attributes(DesignerFormScreen screen, JsonObject definition, boolean advanced) {
		Minecraft mc = Minecraft.getInstance();
		screen.scope("definition");
		screen.section("flower_section");
		String flower = "flowerItem";
		for (String key : new String[]{"flowerBlock", "flowerTag", "flowerFluid", "flowerResource"}) {
			if (definition.has(key)) {
				flower = key;
			}
		}
		String selected = flower;
		screen.anchor(flower);
		screen.rows.add(new DesignerFormScreen.Row("flower_type", (x, y, w) -> screen.selection(x, y, w,
				selected, new String[]{"flowerItem", "flowerBlock", "flowerTag", "flowerFluid", "flowerResource"}, value -> {
					for (String key : new String[]{"flowerItem", "flowerBlock", "flowerTag", "flowerFluid", "flowerResource",
							"flowerType", "inverseFlower", "postPollination"}) {
						definition.remove(key);
					}
					if (value.equals("flowerResource")) {
						JsonObject resource = new JsonObject();
						resource.addProperty("kind", "energy");
						resource.addProperty("energy_type", "FE");
						resource.addProperty("amount", "1000");
						definition.add(value, resource);
					} else {
						String id = switch (value) {
							case "flowerBlock" -> "minecraft:gold_block";
							case "flowerTag" -> "minecraft:flowers";
							case "flowerFluid" -> "minecraft:water";
							default -> "minecraft:poppy";
						};
						definition.addProperty(value, id);
					}
				}), 20, true));
		if (flower.equals("flowerItem")) {
			screen.item("flower", definition, flower, "minecraft:poppy");
		} else if (flower.equals("flowerBlock")) {
			screen.blockFlower(definition);
		} else if (flower.equals("flowerFluid")) {
			screen.rows.add(new DesignerFormScreen.Row("flower", (x, y, w) -> {
				screen.button(x, y, w, Component.literal(BeeDesignerDocument.text(definition, "flowerFluid", "")), () -> mc.setScreen(
						DesignerResourcePicker.open(screen, "fluid", value -> BeeDesignerSelections.flower(definition, value, false, mc.level.registryAccess()))));
				screen.ghost(x, y, w, value -> value instanceof FluidStack, value -> BeeDesignerSelections.flower(definition,
						DesignerFormScreen.selection(value), false, mc.level.registryAccess()));
			}));
		} else if (flower.equals("flowerTag")) {
			flowerTag(screen, definition);
		} else {
			DesignerResourceFields.add(screen, definition.getAsJsonObject("flowerResource"), true);
		}
		screen.section("breeding_section");
		breedingFood(screen, definition);
		screen.number("breedingItemCount", definition, "breedingItemCount", "1");
		screen.toggle("selfbreed", definition, "selfbreed", true);
		screen.group("genes_section", () -> {
			JsonObject genes = definition;
			screen.gene("weatherTolerance", genes, "weather_tolerance", "weather_tolerance.none", "weather_tolerance.rain", "weather_tolerance.any");
			screen.gene("temper", genes, "temper", "temper.passive", "temper.normal", "temper.hostile", "temper.aggressive");
			screen.gene("behavior", genes, "behavior", "behavior.diurnal", "behavior.nocturnal", "behavior.metaturnal");
			screen.gene("endurance", genes, "endurance", "endurance.weak", "endurance.normal", "endurance.medium", "endurance.strong");
			screen.gene("productivity", genes, "productivity", "productivity.normal", "productivity.medium", "productivity.high", "productivity.very_high");
		});
		screen.group("environment_section", () -> {
			screen.toggle("waterproof", definition, "waterproof", false);
			screen.toggle("fireproof", definition, "fireproof", false);
			screen.toggle("coldResistant", definition, "coldResistant", false);
			screen.number("speed", definition, "speed", "1");
			screen.number("attack", definition, "attack", "2");
		});
		if (advanced) {
			screen.group("special_traits", () -> {
				for (String key : new String[]{"selfheal", "irradiated", "withered", "blinding", "draconic", "slimy", "teleporting",
						"munchies", "redstoned", "stringy", "stingless"}) {
					screen.toggle(key, definition, key, false);
				}
				specialBehavior(screen, definition);
				screen.anchor("advanced");
				screen.rows.add(new DesignerFormScreen.Row("advanced", (x, y, w) -> screen.button(x, y, w,
						DesignerFormScreen.tr("edit_definition"), () -> mc.setScreen(new DesignerJsonScreen(screen, definition, "PB definition")))));
			});
		}
		screen.scope("");
	}

	static void flowerTag(DesignerFormScreen screen, JsonObject definition) {
		screen.anchor("flowerTag");
		screen.rows.add(new DesignerFormScreen.Row("flower", (x, y, w) -> screen.button(x, y, w,
				Component.literal(BeeDesignerDocument.text(definition, "flowerTag", "")), () -> Minecraft.getInstance().setScreen(
						DesignerResourcePicker.open(screen, BeeDesignerDocument.text(definition, "flowerType", "blocks").equals("entity_types")
								? "entity_tag" : "block_tag", value -> definition.add("flowerTag", value.get("id")))))));
		screen.field("tag_id", definition, "flowerTag", "minecraft:flowers");
		if (!BeeDesignerDocument.text(definition, "flowerType", "blocks").equals("entity_types")) {
			screen.rows.add(new DesignerFormScreen.Row("inverse_flower_tag", (x, y, w) -> screen.addRenderableWidget(new DesignerTheme.Switch(x, y,
					Math.min(w, 100), BeeDesignerDocument.text(definition, "flowerTag", "").startsWith("!"), () -> {
				String tag = BeeDesignerDocument.text(definition, "flowerTag", "minecraft:flowers");
				definition.addProperty("flowerTag", tag.startsWith("!") ? tag.substring(1) : "!" + tag);
				screen.rebuild();
			}))));
		}
	}

	static void breedingFood(DesignerFormScreen screen, JsonObject definition) {
		String current = BeeDesignerDocument.text(definition, "breedingItem", "");
		if (!current.isBlank() && !current.startsWith("#")) {
			screen.item("breedingItem", definition, "breedingItem", "minecraft:poppy");
		} else {
			screen.field("breedingItem", definition, "breedingItem", "");
		}
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
			int cell = (w - 8) / 3;
			screen.button(x, y, cell, DesignerFormScreen.tr("pick_item"), () -> Minecraft.getInstance().setScreen(new BeeDesignerItemPicker(screen,
					stack -> definition.addProperty("breedingItem", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))));
			screen.button(x + cell + 4, y, cell, DesignerFormScreen.tr("breeding_tag"), () -> Minecraft.getInstance().setScreen(
					DesignerResourcePicker.open(screen, "item_tag", value -> definition.addProperty("breedingItem", "#" + value.get("id").getAsString()))));
			screen.button(x + (cell + 4) * 2, y, cell, DesignerFormScreen.tr("breeding_default"), () -> {
				definition.remove("breedingItem");
				screen.rebuild();
			});
		}));
		screen.note("", DesignerFormScreen.tr("breeding_food_help"));
	}

	static void specialBehavior(DesignerFormScreen screen, JsonObject definition) {
		screen.anchor("attackResponse");
		screen.rows.add(new DesignerFormScreen.Row("attackResponse", (x, y, w) -> screen.dropdown(x, y, w,
				"attack." + BeeDesignerDocument.text(definition, "attackResponse", "none"), new String[]{"attack.none", "attack.fire", "attack.lava"},
				value -> definition.addProperty("attackResponse", value.substring("attack.".length())))));
		screen.note("", DesignerFormScreen.tr("attack_response_help"));
		screen.anchor("invulnerability");
		screen.rows.add(new DesignerFormScreen.Row("invulnerability", (x, y, w) -> screen.button(x, y, w,
				DesignerFormScreen.tr("edit_invulnerability"), () -> Minecraft.getInstance().setScreen(new DesignerImmunityScreen(screen, definition)))));
		screen.field("nestingPreference", definition, "nestingPreference", "");
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
				DesignerFormScreen.tr("pick_nesting_tag"), () -> Minecraft.getInstance().setScreen(
						DesignerResourcePicker.open(screen, "block_tag", value -> definition.add("nestingPreference", value.get("id")))))));
		screen.anchor("flowerType");
		screen.rows.add(new DesignerFormScreen.Row("flowerType", (x, y, w) -> screen.selection(x, y, w,
				BeeDesignerDocument.text(definition, "flowerType", "blocks"), new String[]{"blocks", "entity_types"}, value -> {
			definition.addProperty("flowerType", value);
			screen.rebuild();
		})));
		screen.choice("postPollination", definition, "postPollination", "none", "none", "amber_encase", "bonemeal", "force_grow_crop", "sus");
		screen.choice("particleType", definition, "particleType", "drip", "drip", "pop", "lava", "portal", "rising");
		screen.note("", DesignerFormScreen.tr("special_behavior_help"));
	}

	static void renderTransform(DesignerFormScreen screen, JsonObject definition) {
		screen.anchor("renderTransform");
		screen.rows.add(new DesignerFormScreen.Row("renderTransform", (x, y, w) -> {
			String current = BeeDesignerDocument.text(definition, "renderTransform", "none");
			String selected = renderOption(current);
			List<String> choices = new ArrayList<>(List.of("render.normal", "render.flipped"));
			if (!choices.contains(selected)) {
				choices.add(selected);
			}
			screen.dropdown(x, y, w, selected, choices.toArray(String[]::new), value -> {
				if (!value.equals(selected)) {
					String transform = switch (value) {
						case "render.normal" -> "none";
						case "render.flipped" -> "flipped";
						default -> value;
					};
					definition.addProperty("renderTransform", transform);
				}
			}).setTooltip(Tooltip.create(DesignerFormScreen.tr("render_transform_help")));
		}));
	}

	private static String renderOption(String transform) {
		if (transform.isBlank() || transform.equals("none")) {
			return "render.normal";
		}
		return transform.equals("flipped") ? "render.flipped" : transform;
	}
}
