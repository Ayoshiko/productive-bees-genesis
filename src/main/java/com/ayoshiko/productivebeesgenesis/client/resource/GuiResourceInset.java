package com.ayoshiko.productivebeesgenesis.client.resource;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import net.minecraft.client.gui.GuiGraphics;

/** 窗口材质已包含底板；此控件只提供对应区域的悬停信息。 */
final class GuiResourceInset extends GuiElement {
    GuiResourceInset(IGuiWrapper gui, int x, int y, int width, int height) { super(gui, x, y, width, height); }
    @Override public void drawBackground(GuiGraphics graphics, int mx, int my, float partial) {
        // 静态底板由整窗材质一次绘制，避免控件默认白边重叠。
    }
}
