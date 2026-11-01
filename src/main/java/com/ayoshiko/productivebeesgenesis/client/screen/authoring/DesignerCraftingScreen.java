package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

/** 3×3 虚拟配方网格；位置、标签和完整组件只在确认后写入原配方。 */
final class DesignerCraftingScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject recipe;
	private final JsonElement[] slots = new JsonElement[9];
	private final JsonObject options = new JsonObject();
	private final String initial;

	DesignerCraftingScreen(Screen parent, JsonObject recipe) {
		super(tr("crafting_grid"));
		this.parent = parent;
		editorParent = parent;
		this.recipe = recipe;
		boolean shaped = recipe.get("type").getAsString().endsWith("shaped");
		options.addProperty("mode", shaped ? "shaped" : "shapeless");
		if (shaped) {
			JsonArray pattern = recipe.getAsJsonArray("pattern");
			JsonObject keys = recipe.getAsJsonObject("key");
			for (int row = 0; row < pattern.size(); row++) {
				String line = pattern.get(row).getAsString();
				for (int column = 0; column < line.length(); column++) {
					JsonElement ingredient = keys.get(Character.toString(line.charAt(column)));
					slots[row * 3 + column] = ingredient == null ? null : ingredient.deepCopy();
				}
			}
		} else {
			JsonArray ingredients = recipe.getAsJsonArray("ingredients");
			for (int index = 0; index < Math.min(9, ingredients.size()); index++) {
				slots[index] = ingredients.get(index).deepCopy();
			}
		}
		initial = draftState();
	}

	@Override
	protected void init() {
		begin();
		choice("crafting_type", options, "mode", "shaped", "shaped", "shapeless");
		int cell = Math.max(24, Math.min(40, (editorBottom() - contentTop - 50) / 3));
		for (int row = 0; row < 3; row++) {
			int selectedRow = row;
			rows.add(new Row("", (x, y, w) -> {
				int size = Math.min(cell, (w - 8) / 3);
				int gridX = x + (w - size * 3 - 8) / 2;
				for (int column = 0; column < 3; column++) {
					int index = selectedRow * 3 + column;
					int sx = gridX + column * (size + 4);
					addRenderableWidget(new DesignerStackButton(sx, y, size, DesignerStackButton.decode(slots[index]),
							tr("empty_slot").getString(), () -> minecraft.setScreen(new DesignerIngredientScreen(this, slots[index],
									value -> slots[index] = value))).square());
					ghost(sx, y, size, size, value -> value instanceof ItemStack, value -> slots[index] =
							com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections.ingredient(selection(value), minecraft.level.registryAccess(), true));
				}
			}, cell + 4));
		}
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("clear_grid"), () -> {
			java.util.Arrays.fill(slots, null);
			rebuild();
		})));
		note("grid_help", tr("grid_help_text"));
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.done"), this::save);
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void save() {
		if (commitChanges()) {
			minecraft.setScreen(parent);
		}
	}

	private boolean commitChanges() {
		try {
			JsonObject copy = draftRecipe();
			com.ayoshiko.productivebeesgenesis.port.ForgeRecipeCodecs.CODEC.parse(JsonOps.INSTANCE, copy).getOrThrow(false, message -> {});
			recipe.keySet().clear();
			copy.entrySet().forEach(entry -> recipe.add(entry.getKey(), entry.getValue()));
			return true;
		} catch (RuntimeException exception) {
			status = exception.getMessage();
			return false;
		}
	}

	private JsonObject draftRecipe() {
			JsonObject copy = recipe.deepCopy();
			copy.remove("ingredients");
			copy.remove("key");
			copy.remove("pattern");
			if (options.get("mode").getAsString().equals("shaped")) {
				JsonObject keys = new JsonObject();
				JsonArray pattern = new JsonArray();
				for (int row = 0; row < 3; row++) {
					StringBuilder line = new StringBuilder();
					for (int column = 0; column < 3; column++) {
						int index = row * 3 + column;
						char symbol = (char) ('A' + index);
						line.append(slots[index] == null ? ' ' : symbol);
						if (slots[index] != null) {
							keys.add(Character.toString(symbol), slots[index].deepCopy());
						}
					}
					pattern.add(line.toString());
				}
				copy.addProperty("type", "minecraft:crafting_shaped");
				copy.add("pattern", pattern);
				copy.add("key", keys);
			} else {
				JsonArray ingredients = new JsonArray();
				for (JsonElement value : slots) {
					if (value != null) {
						ingredients.add(value.deepCopy());
					}
				}
				copy.addProperty("type", "minecraft:crafting_shapeless");
				copy.add("ingredients", ingredients);
			}
			return copy;
	}

	private String draftState() {
		return options + "\n" + java.util.Arrays.toString(slots);
	}

	@Override
	protected String pendingDraftState() {
		String state = draftState();
		return initial.equals(state) ? "" : state;
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		JsonObject copy = draftRecipe();
		snapshot.replace(recipe, copy);
	}

	@Override
	protected boolean prepareWindowClose() {
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			if (commitChanges()) {
				continueWindowClose();
			}
		}), this::continueWindowClose, () -> resumePage(() -> {})));
		return false;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
