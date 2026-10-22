package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

final class DesignerChoiceScreen extends DesignerFormScreen {
	private final Screen parent;
	private final String[] choices;
	private final Consumer<String> selected;
	private String input;

	DesignerChoiceScreen(Screen parent, String label, String[] choices, Consumer<String> selected) {
		super(tr(label));
		this.parent = parent;
		editorParent = parent;
		this.choices = choices.clone();
		this.selected = selected;
	}

	DesignerChoiceScreen(Screen parent, String value, Consumer<String> selected) {
		super(tr("edit_bee_id"));
		this.parent = parent;
		editorParent = parent;
		choices = null;
		this.selected = selected;
		input = value;
	}

	@Override
	protected void init() {
		begin();
		if (choices == null) {
			text("bee_id", input, value -> input = value);
			finishRows();
			primaryButton(left, footerY(), 100, Component.translatable("gui.done"), () -> {
				if (net.minecraft.resources.ResourceLocation.tryParse(input) == null) {
					status = "designer.error.invalid_bee_id";
					return;
				}
				selected.accept(input);
				minecraft.setScreen(parent);
			});
			button(left + contentWidth - 100, footerY(), 100, Component.translatable("gui.cancel"), this::onClose);
			return;
		}
		for (String value : choices) {
			rows.add(new Row("", (x, y, w) -> button(left, y, contentWidth, option(value), () -> {
				selected.accept(value);
				if (minecraft.screen == this) {
					minecraft.setScreen(parent);
				}
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
