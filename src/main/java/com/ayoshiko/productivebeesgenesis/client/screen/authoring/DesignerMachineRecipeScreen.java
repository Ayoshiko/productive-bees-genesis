package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.google.gson.JsonObject;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

final class DesignerMachineRecipeScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject recipe;
	private final JsonObject source;
	private final String id;

	DesignerMachineRecipeScreen(Screen parent, JsonObject recipe, String id, JsonObject source) {
		super(tr("machine_recipe"));
		this.parent = parent;
		editorParent = parent;
		this.recipe = recipe;
		this.id = id;
		this.source = source == null ? new JsonObject() : source;
	}

	@Override
	protected void init() {
		begin();
		scope("recipes/" + id);
		anchor("type");
		anchor("machine");
		var host = BeeDesignerMachineRecipes.host(recipe);
		if (host == null) {
			status = "designer.error.machine_type";
			return;
		}
		section("machine_settings");
		note("", Component.literal(id));
		note("", DesignerMachinePicker.machineName(source));
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("view_jei"),
				() -> DesignerOverviewEditing.viewJei(this, id, recipe))));
		if (parent instanceof BeeDesignerScreen editor) {
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("machine_replace"), () ->
					minecraft.setScreen(new DesignerMachinePicker(editor, editor.editingDocument(), id)))));
		}
		note("", Component.literal(host.name() + " · " + BeeDesignerDocument.text(source, "host_version",
				BeeDesignerDocument.text(source, "version", "")) + " · " + BeeDesignerDocument.text(recipe, "machine", recipe.get("type").getAsString())));
		number("machine_processing_time", recipe, host == BeeDesignerMachineRecipes.Host.MMR ? "time" : "duration", "20");
		if (host == BeeDesignerMachineRecipes.Host.MI) {
			number("machine_eu", recipe, "eu", "1");
		} else {
			number("machine_priority", recipe, "priority", "0");
		}
		materials(true);
		materials(false);
		if (host == BeeDesignerMachineRecipes.Host.MMR) {
			section("machine_canvas");
			number("machine_width", recipe, "width", "256");
			number("machine_height", recipe, "height", "256");
			toggle("machine_render_progress", recipe, BeeDesignerMachineRecipes.fieldName(recipe, "renderProgress"), true);
			JsonObject progress = BeeDesignerDocument.object(recipe, BeeDesignerMachineRecipes.fieldName(recipe, "progressData"));
			JsonObject position = BeeDesignerDocument.object(progress, "position");
			if (!position.has("x")) {
				position.addProperty("x", 74);
				position.addProperty("y", 8);
			}
			number("machine_progress_x", position, "x", "74", DesignerNumberRange.of(0, dimension("width") - 1, true));
			number("machine_progress_y", position, "y", "8", DesignerNumberRange.of(0, dimension("height") - 1, true));
			choice("machine_progress_direction", progress, "direction", "left", "left", "right", "up", "down");
			field("machine_progress_empty", progress, BeeDesignerMachineRecipes.fieldName(progress, "emptyTexture"), "modular_machinery_reborn:textures/gui/empty_arrow.png");
			field("machine_progress_filled", progress, BeeDesignerMachineRecipes.fieldName(progress, "filledTexture"), "modular_machinery_reborn:textures/gui/filled_arrow.png");
		}
		section("machine_source");
		if (source.has("source_file") || source.has("source_pack")) {
			note("", Component.literal(BeeDesignerDocument.text(source, "source_file", BeeDesignerDocument.text(source, "source_pack", ""))
					+ (source.has("line") ? ":" + source.get("line").getAsString() : "")));
		}
		rows.add(new Row("advanced", (x, y, w) -> button(x, y, w, tr("edit_json"), () ->
				minecraft.setScreen(new DesignerJsonScreen(this, recipe, id)))));
		rows.add(new Row("conditions", (x, y, w) -> button(x, y, w, tr("edit_conditions"), () ->
				minecraft.setScreen(new DesignerConditionsScreen(this, recipe, "neoforge:conditions")))));
		note("", tr("machine_apply_help"));
		finishRows();
		button(left, footerY(), 72, Component.translatable("gui.done"), this::onClose);
		button(left + 80, footerY(), 100, tr("machine_check_structure"), () -> {
			try {
				BeeDesignerMachineRecipes.validateStructure(recipe, minecraft.level.registryAccess());
				status = "designer.machine_structure_valid";
			} catch (RuntimeException failure) {
				status = failure.getMessage();
			}
		});
	}

	private void materials(boolean input) {
		section(input ? "machine_inputs" : "machine_outputs");
		for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
			if (entry.input() != input) {
				continue;
			}
			String path = entry.field() + "/" + entry.index();
			if (entry.host() == BeeDesignerMachineRecipes.Host.MBD2) {
				path = (entry.input() ? "inputs/" : "outputs/") + path;
			}
			scope("recipes/" + id + "/" + path);
			anchor("");
			rows.add(new Row("", (x, y, w) -> {
				addRenderableWidget(new DesignerMachineEntryButton(x, y, w - 70, entry, () ->
						minecraft.setScreen(new DesignerMachineMaterialScreen(this, entry))));
				ghost(x, y, w - 70, value -> accepts(entry.kind(), value), value -> {
					BeeDesignerMachineRecipes.replaceMaterial(entry, BeeDesignerMachineRecipes.material(selection(value), minecraft.level.registryAccess()));
					rebuild();
				});
				button(x + w - 66, y, 20, Component.literal("↑"), () -> {
					entry.move(-1);
					rebuild();
				}).active = entry.index() > 0;
				button(x + w - 44, y, 20, Component.literal("↓"), () -> {
					entry.move(1);
					rebuild();
				}).active = entry.index() + 1 < entry.siblings().size();
				button(x + w - 22, y, 20, Component.literal("×"), () -> {
					entry.remove();
					rebuild();
				});
			}));
			if (BeeDesignerMachineRecipes.supported(entry.host(), entry.kind())) {
				number("machine_amount", entry.value(), entry.amountKey(), "1", DesignerNumberRange.forMachine(entry));
				number("chance", entry.node(), entry.chanceKey(), "1");
				if (entry.host() == BeeDesignerMachineRecipes.Host.MBD2) {
					toggle("machine_per_tick", entry.node(), "perTick", false);
				}
			}
		}
		scope("recipes/" + id);
		rows.add(new Row("", (x, y, w) -> {
			String[] kinds = List.of("item", "fluid", "chemical", "energy").stream().filter(kind ->
					BeeDesignerMachineRecipes.supported(BeeDesignerMachineRecipes.host(recipe), kind)
							&& (!source.has(kind + (input ? "_input" : "_output")) || source.get(kind + (input ? "_input" : "_output")).getAsBoolean()))
					.toArray(String[]::new);
			int cell = (w - (kinds.length - 1) * 4) / Math.max(1, kinds.length);
			for (int index = 0; index < kinds.length; index++) {
				String kind = kinds[index];
				button(x + index * (cell + 4), y, cell, Component.literal("+ ").append(option(kind)), () -> add(kind, input));
			}
		}));
	}

	private void add(String kind, boolean input) {
		if (kind.equals("energy")) {
			JsonObject energy = new JsonObject();
			energy.addProperty("amount", 1);
			BeeDesignerMachineRecipes.append(recipe, kind, input, energy);
			rebuild();
			return;
		}
		if (kind.equals("item")) {
			minecraft.setScreen(new BeeDesignerItemPicker(this, stack -> append(BeeDesignerSelections.item(stack, minecraft.level.registryAccess()), input)));
		} else {
			minecraft.setScreen(DesignerResourcePicker.open(this, kind, selected -> append(selected, input)));
		}
	}

	private void append(JsonObject selected, boolean input) {
		BeeDesignerMachineRecipes.append(recipe, selected.get("kind").getAsString(), input,
				BeeDesignerMachineRecipes.material(selected, minecraft.level.registryAccess()));
	}

	static boolean accepts(String kind, Object value) {
		return kind.equals("item") && value instanceof ItemStack || kind.equals("fluid") && value instanceof FluidStack
				|| kind.equals("chemical") && value instanceof ChemicalStack;
	}

	private int dimension(String key) {
		try {
			return BeeDesignerMachineRecipes.integer(recipe, key, 256);
		} catch (IllegalArgumentException failure) {
			return 256;
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
