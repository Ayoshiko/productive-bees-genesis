package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class DesignerMachineMaterialScreen extends DesignerFormScreen {
	private final Screen parent;
	private final BeeDesignerMachineRecipes.Entry entry;

	DesignerMachineMaterialScreen(Screen parent, BeeDesignerMachineRecipes.Entry entry) {
		super(tr(entry.input() ? "machine_input" : "machine_output"));
		this.parent = parent;
		editorParent = parent;
		this.entry = entry;
	}

	@Override
	protected void init() {
		begin();
		section("machine_material");
		if (!BeeDesignerMachineRecipes.supported(entry.host(), entry.kind())) {
			note("", tr("machine_unknown_content"));
			rows.add(new Row("advanced", (x, y, w) -> button(x, y, w, tr("edit_json"), () ->
					minecraft.setScreen(new DesignerJsonScreen(this, entry.node(), tr("machine_material").getString())))));
			finishRows();
			button(left, footerY(), 72, Component.translatable("gui.done"), this::onClose);
			return;
		}
		if (!entry.kind().equals("energy")) {
			rows.add(new Row("material", (x, y, w) -> {
				addRenderableWidget(new DesignerMachineEntryButton(x, y, w, entry, this::choose));
				ghost(x, y, w, value -> DesignerMachineRecipeScreen.accepts(entry.kind(), value), value -> replace(selection(value)));
			}));
		}
		number("machine_amount", entry.value(), entry.amountKey(), "1", DesignerNumberRange.forMachine(entry));
		number("chance", entry.node(), entry.chanceKey(), "1");
		if (entry.kind().equals("item")) {
			if (entry.input()) {
				rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("machine_item_or_tag"), () -> {
					var ingredient = entry.host() == BeeDesignerMachineRecipes.Host.MMR ? entry.value().get("ingredient") : entry.value();
					minecraft.setScreen(new DesignerIngredientScreen(this, ingredient, value -> {
						if (value == null || !value.isJsonObject()) {
							throw new IllegalArgumentException("designer.error.machine_output_item");
						}
						BeeDesignerMachineRecipes.replaceMaterial(entry, value.getAsJsonObject());
					}));
				})));
			}
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("choose_bee_egg"), () -> minecraft.setScreen(new DesignerBeePicker(this, id -> {
				JsonObject selected = BeeDesignerSelections.item(BeeDesignerEggs.egg(id), minecraft.level.registryAccess());
				replace(selected);
			})))));
			if (!entry.input() && BeeDesignerEggs.bee(BeeDesignerMachineRecipes.itemIdentity(entry)) != null) {
				rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("egg_components"), () -> {
					JsonObject egg = BeeDesignerMachineRecipes.itemIdentity(entry);
					egg.add("count", entry.value().has(entry.amountKey())
							? entry.value().get(entry.amountKey()).deepCopy() : new com.google.gson.JsonPrimitive(1));
					minecraft.setScreen(new DesignerEggScreen(this, egg, value -> {
						BeeDesignerMachineRecipes.replaceMaterial(entry, BeeDesignerMachineRecipes.itemMaterial(value));
						entry.value().add(entry.amountKey(), value.has("count") ? value.get("count").deepCopy() : new com.google.gson.JsonPrimitive(1));
					}));
				})));
			}
		}
		if (entry.host() == BeeDesignerMachineRecipes.Host.MBD2) {
			section("machine_content_options");
			toggle("machine_per_tick", entry.node(), "perTick", false);
			number("machine_tier_chance", entry.node(), "tierChanceBoost", "0");
			field("machine_slot_name", entry.node(), "slotName", "");
			field("machine_ui_name", entry.node(), "uiName", "");
		}
		if (entry.host() == BeeDesignerMachineRecipes.Host.MMR) {
			section("machine_position");
			JsonObject point = BeeDesignerDocument.object(entry.node(), "position");
			number("machine_x", point, "x", "0");
			number("machine_y", point, "y", "0");
			note("", tr("machine_position_help"));
		}
		rows.add(new Row("advanced", (x, y, w) -> button(x, y, w, tr("edit_json"), () ->
				minecraft.setScreen(new DesignerJsonScreen(this, entry.node(), tr("machine_material").getString())))));
		finishRows();
		button(left, footerY(), 72, Component.translatable("gui.done"), this::onClose);
	}

	private void choose() {
		if (entry.kind().equals("item")) {
			minecraft.setScreen(new BeeDesignerItemPicker(this, stack -> replace(BeeDesignerSelections.item(stack, minecraft.level.registryAccess()))));
		} else {
			minecraft.setScreen(DesignerResourcePicker.open(this, entry.kind(), this::replace));
		}
	}

	private void replace(JsonObject selection) {
		BeeDesignerMachineRecipes.replaceMaterial(entry, BeeDesignerMachineRecipes.material(selection, minecraft.level.registryAccess()));
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
