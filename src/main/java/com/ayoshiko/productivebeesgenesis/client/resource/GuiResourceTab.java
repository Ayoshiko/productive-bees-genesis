package com.ayoshiko.productivebeesgenesis.client.resource;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

/** 复用 MEK 侧面 TAB、窗口关闭与重挂逻辑。 */
public final class GuiResourceTab extends GuiWindowCreatorTab<MekanismTileContainer<?>, GuiResourceTab> {
    private final AbstractContainerScreen<?> screen;
    private static final SelectedWindowData DATA = new SelectedWindowData(SelectedWindowData.WindowType.UNSPECIFIED);
    static {
        ((com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData) (Object) DATA)
                .productivebeesgenesis$setCustomSaveName("window_resource_outputs");
    }
    public GuiResourceTab(IGuiWrapper gui, MekanismTileContainer<?> menu, AbstractContainerScreen<?> screen,
            int y, Supplier<GuiResourceTab> supplier) {
        super(ResourceGuiTextures.WIDGETS, gui, menu, gui.getXSize(), y, 26, 18, false, supplier);
        this.screen = screen;
        setTooltip(Tooltip.create(Component.translatable("resource.productivebeesgenesis.panel")
                .append("\n").append(Component.translatable("resource.productivebeesgenesis.scope"))
                .append("\n").append(Component.translatable("resource.productivebeesgenesis.purpose"))));
    }
    @Override protected void colorTab(GuiGraphics graphics) { graphics.setColor(1, 1, 1, 1); }
    @Override protected void drawBackgroundOverlay(GuiGraphics graphics) {
        ResourceGuiTextures.sprite(graphics, getButtonX() + (getButtonWidth() - 16) / 2,
                getButtonY() + (getButtonHeight() - 16) / 2, 112, 0, 16, 16);
    }
    @Override protected SelectedWindowData getNextWindowData() { return DATA; }
    @Override protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiResourceWindow(gui(), (getGuiWidth() - GuiResourceWindow.WIDTH) / 2,
                (gui().getYSize() - GuiResourceWindow.HEIGHT) / 2, dataSource, screen, data);
    }
}
