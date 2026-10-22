package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class RecipeTypeScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject document;
	private final boolean production;

	RecipeTypeScreen(Screen parent, JsonObject document, boolean production) {
		super(tr("add_recipe"));
		this.parent = parent;
		editorParent = parent;
		this.document = document;
		this.production = production;
	}

	@Override
	protected void init() {
		begin();
		for (String type : new String[]{"productivebees:advanced_beehive", "productivebees:centrifuge",
				"productivebeesgenesis:resource_production", "productivebeesgenesis:resource_centrifuging",
				"productivebees:bee_breeding", "productivebees:bee_conversion", "productivebees:bee_spawning",
				"productivebees:bee_fishing", "minecraft:crafting_shapeless", "minecraft:crafting_shaped"}) {
			boolean isProduction = type.endsWith("advanced_beehive") || type.endsWith("centrifuge") || type.startsWith("productivebeesgenesis:");
			if (isProduction != production) {
				continue;
			}
			rows.add(new Row("", (x, y, w) -> button(left, y, contentWidth, option(type), () -> {
				String id = BeeDesignerDocument.addRecipe(document, type);
				minecraft.setScreen(new DesignerRecipeScreen(parent, document.getAsJsonObject("recipes").getAsJsonObject(id), id));
			})));
		}
		finishRows();
		button(left, footerY(), 100, Component.translatable("gui.cancel"), this::onClose);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
