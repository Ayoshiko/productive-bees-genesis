package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;

/** Adds explicit focus retention to the Mekanism 10.4 window lifecycle. */
public class PinnableGuiWindow extends GuiWindow {
    private boolean pinned;
    public PinnableGuiWindow(IGuiWrapper gui, int x, int y, int width, int height, SelectedWindowData data) {
        super(gui, x, y, width, height, data);
    }
    public boolean isPinned() { return pinned; }
    public void togglePinned() { pinned = !pinned; }
    @Override public void onFocusLost() { if (!pinned) super.onFocusLost(); }
}
