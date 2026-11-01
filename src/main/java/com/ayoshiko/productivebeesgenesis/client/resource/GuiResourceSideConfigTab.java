package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import mekanism.client.gui.GuiMekanism;
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

/** 原生侧配置旁的单一入口，独立窗口由 MEK 管理焦点、位置与重挂。 */
public final class GuiResourceSideConfigTab extends GuiWindowCreatorTab<MekanismTileContainer<?>, GuiResourceSideConfigTab> {
    private static final SelectedWindowData DATA = new SelectedWindowData(SelectedWindowData.WindowType.UNSPECIFIED);
    private final AbstractContainerScreen<?> screen;

    static {
        ((CustomWindowData) (Object) DATA).productivebeesgenesis$setCustomSaveName("window_resource_sides");
    }

    public GuiResourceSideConfigTab(IGuiWrapper gui, MekanismTileContainer<?> menu, AbstractContainerScreen<?> screen,
            int x, int y, boolean left, Supplier<GuiResourceSideConfigTab> supplier) {
        super(ResourceGuiTextures.WIDGETS, gui, menu, x, y, 26, 18, left, supplier);
        this.screen = screen;
        setTooltip(Tooltip.create(Component.translatable("resource.productivebeesgenesis.sides.title")
                .append("\n").append(Component.translatable("resource.productivebeesgenesis.sides.tab_hint"))));
    }

    @Override public void onClick(double mouseX, double mouseY, int button) {
        if (button != 0) return;
        if (gui() instanceof GuiMekanism<?> gui) {
            for (var window : gui.getWindows()) {
                if (window instanceof GuiResourceSideConfig) {
                    // 父窗口可以单独关闭；新入口必须接管已有窗口，避免覆盖单一配置会话。
                    adoptWindows(window);
                    disableTab();
                    gui.removeWindow(window);
                    gui.addWindow(window);
                    return;
                }
            }
        }
        super.onClick(mouseX, mouseY, button);
    }

    @Override protected void colorTab(GuiGraphics graphics) {
        graphics.setColor(1, 1, 1, 1);
    }

    @Override protected void drawBackgroundOverlay(GuiGraphics graphics) {
        ResourceGuiTextures.sprite(graphics, getButtonX() + (getButtonWidth() - 16) / 2,
                getButtonY() + (getButtonHeight() - 16) / 2, 112, 0, 16, 16);
    }

    protected SelectedWindowData getNextWindowData() {
        return DATA;
    }

    @Override protected GuiWindow createWindow() {
        SelectedWindowData data=getNextWindowData();
        return new GuiResourceSideConfig(gui(), (getGuiWidth() - GuiResourceSideConfig.WIDTH) / 2,
                (gui().getHeight() - GuiResourceSideConfig.HEIGHT) / 2, dataSource, screen, data);
    }
}
