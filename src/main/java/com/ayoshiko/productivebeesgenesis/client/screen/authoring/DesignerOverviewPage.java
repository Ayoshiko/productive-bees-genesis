package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 图形总览直接引用当前草稿；卡片点击返回对应表单，不通过生成 JSON 兜转。 */
final class DesignerOverviewPage {
	private DesignerOverviewPage() {
	}

	static void add(DesignerFormScreen screen, JsonObject document, Consumer<String> locate) {
		JsonObject definition = document.getAsJsonObject("definition");
		screen.note("", DesignerFormScreen.tr("overview_help"));
		String bee = document.get("id").getAsString();
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) ->
				screen.beeButton(x, y, w, bee, () -> locate.accept("definition"))));
		heading(screen, "appearance", () -> locate.accept("definition/renderer"));
		screen.scope("names");
		JsonObject names = BeeDesignerDocument.object(document, "names");
		screen.field("name_zh", names, "zh_cn", "");
		screen.field("name_en", names, "en_us", "");
		screen.scope("definition");
		DesignerBeeModels.add(screen, definition);
		for (String color : List.of("primaryColor", "secondaryColor", "particleColor", "tertiaryColor")) {
			screen.field(color, definition, color, "#FFFFFF");
		}
		screen.number("size", definition, "size", "1");
		screen.number("pollinatedSize", definition, "pollinatedSize", BeeDesignerDocument.text(definition, "size", "1"));
		screen.toggle("colorCycle", definition, "colorCycle", false);
		screen.toggle("useGlowLayer", definition, "useGlowLayer", BeeDesignerDocument.text(definition, "renderer", "default").equals("default_crystal"));
		screen.toggle("createComb", definition, "createComb", true);
		heading(screen, "attributes", () -> locate.accept("definition/speed"));
		screen.number("speed", definition, "speed", "1");
		screen.number("attack", definition, "attack", "2");
		DesignerBeeFields.breedingFood(screen, definition);
		screen.number("breedingItemCount", definition, "breedingItemCount", "1");
		for (String flower : List.of("flowerItem", "flowerBlock", "flowerTag", "flowerFluid", "flowerResource")) {
			if (definition.has(flower)) {
				if (flower.equals("flowerResource")) {
					DesignerResourceFields.add(screen, definition.getAsJsonObject(flower), true);
				} else if (flower.equals("flowerItem")) {
					screen.item("flower", definition, flower, "minecraft:poppy");
				} else if (flower.equals("flowerBlock")) {
					screen.blockFlower(definition);
				} else if (flower.equals("flowerTag")) {
					DesignerBeeFields.flowerTag(screen, definition);
				} else {
					screen.field("flower", definition, flower, "");
				}
			}
		}
		JsonObject genes = definition;
		screen.gene("weatherTolerance", genes, "weather_tolerance", "weather_tolerance.none", "weather_tolerance.rain", "weather_tolerance.any");
		screen.gene("temper", genes, "temper", "temper.passive", "temper.normal", "temper.hostile", "temper.aggressive");
		screen.gene("behavior", genes, "behavior", "behavior.diurnal", "behavior.nocturnal", "behavior.metaturnal");
		screen.gene("endurance", genes, "endurance", "endurance.weak", "endurance.normal", "endurance.medium", "endurance.strong");
		screen.gene("productivity", genes, "productivity", "productivity.normal", "productivity.medium", "productivity.high", "productivity.very_high");
		for (String trait : List.of("selfbreed", "waterproof", "fireproof", "coldResistant", "selfheal", "irradiated",
				"withered", "blinding", "draconic", "slimy", "teleporting", "munchies", "redstoned", "stringy", "stingless", "translucent")) {
			screen.toggle(trait, definition, trait, trait.equals("selfbreed"));
		}
		DesignerBeeFields.specialBehavior(screen, definition);
		screen.scope("");
		heading(screen, "acquisition", () -> locate.accept("acquisition"));
		JsonObject recipes = document.getAsJsonObject("recipes");
		Set<String> disabled = new HashSet<>();
		if (document.has("disabled_recipes")) {
			document.getAsJsonArray("disabled_recipes").forEach(value -> disabled.add(value.getAsString()));
		}
		for (var entry : recipes.entrySet()) {
			JsonObject recipe = entry.getValue().getAsJsonObject();
			if (!DesignerFieldNavigation.production(recipe)) {
				String id = entry.getKey();
				Runnable edit = () -> locate.accept("recipes/" + id);
				screen.scope("overview/recipes/" + id);
				Component title = BeeDesignerMachineRecipes.host(recipe) == null ? DesignerFormScreen.option(recipe.get("type").getAsString())
						: DesignerRecipeSummary.machineName(document, id, recipe);
				heading(screen, title, edit);
				if (disabled.contains(id)) {
					screen.note("", DesignerFormScreen.tr("overview_disabled"));
				}
				DesignerOverviewEditing.recipe(screen, document, id, recipe, edit);
			}
		}
		heading(screen, "production", () -> locate.accept("production"));
		screen.scope("");
		Set<String> grouped = new HashSet<>();
		JsonObject nodes = document.has("production_nodes") ? document.getAsJsonObject("production_nodes") : new JsonObject();
		for (var entry : nodes.entrySet()) {
			String nodeId = entry.getKey();
			JsonObject node = entry.getValue().getAsJsonObject();
			screen.scope("production_nodes/" + nodeId);
			Runnable edit = () -> locate.accept("production_nodes/" + nodeId);
			heading(screen, DesignerFormScreen.option(BeeDesignerDocument.text(node, "route", "direct")), edit);
			screen.note("", DesignerFormScreen.tr(BeeDesignerDocument.text(node, "route", "direct").equals("direct") ? "flow_direct" : "flow_centrifuge"));
			DesignerOverviewEditing.productionTime(screen, document, nodeId, node);
			for (var value : node.getAsJsonArray("recipe_ids")) {
				String id = value.getAsString();
				grouped.add(id);
				if (recipes.has(id)) {
					JsonObject recipe = recipes.getAsJsonObject(id);
					DesignerRecipeSummary.add(screen, document, id, recipe, edit);
					if (!id.endsWith("/comb")) {
						DesignerOverviewEditing.productionFields(screen, id, recipe);
					}
				}
			}
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("overview_edit_production"), () -> net.minecraft.client.Minecraft.getInstance().setScreen(new DesignerProductionScreen(screen, document, nodeId)))));
		}
		for (var entry : recipes.entrySet()) {
			String id = entry.getKey();
			JsonObject recipe = entry.getValue().getAsJsonObject();
			if (DesignerFieldNavigation.production(recipe) && !grouped.contains(id)) {
				Runnable edit = () -> locate.accept("recipes/" + id);
				heading(screen, DesignerFormScreen.option(recipe.get("type").getAsString()), edit);
				DesignerOverviewEditing.recipe(screen, document, id, recipe, edit);
			}
		}
	}

	private static void heading(DesignerFormScreen screen, String title, Runnable edit) {
		heading(screen, DesignerFormScreen.tr(title), edit);
	}

	private static void heading(DesignerFormScreen screen, Component title, Runnable edit) {
		screen.overviewHeading(title, edit);
	}
}
