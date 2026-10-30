package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.function.Supplier;
import mekanism.client.gui.IGuiWrapper;
import com.ayoshiko.productivebeesgenesis.client.screen.PinnableGuiWindowCreatorTab;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.resources.ResourceLocation;

public abstract class PinnableGuiWindowCreatorTab<D, E extends PinnableGuiWindowCreatorTab<D,E>> extends GuiWindowCreatorTab<D,E> {
    protected PinnableGuiWindowCreatorTab(ResourceLocation texture, IGuiWrapper gui, D source,
            int x, int y, int width, int height, boolean left, Supplier<E> supplier) {
        super(texture, gui, source, x, y, width, height, left, supplier);
    }
    @Override protected final GuiWindow createWindow() { return createWindow(getNextWindowData()); }
    protected abstract GuiWindow createWindow(SelectedWindowData data);
    protected abstract SelectedWindowData getNextWindowData();
}
