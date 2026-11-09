package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DesignerRecipeScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject recipe;
	private final String id;

	DesignerRecipeScreen(Screen parent, JsonObject recipe, String id) {
		super(option(recipe.get("type").getAsString()));
		this.parent = parent;
		editorParent = parent;
		this.recipe = recipe;
		this.id = id;
	}

	@Override
	protected void init() {
		begin();
		new DesignerRecipeFields(this, recipe, id).add();
		finishRows();
		button(left, footerY(), 100, Component.translatable("gui.done"), this::onClose);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
