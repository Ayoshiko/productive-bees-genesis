package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 总览与机器列表读取同一份草稿，展示声明的路线和数量。 */
final class DesignerRecipeSummary {
	private DesignerRecipeSummary() {
	}

	static Component machineName(JsonObject document, String id, JsonObject recipe) {
		JsonObject source = document.has("machine_sources") ? document.getAsJsonObject("machine_sources").getAsJsonObject(id) : null;
		if (source != null) {
			Component name = DesignerMachinePicker.machineName(source);
			if (!name.getString().isBlank()) {
				return name;
			}
		}
		return DesignerFormScreen.option(BeeDesignerDocument.text(recipe, "machine", BeeDesignerDocument.text(recipe, "type", "")));
	}

	static Component machineFlow(JsonObject recipe) {
		List<String> inputs = new ArrayList<>();
		List<String> outputs = new ArrayList<>();
		for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
			Component label = new DesignerMachineEntryButton(0, 0, 200, entry, () -> {}).summary();
			(entry.input() ? inputs : outputs).add(label.getString());
		}
		if (recipe.has("eu") && !recipe.get("eu").getAsString().equals("0")) {
			inputs.add(recipe.get("eu").getAsString() + " EU/t");
		}
		return Component.literal(join(inputs) + " → " + join(outputs));
	}

	private static String join(List<String> names) {
		return names.isEmpty() ? DesignerFormScreen.tr("no_materials").getString() : String.join(" + ", names);
	}

	static void add(DesignerFormScreen screen, JsonObject document, String id, JsonObject recipe, Runnable edit) {
		String type = BeeDesignerDocument.text(recipe, "type", "");
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			screen.note("", machineFlow(recipe));
			screen.note("", Component.translatable("designer.recipe_time",
					BeeDesignerDocument.text(recipe, "time", BeeDesignerDocument.text(recipe, "duration", "20"))));
			for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
				screen.rows.add(new DesignerFormScreen.Row(entry.input() ? "machine_inputs" : "machine_outputs", (x, y, w) ->
						screen.addRenderableWidget(new DesignerMachineEntryButton(x, y, w, entry, edit))));
			}
			return;
		}
		switch (type) {
			case "productivebees:bee_breeding" -> {
				bee(screen, "parent1", recipe, "parent1", edit);
				bee(screen, "parent2", recipe, "parent2", edit);
				bee(screen, "offspring", recipe, "offspring", edit);
				screen.note("", DesignerFormScreen.tr("overview_breeding_help"));
			}
			case "productivebees:bee_conversion" -> {
				bee(screen, "conversionSource", recipe, "source", edit);
				item(screen, "conversionItem", recipe.get("item"), edit);
				bee(screen, "conversionResult", recipe, "result", edit);
				chance(screen, recipe);
			}
			case "productivebees:bee_spawning" -> {
				item(screen, "spawningNest", recipe.get("ingredient"), edit);
				item(screen, "spawn_item", recipe.get("spawn_item"), edit);
				screen.note("biomes", recipe.has("biomes") ? Component.literal(recipe.get("biomes").toString()) : DesignerFormScreen.tr("any_biome"));
				screen.note("", DesignerFormScreen.tr("overview_spawn_help"));
			}
			case "productivebees:bee_fishing" -> {
				screen.note("", DesignerFormScreen.tr("overview_fishing_help"));
				screen.note("biomes", recipe.has("biomes") ? Component.literal(recipe.get("biomes").toString()) : DesignerFormScreen.tr("any_biome"));
				chance(screen, recipe);
			}
			case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" -> {
				craftingGrid(screen, recipe, edit);
				item(screen, "egg_output", recipe.get("result"), edit);
				screen.note("quantity", Component.literal(recipe.has("result") && recipe.get("result").isJsonObject()
						? BeeDesignerDocument.text(recipe.getAsJsonObject("result"), "count", "1") : "1"));
			}
			default -> {
				boolean centrifuge = type.endsWith("centrifuge") || type.endsWith("centrifuging");
				boolean resource = type.startsWith("productivebeesgenesis:");
				String flow;
				if (centrifuge) {
					flow = resource ? "flow_resource_centrifuge" : "flow_pb_centrifuge";
				} else {
					flow = resource ? "flow_resource_direct" : "flow_pb_direct";
				}
				screen.note("", DesignerFormScreen.tr(flow));
				if (recipe.has("ingredient")) {
					item(screen, "ingredient", recipe.get("ingredient"), edit);
				}
				String field = type.endsWith("advanced_beehive") ? "results" : "outputs";
				if (recipe.has(field)) {
					for (JsonElement value : recipe.getAsJsonArray(field)) {
						JsonObject output = value.getAsJsonObject();
						if (type.startsWith("productivebeesgenesis:")) {
							screen.rows.add(new DesignerFormScreen.Row("output", (x, y, w) ->
									screen.addRenderableWidget(new DesignerResourceButton(x, y, w, output, false, edit))));
							chance(screen, output);
						} else {
							ItemStack stack = DesignerStackButton.decode(output.get("item"));
							String amount = BeeDesignerDocument.text(output, "min", "1") + "–" + BeeDesignerDocument.text(output, "max", "1");
							screen.rows.add(new DesignerFormScreen.Row("output", (x, y, w) ->
									screen.stackButton(x, y, w, stack, DesignerFormScreen.tr("output").getString(), edit)));
							screen.note("quantity", Component.literal(amount + " · ").append(DesignerFormScreen.tr("chance"))
									.append(" " + BeeDesignerDocument.text(output, "chance", "1")));
						}
					}
				}
				if (recipe.has("fluid")) {
					screen.rows.add(new DesignerFormScreen.Row("fluid", (x, y, w) ->
							screen.addRenderableWidget(new DesignerResourceButton(x, y, w, recipe.getAsJsonObject("fluid"), true, edit))));
				}
				if (recipe.has("processingTime") || recipe.has("processing_time")) {
					screen.note("", Component.translatable("designer.recipe_time",
							BeeDesignerDocument.text(recipe, "processing_time", BeeDesignerDocument.text(recipe, "processingTime", "200"))));
				}
				if (BeeDesignerDocument.text(recipe, "include_pb_outputs", "false").equals("true")) {
					screen.note("", DesignerFormScreen.tr("overview_include_pb"));
				}
			}
		}

		if (recipe.has("neoforge:conditions")) {
			screen.note("conditions", DesignerFormScreen.tr("overview_conditional"));
		}
	}

	private static void craftingGrid(DesignerFormScreen screen, JsonObject recipe, Runnable edit) {
		screen.anchor("crafting_grid");
		JsonElement[] slots = new JsonElement[9];
		if (recipe.has("pattern") && recipe.has("key")) {
			var pattern = recipe.getAsJsonArray("pattern");
			var keys = recipe.getAsJsonObject("key");
			for (int row = 0; row < Math.min(3, pattern.size()); row++) {
				String line = pattern.get(row).getAsString();
				for (int column = 0; column < Math.min(3, line.length()); column++) {
					slots[row * 3 + column] = keys.get(Character.toString(line.charAt(column)));
				}
			}
		} else if (recipe.has("ingredients")) {
			var ingredients = recipe.getAsJsonArray("ingredients");
			for (int index = 0; index < Math.min(9, ingredients.size()); index++) {
				slots[index] = ingredients.get(index);
			}
		}
		int cell = Math.max(24, Math.min(32, (screen.editorBottom() - screen.contentTop - 40) / 3));
		for (int row = 0; row < 3; row++) {
			int selected = row;
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
				int size = Math.min(cell, (w - 8) / 3);
				int gridX = x + (w - size * 3 - 8) / 2;
				for (int column = 0; column < 3; column++) {
					JsonElement ingredient = slots[selected * 3 + column];
					screen.addRenderableWidget(new DesignerStackButton(gridX + column * (size + 4), y, size,
							DesignerStackButton.decode(ingredient), DesignerFormScreen.tr("empty_slot").getString(), edit).square());
				}
			}, cell + 4));
		}
	}
	static void item(DesignerFormScreen screen, String label, JsonElement value, Runnable edit) {
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> {
			ItemStack stack = DesignerStackButton.decode(value);
			if (stack.isEmpty() && value != null && value.isJsonPrimitive()) {
				ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
				if (id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) != Items.AIR) {
					stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
				}
			}
			if (stack.isEmpty() && value != null) {
				screen.button(x, y, w, Component.literal(value.toString()), edit);
			} else {
				screen.stackButton(x, y, w, stack, DesignerFormScreen.tr(label).getString(), edit);
			}
		}));
	}

	private static void bee(DesignerFormScreen screen, String label, JsonObject recipe, String key, Runnable edit) {
		String id = BeeDesignerDocument.text(recipe, key, "minecraft:bee");
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> screen.beeButton(x, y, w, id, edit)));
	}

	private static void chance(DesignerFormScreen screen, JsonObject recipe) {
		screen.note("chance", Component.literal(BeeDesignerDocument.text(recipe, "chance", "1")));
	}
}
