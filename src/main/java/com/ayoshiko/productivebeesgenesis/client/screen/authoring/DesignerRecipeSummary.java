package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerWorldConversions;
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
						screen.addRenderableWidget(new DesignerMachineEntryButton(x, y, w, entry, edit).fitted())));
			}
			return;
		}
		switch (type) {
			case "productivebees:bottler" -> {
				item(screen, "ingredient", recipe.get("ingredient"), edit);
				screen.rows.add(new DesignerFormScreen.Row("fluid", (x, y, w) -> screen.addRenderableWidget(new DesignerResourceButton(
						x, y, w, recipe.getAsJsonObject("fluid"), true, edit).fitted())));
				inline(screen, new DesignerInlineNumber("fluid_amount", recipe.getAsJsonObject("fluid"), "amount", "250", DesignerNumberRange.of(1, Integer.MAX_VALUE, true)));
				item(screen, "egg_output", recipe.get("result"), edit);
			}
			case "productivebees:item_conversion" -> {
				item(screen, "conversion_input", recipe.get("ingredients"), edit);
				item(screen, "conversion_output", recipe.get("result"), edit);
				if (!BeeDesignerWorldConversions.shared(recipe) && recipe.has("result")) {
					inline(screen, new DesignerInlineNumber("quantity", recipe.getAsJsonObject("result"), "count", "1", DesignerNumberRange.of(1, 99, true)));
				}
				chance(screen, recipe);
			}
			case "productivebees:block_conversion" -> {
				if (recipe.has("input") && (!recipe.get("input").isJsonArray() || !recipe.getAsJsonArray("input").isEmpty())) {
					item(screen, "conversion_input", recipe.get("input"), edit);
				} else {
					block(screen, "conversion_from", recipe.getAsJsonObject("from"), edit);
				}
				block(screen, "conversion_to", recipe.getAsJsonObject("to"), edit);
				chance(screen, recipe);
			}
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
				biomes(screen, recipe.get("biomes"));
				screen.note("", DesignerFormScreen.tr("overview_spawn_help"));
			}
			case "productivebees:bee_fishing" -> {
				screen.note("", DesignerFormScreen.tr("overview_fishing_help"));
				biomes(screen, recipe.get("biomes"));
				chance(screen, recipe);
			}
			case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" -> {
				craftingGrid(screen, recipe, edit);
				item(screen, "egg_output", recipe.get("result"), edit);
				if (recipe.has("result") && recipe.get("result").isJsonObject()) {
					inline(screen, new DesignerInlineNumber("quantity", recipe.getAsJsonObject("result"), "count", "1", DesignerNumberRange.of(1, 64, true)));
				}
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
					if (type.equals("productivebees:advanced_beehive") && recipe.get("ingredient").isJsonPrimitive()) {
						screen.rows.add(new DesignerFormScreen.Row("bee", (x, y, w) -> screen.beeButton(x, y, w, recipe.get("ingredient").getAsString(), edit)));
					} else {
						item(screen, "ingredient", recipe.get("ingredient"), edit);
					}
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
							itemOutput(screen, output, edit);
						}
					}
				}
				if (recipe.has("fluid")) {
					int width = new DesignerResourceButton(0, 0, 260, recipe.getAsJsonObject("fluid"), true, edit).fitted().getWidth();
					screen.rows.add(new DesignerFormScreen.Row("fluid", (x, y, w) ->
							screen.addRenderableWidget(new DesignerResourceButton(x, y, w, recipe.getAsJsonObject("fluid"), true, edit).fitted()), 24, false, width)
							.flow(fieldWidth("fluid", width)));
					inline(screen, new DesignerInlineNumber("fluid_amount", recipe.getAsJsonObject("fluid"), "amount", "1000",
							DesignerNumberRange.forField("fluid_amount", "amount")));
				}
				if (recipe.has("processingTime") || recipe.has("processing_time")) {
					String time = recipe.has("processing_time") ? "processing_time" : "processingTime";
					inline(screen, new DesignerInlineNumber("processing_time", recipe, time, "200", DesignerNumberRange.forField("processing_time", time)));
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

	private static void itemOutput(DesignerFormScreen screen, JsonObject output, Runnable edit) {
		ItemStack stack = DesignerStackButton.decode(output.get("item"));
		String minimum = BeeDesignerDocument.text(output, "min", "1");
		String maximum = BeeDesignerDocument.text(output, "max", "1");
		List<DesignerInlineNumber> fields = new ArrayList<>();
		var quantity = DesignerNumberRange.forField("quantity", "min");
		if (minimum.equals(maximum)) {
			fields.add(new DesignerInlineNumber("quantity", output, "min", "1", quantity).paired("max"));
		} else {
			fields.add(new DesignerInlineNumber("minimum_short", output, "min", "1", quantity));
			fields.add(new DesignerInlineNumber("maximum_short", output, "max", "1", quantity));
		}
		fields.add(DesignerInlineNumber.probability(output, "chance"));
		int itemWidth = Math.max(104, Math.min(260, Minecraft.getInstance().font.width(BeeDesignerClient.itemName(stack)) + 38));
		int controlsWidth = DesignerInlineNumber.totalWidth(fields);
		boolean inline = itemWidth + controlsWidth + 24 <= screen.contentWidth;
		int height = inline ? 24 : 24 + DesignerInlineNumber.rows(fields, screen.contentWidth - 8) * 24;
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
			var item = screen.stackButton(x + 4, y, inline ? Math.min(itemWidth, w - controlsWidth - 20) : w - 8,
					stack, DesignerFormScreen.tr("output").getString(), edit);
			int start = inline ? item.getWidth() + 16 : 4;
			DesignerInlineNumber.flow(screen, fields, x + start, y + (inline ? 0 : 24), w - start - 4);
		}, height, true));
	}
	static void item(DesignerFormScreen screen, String label, JsonElement value, Runnable edit) {
		ItemStack decoded = DesignerStackButton.decode(value);
		if (decoded.isEmpty() && value != null && value.isJsonPrimitive()) {
			ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
			if (id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) != Items.AIR) {
				decoded = new ItemStack(BuiltInRegistries.ITEM.get(id));
			}
		}
		ItemStack stack = decoded;
		Component name = stack.isEmpty() ? Component.literal(value == null ? DesignerFormScreen.tr(label).getString() : value.toString())
				: BeeDesignerClient.itemName(stack);
		int width = Math.max(104, Math.min(260, Minecraft.getInstance().font.width(name) + 38));
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> {
			if (stack.isEmpty() && value != null) {
				screen.button(x, y, w, name, edit);
			} else {
				screen.stackButton(x, y, w, stack, DesignerFormScreen.tr(label).getString(), edit);
			}
		}, 24, false, width).flow(fieldWidth(label, width)));
	}

	private static void bee(DesignerFormScreen screen, String label, JsonObject recipe, String key, Runnable edit) {
		String id = BeeDesignerDocument.text(recipe, key, "minecraft:bee");
		int width = Math.max(104, Math.min(260, Minecraft.getInstance().font.width(BeeDesignerClient.beeName(id)) + 38));
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> screen.addRenderableWidget(
				new DesignerBeeButton(x, y, w, id, edit).fitted()), 24, false, width).flow(fieldWidth(label, width)));
	}

	private static void block(DesignerFormScreen screen, String label, JsonObject state, Runnable edit) {
		String name = state == null ? "minecraft:air" : BeeDesignerDocument.text(state, "Name", "minecraft:air");
		ResourceLocation id = ResourceLocation.tryParse(name);
		var block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
		Component title = block == null ? Component.literal(name) : block.getName();
		int width = Math.max(104, Math.min(260, Minecraft.getInstance().font.width(title) + 38));
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> screen.stackButton(x, y, w,
				block == null ? ItemStack.EMPTY : block.asItem().getDefaultInstance(), title.getString(), edit), 24, false, width).flow(fieldWidth(label, width)));
	}

	private static void biomes(DesignerFormScreen screen, JsonElement value) {
		if (value == null || value.isJsonArray() && value.getAsJsonArray().isEmpty()) {
			screen.note("biomes", DesignerFormScreen.tr("any_biome"));
			return;
		}
		List<JsonElement> entries = value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
		List<String> names = new ArrayList<>();
		for (JsonElement entry : entries) {
			String text = entry.getAsString();
			ResourceLocation id = ResourceLocation.tryParse(text);
			names.add(id == null ? text : Component.translatableWithFallback(
					"biome." + id.getNamespace() + "." + id.getPath(), text).getString());
		}
		screen.note("biomes", Component.literal(String.join(" · ", names)));
	}

	private static void chance(DesignerFormScreen screen, JsonObject recipe) {
		if (BeeDesignerWorldConversions.shared(recipe)) {
			screen.note("chance", Component.literal(BeeDesignerDocument.text(recipe, "chance", "1")));
			return;
		}
		inline(screen, DesignerInlineNumber.probability(recipe, "chance"));
	}

	private static void inline(DesignerFormScreen screen, DesignerInlineNumber field) {
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> field.add(screen, x + 4, y, w - 8), 24, true).flow(field.width() + 8).joined());
	}

	private static int fieldWidth(String label, int control) {
		return Minecraft.getInstance().font.width(DesignerFormScreen.tr(label)) + control + 14;
	}
}
