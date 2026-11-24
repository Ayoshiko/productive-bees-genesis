package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 九宫格共享真实材料声明；相同标签或完整组件使用同一符号。 */
final class DesignerCraftingGrid {
	private DesignerCraftingGrid() {
	}

	static JsonElement[] read(JsonObject recipe) {
		JsonElement[] slots = new JsonElement[9];
		if (recipe.has("pattern") && recipe.has("key")) {
			JsonArray pattern = recipe.getAsJsonArray("pattern");
			JsonObject keys = recipe.getAsJsonObject("key");
			for (int row = 0; row < Math.min(3, pattern.size()); row++) {
				String line = pattern.get(row).getAsString();
				for (int column = 0; column < Math.min(3, line.length()); column++) {
					JsonElement ingredient = keys.get(Character.toString(line.charAt(column)));
					slots[row * 3 + column] = ingredient == null ? null : ingredient.deepCopy();
				}
			}
		} else if (recipe.has("ingredients")) {
			JsonArray ingredients = recipe.getAsJsonArray("ingredients");
			for (int index = 0; index < Math.min(9, ingredients.size()); index++) {
				slots[index] = ingredients.get(index).deepCopy();
			}
		}
		return slots;
	}

	static void add(DesignerFormScreen screen, JsonObject recipe) {
		JsonElement[] slots = read(recipe);
		boolean shaped = recipe.get("type").getAsString().equals("minecraft:crafting_shaped");
		mode(screen, shaped, selected -> {
			write(recipe, slots, selected);
			screen.rebuild();
		});
		Minecraft minecraft = Minecraft.getInstance();
		int cell = Math.max(24, Math.min(32, (screen.editorBottom() - screen.contentTop - 40) / 3));
		screen.anchor("ingredients");
		screen.anchor("pattern");
		screen.anchor("key");
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
			int size = Math.min(cell, (w - 8) / 3);
			int gridX = x + (w - size * 3 - 8) / 2;
			for (int row = 0; row < 3; row++) {
				int sy = y + row * (size + 4);
				for (int column = 0; column < 3; column++) {
					int index = row * 3 + column;
					int sx = gridX + column * (size + 4);
					screen.addRenderableWidget(new DesignerStackButton(sx, sy, size,
							DesignerStackButton.decode(slots[index]), DesignerFormScreen.tr("empty_slot").getString(),
							() -> minecraft.setScreen(new DesignerIngredientScreen(screen, slots[index],
									value -> change(recipe, slots, index, value)))).square()
							.clearable(() -> {
								if (change(recipe, slots, index, null)) {
									screen.rebuild();
								}
							}));
					screen.ghost(sx, sy, size, size, slots[index] != null, value -> value instanceof ItemStack,
							value -> change(recipe, slots, index, BeeDesignerSelections.ingredient(
									DesignerFormScreen.selection(value), minecraft.level.registryAccess(), true)));
				}
			}
		}, cell * 3 + 8, true));
		screen.note("grid_help", DesignerFormScreen.tr("grid_inline_help"));
	}

	static void mode(DesignerFormScreen screen, boolean shaped, Consumer<Boolean> selected) {
		screen.anchor("crafting_mode");
		screen.rows.add(new DesignerFormScreen.Row("crafting_mode", (x, y, w) -> {
			var shapedLabel = DesignerFormScreen.tr("crafting_mode_shaped");
			var shapelessLabel = DesignerFormScreen.tr("crafting_mode_shapeless");
			var font = DesignerFonts.body();
			int cell = Math.min((w - 4) / 2, Math.max(48, Math.max(font.width(shapedLabel), font.width(shapelessLabel)) + 16));
			var ordered = new DesignerTheme.Action(x, y, cell, shapedLabel, () -> {
				if (!shaped) {
					selected.accept(true);
				}
			});
			ordered.setTooltip(Tooltip.create(DesignerFormScreen.tr("crafting_mode_shaped_help")));
			if (shaped) {
				ordered.primary();
			}
			screen.addRenderableWidget(ordered);
			var unordered = new DesignerTheme.Action(x + cell + 4, y, cell, shapelessLabel, () -> {
				if (shaped) {
					selected.accept(false);
				}
			});
			unordered.setTooltip(Tooltip.create(DesignerFormScreen.tr("crafting_mode_shapeless_help")));
			if (!shaped) {
				unordered.primary();
			}
			screen.addRenderableWidget(unordered);
			int hintWidth = w - cell * 2 - 12;
			if (hintWidth >= 32) {
				var hint = new DesignerTheme.InlineText(x + cell * 2 + 12, y, hintWidth, 20,
						DesignerFormScreen.tr(shaped ? "crafting_mode_shaped_hint" : "crafting_mode_shapeless_hint"));
				hint.setColor(DesignerTheme.colors().muted());
				hint.setTooltip(Tooltip.create(DesignerFormScreen.tr(shaped ? "crafting_mode_shaped_help" : "crafting_mode_shapeless_help")));
				screen.addRenderableWidget(hint);
			}
		}, 24, true));
	}

	private static boolean change(JsonObject recipe, JsonElement[] slots, int index, JsonElement value) {
		if (java.util.Objects.equals(slots[index], value)) {
			return false;
		}
		slots[index] = value;
		write(recipe, slots, recipe.get("type").getAsString().equals("minecraft:crafting_shaped"));
		return true;
	}

	static void write(JsonObject recipe, JsonElement[] slots, boolean shaped) {
		recipe.remove("ingredients");
		recipe.remove("key");
		recipe.remove("pattern");
		if (!shaped) {
			JsonArray ingredients = new JsonArray();
			for (JsonElement value : slots) {
				if (value != null) {
					ingredients.add(value.deepCopy());
				}
			}
			recipe.addProperty("type", "minecraft:crafting_shapeless");
			recipe.add("ingredients", ingredients);
			return;
		}
		Map<JsonElement, Character> symbols = new LinkedHashMap<>();
		JsonObject keys = new JsonObject();
		JsonArray pattern = new JsonArray();
		for (int row = 0; row < 3; row++) {
			StringBuilder line = new StringBuilder();
			for (int column = 0; column < 3; column++) {
				JsonElement ingredient = slots[row * 3 + column];
				if (ingredient == null) {
					line.append(' ');
				} else {
					char symbol = symbols.computeIfAbsent(ingredient, unused -> (char) ('A' + symbols.size()));
					line.append(symbol);
					keys.add(Character.toString(symbol), ingredient.deepCopy());
				}
			}
			pattern.add(line.toString());
		}
		recipe.addProperty("type", "minecraft:crafting_shaped");
		recipe.add("pattern", pattern);
		recipe.add("key", keys);
	}
}
