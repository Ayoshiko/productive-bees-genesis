package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.tab.GuiConfigTypeTab;
import mekanism.client.gui.element.window.GuiSideConfiguration;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 扩展资源的选择与详情窗口；化学品和 FE 在原生侧配置窗口中配置。 */
public final class GuiResourceSideConfig extends GuiWindow {
    public static final int WIDTH = 156;
    public static final int HEIGHT = 158;
    private final AbstractContainerScreen<?> screen;
    private final List<ResourceNativeType> types = new ArrayList<>();
    private final List<ItemStack> icons = new ArrayList<>();
    private final List<GuiElement> choices = new ArrayList<>();
    private final int selectionHeight;
    private final GuiResourceSideConfigPage page;
    private final MekanismButton back;
    private boolean closed;
    private int selected = -1;

    public GuiResourceSideConfig(IGuiWrapper gui, int x, int y, MekanismTileContainer<?> menu,
            AbstractContainerScreen<?> screen, SelectedWindowData data) {
        super(gui, x, y, WIDTH, HEIGHT, data);
        this.screen = screen;
        interactionStrategy = InteractionStrategy.ALL;
        for (var type : ResourceNativeType.values()) {
            if (type == ResourceNativeType.ENERGY || type == ResourceNativeType.CHEMICAL || !type.available()) continue;
            var icon = ResourceSideIcons.icon(type);
            if (!icon.isEmpty()) {
                types.add(type);
                icons.add(icon);
            }
        }
        selectionHeight = 68 + Math.max(1, (types.size() + 2) / 3) * 30;
        for (int choice = 0; choice < types.size(); choice++) {
            final int index = choice;
            int columns = Math.min(3, types.size());
            int rowCount = Math.min(3, types.size() - choice / 3 * 3);
            int rowWidth = rowCount * 30 - 4;
            var button = addChild(new MekanismButton(gui, relativeX + (WIDTH - rowWidth) / 2 + choice % columns * 30,
                    relativeY + 44 + choice / 3 * 30, 26, 26, Component.empty(),
                    () -> {
                        select(index);
                        }, null) {
                @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
                    super.renderForeground(graphics, mx, my);
                    graphics.renderItem(icons.get(index), relativeX + 5, relativeY + 5);
                }
            });
            button.setTooltip(Tooltip.create(tr("type." + types.get(choice).id()).copy().append("\n").append(tr("configure"))));
            choices.add(button);
        }
        page = addChild(new GuiResourceSideConfigPage(gui, relativeX, relativeY, menu, screen,
                () -> selected < 0 ? null : types.get(selected),
                () -> gui instanceof GuiMekanism<?> owner && owner.getWindows().stream().findFirst().orElse(null) == this));
        back = addChild(new MekanismButton(gui, relativeX + 12, relativeY + 132, WIDTH - 24, 20,
                tr("back"), () -> {
                    showChoices();
                    }, null));
        applyVisibility();
    }

    /** 原生覆盖按钮按实际选中的 MEK 页签工作，包含独立的化学品页。 */
    public static TransmissionType nativeType(GuiSideConfiguration<?> owner) {
        TransmissionType first = null;
        TransmissionType selected = null;
        for (var element : owner.children()) {
            if (element instanceof GuiConfigTypeTab tab) {
                if (first == null) first = tab.getTransmissionType();
                if (!tab.visible) selected = tab.getTransmissionType();
            }
        }
        if (selected != null) return selected;
        return first == null ? TransmissionType.ITEM : first;
    }

    public void select(int choice) {
        if (closed || choice < 0 || choice >= types.size()) return;
        selected = choice;
        applyVisibility();
    }

    public void showChoices() {
        if (closed) return;
        selected = -1;
        applyVisibility();
    }

    private void applyVisibility() {
        height = selected < 0 ? selectionHeight : HEIGHT;
        int top = Math.max(0, Math.min(getY(), screen.height - height));
        if (top != getY()) move(0, top - getY());
        for (var element : choices) element.visible = selected < 0;
        page.show(selected >= 0);
        back.visible = selected >= 0;
    }

    private static Component tr(String key) {
        return Component.translatable("resource.productivebeesgenesis.sides." + key);
    }

    @Override public void close() {
        closed = true;
        super.close();
    }

    @Override public void onWindowClose() {
        closed = true;
        super.onWindowClose();
    }

    private void drawLabel(GuiGraphics graphics, Component text, int x, int y, int maxWidth) {
        float scale = Math.min(1, maxWidth / (float) Math.max(1, net.minecraft.client.Minecraft.getInstance().font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(net.minecraft.client.Minecraft.getInstance().font, text, 0, 0, ResourceGuiTextures.TEXT, false);
        graphics.pose().popPose();
    }

    @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
        super.renderForeground(graphics, mx, my);
        if (selected >= 0) return;
        drawLabel(graphics, tr("title"), relativeX + 24, relativeY + 8, WIDTH - 36);
        drawLabel(graphics, tr(types.isEmpty() ? "empty" : "choose"), relativeX + 12, relativeY + 26, WIDTH - 24);
        drawLabel(graphics, tr("native_hint"), relativeX + 12, relativeY + height - 18, WIDTH - 24);
    }
}
