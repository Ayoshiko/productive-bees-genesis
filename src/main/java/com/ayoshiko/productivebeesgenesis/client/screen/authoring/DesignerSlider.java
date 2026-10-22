package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

final class DesignerSlider extends AbstractSliderButton {
	private final DoubleConsumer changed;
	private final DoubleFunction<Component> label;

	DesignerSlider(int x, int y, int width, double value, DoubleFunction<Component> label, DoubleConsumer changed) {
		super(x, y, width, 20, label.apply(value), value);
		this.label = label;
		this.changed = changed;
		setTooltip(Tooltip.create(getMessage()));
	}

	@Override
	protected void updateMessage() {
		setMessage(label.apply(value));
		setTooltip(Tooltip.create(getMessage()));
	}

	@Override
	protected void applyValue() {
		changed.accept(value);
	}

	@Override
	public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		DesignerTheme.panel(graphics, "input", getX(), getY(), width, height);
		int trackY = getY() + height - 6;
		graphics.fill(getX() + 5, trackY, getX() + width - 5, trackY + 2, DesignerFormScreen.MUTED);
		int knob = getX() + 5 + (int) Math.round(value * (width - 16));
		DesignerTheme.panel(graphics, isHoveredOrFocused() ? "button_hover" : "button", knob, trackY - 2, 7, 6);
		var font = Minecraft.getInstance().font;
		graphics.drawString(font, font.plainSubstrByWidth(getMessage().getString(), width - 10), getX() + 5, getY() + 3,
				DesignerFormScreen.FOREGROUND, false);
	}
}
