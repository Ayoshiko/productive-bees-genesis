package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProduction;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;
import mekanism.api.chemical.ChemicalStack;

/** 在本地副本中编辑整组生产，确认后一次替换稳定节点，不逐字段改写世界。 */
final class DesignerProductionScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject document;
	private final String entryId;
	private final JsonObject options = new JsonObject();
	private JsonArray items = new JsonArray();
	private JsonArray resources = new JsonArray();
	private JsonObject fluid;
	private final String initial;
	private String recoveryId = java.util.UUID.randomUUID().toString();
	private Runnable recoveryResolved = () -> {};
	private boolean discarded;

	DesignerProductionScreen(Screen parent, JsonObject document, String entryId) {
		super(tr("production"));
		this.parent = parent;
		editorParent = parent;
		this.document = document;
		this.entryId = entryId;
		options.addProperty("route", "centrifuge");
		options.addProperty("time", 200);
		JsonObject nodes = document.has("production_nodes") ? document.getAsJsonObject("production_nodes") : new JsonObject();
		if (nodes.has(entryId)) {
			JsonObject node = nodes.getAsJsonObject(entryId);
			options.add("route", node.get("route"));
			for (var value : node.getAsJsonArray("recipe_ids")) {
				JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(value.getAsString());
				if (recipe == null) {
					continue;
				}
				String type = recipe.get("type").getAsString();
				if (type.startsWith("productivebeesgenesis:")) {
					resources = recipe.getAsJsonArray("outputs").deepCopy();
				} else if (!value.getAsString().endsWith("/comb")) {
					items = recipe.getAsJsonArray(type.endsWith("centrifuge") ? "outputs" : "results").deepCopy();
					fluid = recipe.has("fluid") ? recipe.getAsJsonObject("fluid").deepCopy() : null;
				}
				if (recipe.has("processingTime") || recipe.has("processing_time")) {
					options.add("time", recipe.has("processingTime") ? recipe.get("processingTime") : recipe.get("processing_time"));
				}
			}
		}
		initial = snapshot();
	}

	@Override
	protected void init() {
		begin();
		scope("production_nodes/" + entryId);
		anchor("");
		anchor("route");
		section("production_route");
		rows.add(new Row("route", (x, y, w) -> selection(x, y, w, options.get("route").getAsString(),
				new String[]{"centrifuge", "direct"}, value -> {
			options.addProperty("route", value);
		}), 20, true));
		boolean centrifuge = options.get("route").getAsString().equals("centrifuge");
		note("flow", tr(centrifuge ? "flow_centrifuge" : "flow_direct"));
		if (centrifuge) {
			number("processing_time", options, "time", "200");
		}
		section("item_outputs");
		note("", tr("output_actions_help"));
		for (int index = 0; index < items.size(); index++) {
			scope("production_nodes/" + entryId + "/items/" + index);
			anchor("");
			JsonObject output = items.get(index).getAsJsonObject();
			ItemStack display = DesignerStackButton.decode(output.get("item"));
			outputHeading(Component.translatable("designer.output_numbered", index + 1, display.isEmpty() ? tr("output") : display.getHoverName()), items, output);
			DesignerOutputScreen.addFields(this, output, false, items);
		}
		scope("production_nodes/" + entryId);
		rows.add(new Row("", (x, y, w) -> button(x, y, Math.min(220, w), tr("add_material_output"), () -> minecraft.setScreen(
				new BeeDesignerItemPicker(this, centrifuge ? new String[]{"item", "fluid", "chemical"} : new String[]{"item", "chemical"}, this::addMaterial)))));
		section("fluid_output");
		anchor("fluid");
		if (centrifuge) {
			rows.add(new Row("fluid", (x, y, w) -> {
				Runnable choose = () -> minecraft.setScreen(DesignerResourcePicker.open(this, "fluid", selected -> setFluid(selected, 1000)));
				if (fluid == null) {
					button(x, y, w - 26, tr("pick_fluid"), choose);
				} else {
					addRenderableWidget(new DesignerResourceButton(x, y, w - 26, fluid, true, choose));
				}
				ghost(x, y, w - 26, value -> value instanceof FluidStack, value -> setFluid(selection(value), 1000));
				button(x + w - 22, y, 22, Component.literal("×"), () -> {
					fluid = null;
					rebuild();
				}).active = fluid != null;
			}));
			if (fluid != null) {
				number("fluid_amount", fluid, "amount", "1000");
			}
			note("limit", tr("one_pb_fluid"));
		} else {
			note("fluid", tr("direct_fluid_help"));
		}
		section("resource_outputs");
		for (int index = 0; index < resources.size(); index++) {
			scope("production_nodes/" + entryId + "/resources/" + index);
			anchor("");
			JsonObject output = resources.get(index).getAsJsonObject();
			outputHeading(Component.translatable("designer.output_numbered", index + 1, option(BeeDesignerDocument.text(output, "kind", "output"))), resources, output);
			rows.add(new Row("output", (x, y, w) -> {
				addRenderableWidget(new DesignerResourceButton(x, y, w, output, false,
						() -> minecraft.setScreen(new DesignerOutputScreen(this, output, true))));
			}));
			DesignerResourceFields.add(this, output, false, false);
		}
		scope("production_nodes/" + entryId);
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("add_resource_output"), () -> {
			if (resources.size() < 16) {
				JsonObject output = new JsonObject();
				output.addProperty("kind", "energy");
				output.addProperty("energy_type", "FE");
				output.addProperty("amount", "1000");
				output.addProperty("chance", 1);
				resources.add(output);
				rebuild();
			}
		})));
		note("combination", tr("automatic_combination"));
		finishRows();
		primaryButton(left, footerY(), 110, tr("save_production"), this::save);
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void setFluid(JsonObject selected, int amount) {
		fluid = BeeDesignerSelections.fluidOutput(selected, minecraft.level.registryAccess(), amount).getAsJsonObject();
	}

	private void addMaterial(Object material) {
		if (material instanceof ItemStack stack) {
			if (items.size() >= 16) {
				throw new IllegalArgumentException("designer.error.output_limit");
			}
			items.add(BeeDesignerSelections.itemOutput(selection(stack), minecraft.level.registryAccess(), 1, 1, 1));
		} else if (material instanceof FluidStack) {
			if (!options.get("route").getAsString().equals("centrifuge")) {
				throw new IllegalArgumentException("designer.error.route_fluid");
			}
			setFluid(selection(material), 1000);
		} else if (material instanceof ChemicalStack) {
			if (resources.size() >= 16) {
				throw new IllegalArgumentException("designer.error.output_limit");
			}
			resources.add(BeeDesignerSelections.chemicalOutput(selection(material), "1000", 1));
		} else {
			throw new IllegalArgumentException("Unsupported material output");
		}
	}

	private void save() {
		if (commitChanges()) {
			minecraft.setScreen(parent);
		}
	}

	private boolean commitChanges() {
		try {
			JsonObject copy = document.deepCopy();
			boolean centrifuge = options.get("route").getAsString().equals("centrifuge");
			if (!centrifuge && fluid != null) {
				throw new IllegalArgumentException("designer.error.route_fluid");
			}
			int time = options.get("time").getAsBigDecimal().intValueExact();
			BeeDesignerProduction.replace(copy, entryId, centrifuge, items, fluid, resources, time);
			for (var value : copy.getAsJsonObject("production_nodes").getAsJsonObject(entryId).getAsJsonArray("recipe_ids")) {
				Recipe.CODEC.parse(minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
						copy.getAsJsonObject("recipes").get(value.getAsString())).getOrThrow();
			}
			document.add("recipes", copy.get("recipes"));
			document.add("production_nodes", copy.get("production_nodes"));
			document.add("disabled_recipes", copy.get("disabled_recipes"));
			recoveryResolved.run();
			return true;
		} catch (RuntimeException exception) {
			status = exception.getMessage();
			return false;
		}
	}

	@Override
	protected boolean prepareWindowClose() {
		if (initial.equals(snapshot())) {
			recoveryResolved.run();
			return true;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			if (commitChanges()) {
				continueWindowClose();
			}
		}), () -> {
			discardChanges();
			continueWindowClose();
		}, () -> resumePage(() -> {})));
		return false;
	}

	@Override
	public void onClose() {
		if (initial.equals(snapshot())) {
			recoveryResolved.run();
			minecraft.setScreen(parent);
			return;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			if (confirmed) {
				discardChanges();
			}
			minecraft.setScreen(confirmed ? parent : this);
		}, tr("discard_node"), tr("discard_node_help")));
	}

	private String snapshot() {
		return options + "\n" + items + "\n" + resources + "\n" + fluid;
	}

	@Override
	protected String pendingDraftState() {
		String value = snapshot();
		return discarded || initial.equals(value) ? "" : value;
	}

	private void discardChanges() {
		discarded = true;
		recoveryResolved.run();
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		if (discarded) {
			return;
		}
		JsonObject recovery = new JsonObject();
		recovery.addProperty("kind", "production");
		recovery.addProperty("node", entryId);
		recovery.add("options", options.deepCopy());
		recovery.add("items", items.deepCopy());
		recovery.add("resources", resources.deepCopy());
		if (fluid != null) {
			recovery.add("fluid", fluid.deepCopy());
		}
		snapshot.recover(recovery, recoveryId);
		snapshot.bind(options, recovery.get("options"));
		snapshot.bind(items, recovery.get("items"));
		snapshot.bind(resources, recovery.get("resources"));
		snapshot.bind(fluid, recovery.get("fluid"));
	}

	JsonObject recoveryTarget(JsonArray path) {
		JsonObject values = new JsonObject();
		values.add("options", options);
		values.add("items", items);
		values.add("resources", resources);
		if (fluid != null) {
			values.add("fluid", fluid);
		}
		return DesignerDraftSnapshot.target(values, path);
	}

	void recovery(String id, Runnable resolved) {
		recoveryId = id;
		recoveryResolved = resolved;
	}

	void restoreDraft(JsonObject recovery) {
		options.keySet().clear();
		recovery.getAsJsonObject("options").entrySet().forEach(entry -> options.add(entry.getKey(), entry.getValue().deepCopy()));
		items = recovery.getAsJsonArray("items").deepCopy();
		resources = recovery.getAsJsonArray("resources").deepCopy();
		fluid = recovery.has("fluid") ? recovery.getAsJsonObject("fluid").deepCopy() : null;
		status = "designer.recovered_json_help";
	}
}
