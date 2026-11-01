package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;

/** 目录保持服务端有界分页；滑块位置映射到页码和该页内部位置。 */
final class DesignerLibraryScrollbar extends AbstractWidget {
    private final int page;
    private final int pages;
    private final DoubleSupplier localPosition;
    private final BiConsumer<Integer, Double> selected;
    private double draggingPosition = -1;

    DesignerLibraryScrollbar(int x, int y, int height, int page, int pages, DoubleSupplier localPosition,
                             BiConsumer<Integer, Double> selected) {
        super(x, y, 10, height, Component.translatable("designer.catalog_scroll"));
        this.page = page;
        this.pages = pages;
        this.localPosition = localPosition;
        this.selected = selected;
        setTooltip(Tooltip.create(Component.translatable("designer.catalog_scroll_help")));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int thumb = Math.min(height, Math.max(18, height / pages));
        double position = draggingPosition >= 0 ? draggingPosition : (page + localPosition.getAsDouble()) / pages;
        int top = getY() + (int) Math.round((height - thumb) * position);
        DesignerTheme.scrollbar(graphics, getX() + 2, getY(), 7, height, top, thumb, isHoveredOrFocused());
    }

    private void select(double y) {
        int thumb = Math.min(height, Math.max(18, height / pages));
        draggingPosition = Math.max(0, Math.min(1, (y - getY() - thumb / 2.0) / Math.max(1, height - thumb)));
        double position = Math.min(pages - 0.000001, draggingPosition * pages);
        int target = (int) position;
        selected.accept(target, position - target);
    }

    @Override
    public void onClick(double x, double y) {
        select(y);
    }

    @Override
    protected void onDrag(double x, double y, double dx, double dy) {
        select(y);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
