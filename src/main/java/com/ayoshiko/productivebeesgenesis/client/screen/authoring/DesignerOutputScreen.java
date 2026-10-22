package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
		if (resource) {
			DesignerResourceFields.add(screen, output, false);
		} else {
			var mc = net.minecraft.client.Minecraft.getInstance();
			screen.rows.add(new Row("output_item", (x, y, w) -> screen.virtualItem(x, y, w, DesignerStackButton.decode(output.get("item")),
					tr("pick_item").getString(), stack -> output.add("item", BeeDesignerSelections.ingredient(
							BeeDesignerSelections.item(stack, mc.level.registryAccess()), mc.level.registryAccess(), true)))));
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
