package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;

public final class GuiPinButton extends MekanismButton {
    public static final int WIDTH = 12;
    public GuiPinButton(IGuiWrapper gui, int x, int y, PinnableGuiWindow window) {
        super(gui, x, y, WIDTH, 12, Component.translatable("productivebeesgenesis.window.pin"), window::togglePinned, null);
    }
}
