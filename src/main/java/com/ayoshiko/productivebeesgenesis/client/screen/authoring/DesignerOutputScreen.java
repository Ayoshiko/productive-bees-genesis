package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

final class DesignerOutputScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject output;
	private final boolean resource;

	DesignerOutputScreen(Screen parent, JsonObject output, boolean resource) {
		super(tr("output"));
		this.parent = parent;
		editorParent = parent;
		this.output = output;
		this.resource = resource;
	}

	@Override
	protected void init() {
		begin();
		addFields(this, output, resource);
		finishRows();
		button(left, footerY(), 100, Component.translatable("gui.done"), this::onClose);
	}

	static void addFields(DesignerFormScreen screen, JsonObject output, boolean resource) {
		addFields(screen, output, resource, null);
	}

	static void addFields(DesignerFormScreen screen, JsonObject output, boolean resource, JsonArray outputs) {
		if (resource) {
			DesignerResourceFields.add(screen, output, false);
		} else {
			var mc = net.minecraft.client.Minecraft.getInstance();
			screen.anchor("item");
			screen.rows.add(new Row("output_item", (x, y, w) -> {
				Consumer<ItemStack> chosen = stack -> output.add("item", BeeDesignerSelections.ingredient(
						BeeDesignerSelections.item(stack, mc.level.registryAccess()), mc.level.registryAccess(), true));
				if (outputs == null) {
					screen.virtualItem(x, y, w, DesignerStackButton.decode(output.get("item")), tr("pick_item").getString(), chosen);
				} else {
					screen.virtualOutputItem(x, y, w, DesignerStackButton.decode(output.get("item")), tr("pick_item").getString(), chosen, outputs, output);
				}
			}));
			screen.number("minimum", output, "min", "1");
			screen.number("maximum", output, "max", "1");
			screen.number("chance", output, "chance", "1");
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
