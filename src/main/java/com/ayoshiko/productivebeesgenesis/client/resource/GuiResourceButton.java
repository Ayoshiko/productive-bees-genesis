package com.ayoshiko.productivebeesgenesis.client.resource;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 保留 MEK 的焦点、声音与点击处理，只替换本窗口的按钮材质和文字色。 */
class GuiResourceButton extends MekanismButton {
	GuiResourceButton(IGuiWrapper gui, int x, int y, int width, int height, Component text, IClickable click) {
		super(gui, x, y, width, height, text, () -> click.onClick(null,0,0), null);
	}
	@Override public void drawBackground(GuiGraphics graphics, int mx, int my, float partial) {
		ResourceGuiTextures.button(graphics, relativeX, relativeY, width, height, !active ? 2 : isMouseOver(mx, my) || isFocused() ? 1 : 0);
	}
	@Override protected int getButtonTextColor(int mx, int my) { return active ? 0xFFF5F5F5 : 0xFFABABAB; }
}
