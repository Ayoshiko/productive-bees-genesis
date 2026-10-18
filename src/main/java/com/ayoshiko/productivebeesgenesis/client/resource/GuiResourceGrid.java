package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.resource.*;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.scroll.GuiScrollBar;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;

/** QIO 原生 18 像素槽位；16 像素资源只留 1 像素边框，格子不参与背包取放。 */
final class GuiResourceGrid extends GuiElement {
    private static final int COLUMNS = 16, ROWS = 4, CELL = 18;
    private final Supplier<List<ResourceStack>> contents;
    private final Supplier<ResourceKey> selected;
    private final Consumer<ResourceKey> select;
    private final Supplier<Component> emptyMessage;
    private final Function<ResourceStack, String> amountText;
    private final Function<ResourceStack, Component> amountTooltip;
    private final ResourceScrollBar scroll;
    private long tooltipRevision;
    GuiResourceGrid(IGuiWrapper gui, int x, int y, Supplier<List<ResourceStack>> contents,
            Supplier<ResourceKey> selected, Consumer<ResourceKey> select, Supplier<Component> emptyMessage) {
        this(gui, x, y, contents, selected, select, emptyMessage,
                value -> ResourceVisuals.compactAmount(value.key(), value.exactAmount()),
                value -> ResourceVisuals.quantity(value.key(), value.exactAmount(), 1));
    }
    GuiResourceGrid(IGuiWrapper gui, int x, int y, Supplier<List<ResourceStack>> contents,
            Supplier<ResourceKey> selected, Consumer<ResourceKey> select, Supplier<Component> emptyMessage,
            Function<ResourceStack, String> amountText, Function<ResourceStack, Component> amountTooltip) {
        super(gui, x, y, 302, ROWS * CELL);
        this.contents = contents; this.selected = selected; this.select = select;
        this.emptyMessage = emptyMessage;
        this.amountText = amountText;
        this.amountTooltip = amountTooltip;
        scroll = addChild(new ResourceScrollBar(gui, x + COLUMNS * CELL, y, contents));
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            final int cell = i;
            addChild(new GuiElement(gui, x + i % COLUMNS * CELL, y + i / COLUMNS * CELL, CELL, CELL) {
                private ResourceStack tooltipValue;
                private long tooltipAt = -1;
                @Override public void drawBackground(GuiGraphics graphics, int mx, int my, float partial) {
                    var value = row(cell);
                    boolean chosen = value != null && value.key().equals(GuiResourceGrid.this.selected.get());
                    boolean hover = isMouseOver(mx, my);
                    ResourceGuiTextures.sprite(graphics, relativeX, relativeY,
                            (value == null ? 3 : chosen ? 2 : hover ? 1 : 0) * CELL, 0, CELL, CELL);
                }
                @Override public void onClick(double mx, double my, int button) {
                    var value = row(cell);
                    if (button == 0 && value != null) GuiResourceGrid.this.select.accept(value.key());
                }
                @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
                    super.renderForeground(graphics, mx, my);
                    var value = row(cell);
                    if (value == null) { tooltipValue = null; setTooltip((Tooltip) null); return; }
                    if (!value.equals(tooltipValue) || tooltipAt != tooltipRevision) {
                        tooltipValue = value;
                        tooltipAt = tooltipRevision;
                        setTooltip(Tooltip.create(ResourceVisuals.name(value.key()).copy().append("\n")
                            .append(GuiResourceGrid.this.amountTooltip.apply(value)).append("\n")
                            .append(ResourceVisuals.id(value.key()).toString())));
                    }
                    graphics.pose().pushPose(); graphics.pose().translate(relativeX + 1, relativeY + 1, 0);
                    ResourceVisuals.draw(graphics, value.key()); graphics.pose().popPose();
                    String amount = GuiResourceGrid.this.amountText.apply(value);
                    graphics.pose().pushPose();
                    graphics.pose().translate(relativeX + CELL - 1, relativeY + 11, 200);
                    float scale = font().width(amount) * .6F >= 16 ? .5F : .6F;
                    graphics.pose().scale(scale, scale, 1);
                    graphics.drawString(font(), amount, -font().width(amount), 0, 0xFFFFFF, true);
                    graphics.pose().popPose();
                }
            });
        }
    }
    private ResourceStack row(int index) {
        var rows = contents.get(); int offset = scroll.getCurrentSelection() * COLUMNS + index;
        return offset < rows.size() ? rows.get(offset) : null;
    }
    void resetScroll() { scroll.reset(); }
    void refreshTooltips() { tooltipRevision++; }
    @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
        super.renderForeground(graphics, mx, my);
        if (contents.get().isEmpty()) {
            ResourceGuiTextures.button(graphics, relativeX + 5, relativeY + 18, COLUMNS * CELL - 10, 36, 2);
            var lines = font().split(emptyMessage.get(), COLUMNS * CELL - 24);
            int y = relativeY + 36 - lines.size() * 5;
            for (var line : lines) {
                graphics.drawString(font(), line, relativeX + (COLUMNS * CELL - font().width(line)) / 2, y, 0xFFFFFF, false);
                y += 10;
            }
        }
    }
    @Override public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return scroll.adjustScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }
    private static final class ResourceScrollBar extends GuiScrollBar {
        ResourceScrollBar(IGuiWrapper gui, int x, int y, Supplier<List<ResourceStack>> contents) {
            super(gui, x, y, ROWS * CELL, () -> (contents.get().size() + COLUMNS - 1) / COLUMNS, () -> ROWS);
        }
        void reset() { scroll = 0; }
        @Override public void drawBackground(GuiGraphics graphics, int mx, int my, float partial) {
            ResourceGuiTextures.button(graphics, relativeX + 2, relativeY, 12, height, 2);
            if (needsScrollBars()) ResourceGuiTextures.button(graphics, barX, barY + getScroll(),
                    barWidth, barHeight, isMouseOver(mx, my) ? 1 : 0);
        }
    }
}
