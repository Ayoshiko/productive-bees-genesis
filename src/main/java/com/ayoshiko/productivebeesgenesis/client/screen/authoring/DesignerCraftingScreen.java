package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

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
	private final JsonElement[] slots;
	private final JsonObject options = new JsonObject();
	private final String initial;

	DesignerCraftingScreen(Screen parent, JsonObject recipe) {
		super(tr("crafting_grid"));
		this.parent = parent;
		editorParent = parent;
		this.recipe = recipe;
		slots = DesignerCraftingGrid.read(recipe);
		boolean shaped = recipe.get("type").getAsString().endsWith("shaped");
		options.addProperty("mode", shaped ? "shaped" : "shapeless");
		initial = draftState();
	}

	@Override
	protected boolean showsInventory() {
		return true;
	}

	@Override
	protected void init() {
		begin();
		DesignerCraftingGrid.mode(this, options.get("mode").getAsString().equals("shaped"), shaped -> {
			options.addProperty("mode", shaped ? "shaped" : "shapeless");
			rebuild();
		});
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
		if (initial.equals(draftState())) {
			return true;
		}
		try {
			JsonObject copy = draftRecipe();
			Recipe.CODEC.parse(minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), copy).getOrThrow();
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
		DesignerCraftingGrid.write(copy, slots, options.get("mode").getAsString().equals("shaped"));
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
		if (initial.equals(draftState())) {
			return;
		}
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
