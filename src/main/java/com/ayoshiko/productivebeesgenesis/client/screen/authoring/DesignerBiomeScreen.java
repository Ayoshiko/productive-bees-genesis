package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class DesignerBiomeScreen extends DesignerFormScreen {
	private final Screen parent;
	private final Consumer<JsonElement> saved;
	private final boolean tags;
	private final boolean single;
	private final boolean allowEmpty;
	private final Set<String> selected = new LinkedHashSet<>();
	private final List<List<String>> history = new ArrayList<>();
	private int historyCursor = -1;
	private JsonObject draftOwner;
	private String draftField;
	private final JsonElement originalValue;
	private final String initialState;

	DesignerBiomeScreen(Screen parent, JsonObject recipe) {
		this(parent, recipe.get("biomes"), true, false, false, value -> recipe.add("biomes", value));
		draftOwner = recipe;
		draftField = "biomes";
	}

	DesignerBiomeScreen(Screen parent, JsonElement initial, boolean tags, boolean single, boolean allowEmpty, Consumer<JsonElement> saved) {
		super(tr("biomes"));
		this.parent = parent;
		editorParent = parent;
		this.saved = saved;
		this.tags = tags;
		this.single = single;
		this.allowEmpty = allowEmpty;
		originalValue = initial;
		if (initial != null) {
			if (initial.isJsonArray()) {
				initial.getAsJsonArray().forEach(entry -> selected.add(entry.getAsString()));
			} else {
				selected.add(initial.getAsString());
			}
		}
		captureSelection();
		initialState = selected.toString();
	}

	@Override
	protected String pendingDraftState() {
		String state = selected.toString();
		return initialState.equals(state) ? "" : state;
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		JsonElement value;
		if (!selected.isEmpty() && (single || selected.iterator().next().startsWith("#"))) {
			value = new JsonPrimitive(selected.iterator().next());
		} else {
			JsonArray values = new JsonArray();
			selected.forEach(values::add);
			value = values;
		}
		JsonObject owner = draftOwner == null ? null : snapshot.target(draftOwner);
		if (owner != null) {
			owner.add(draftField, value);
		} else {
			snapshot.replaceValue(originalValue, value);
		}
	}

	@Override
	protected void init() {
		begin();
		button(left, contentTop, 24, Component.literal("↶"), () -> restoreSelection(-1))
				.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("undo")));
		button(left + 30, contentTop, 24, Component.literal("↷"), () -> restoreSelection(1))
				.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("redo")));
		contentTop += 28;
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("add_biome"), () -> minecraft.setScreen(
				DesignerResourcePicker.open(this, "biome", value -> {
					if (single) {
						selected.clear();
					}
					selected.removeIf(id -> id.startsWith("#"));
					selected.add(value.get("id").getAsString());
					captureSelection();
				})))));
		if (tags) {
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("use_biome_tag"), () -> minecraft.setScreen(
				DesignerResourcePicker.open(this, "biome_tag", value -> {
					selected.clear();
					selected.add("#" + value.get("id").getAsString());
					captureSelection();
				})))));
		}
		for (String id : selected) {
			rows.add(new Row("", (x, y, w) -> button(x, y, w, Component.literal(id + "  ×"), () -> {
				selected.remove(id);
				captureSelection();
				rebuild();
			})));
		}
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.done"), () -> {
			if (selected.isEmpty() && !allowEmpty) {
				status = "designer.error.empty_biomes";
				return;
			}
			if (!selected.isEmpty() && (single || selected.iterator().next().startsWith("#"))) {
				saved.accept(new JsonPrimitive(selected.iterator().next()));
			} else {
				JsonArray values = new JsonArray();
				selected.forEach(values::add);
				saved.accept(values);
			}
			minecraft.setScreen(parent);
		});
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void captureSelection() {
		List<String> value = List.copyOf(selected);
		if (historyCursor >= 0 && history.get(historyCursor).equals(value)) {
			return;
		}
		while (history.size() > historyCursor + 1) {
			history.remove(history.size() - 1);
		}
		history.add(value);
		if (history.size() > 64) {
			history.remove(0);
		}
		historyCursor = history.size() - 1;
	}

	private void restoreSelection(int direction) {
		int next = historyCursor + direction;
		if (next < 0 || next >= history.size()) {
			return;
		}
		historyCursor = next;
		selected.clear();
		selected.addAll(history.get(next));
		status = "";
		rebuild();
	}

	@Override
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (isLeaving()) {
			return true;
		}
		boolean control = hasControlDown() || (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shift = hasShiftDown() || (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
		if (control && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z || key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y)) {
			restoreSelection(key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y || shift ? 1 : -1);
			return true;
		}
		return super.keyContent(key, scan, modifiers);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	protected boolean prepareWindowClose() {
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			if (selected.isEmpty() && !allowEmpty) {
				status = "designer.error.empty_biomes";
				return;
			}
			if (!selected.isEmpty() && (single || selected.iterator().next().startsWith("#"))) {
				saved.accept(new JsonPrimitive(selected.iterator().next()));
			} else {
				JsonArray values = new JsonArray();
				selected.forEach(values::add);
				saved.accept(values);
			}
			continueWindowClose();
		}), this::continueWindowClose, () -> resumePage(() -> {})));
		return false;
	}
}
