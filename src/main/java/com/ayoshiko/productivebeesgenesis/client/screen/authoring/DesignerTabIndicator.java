package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** 选中背景独立于按钮文字，切换栏目时保留同一个动画值。 */
final class DesignerTabIndicator extends AbstractWidget {
    private final int cell;
    private final int total;
    private final int target;
    private final DesignerMotionValue position;
    private long frameAt;

    DesignerTabIndicator(int x, int y, int cell, int total, int target, DesignerMotionValue position) {
        super(x, y, cell * total + (total - 1) * 3, 20, Component.empty());
        this.cell = cell;
        this.total = total;
        this.target = target;
        this.position = position;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = net.minecraft.Util.getMillis();
        double seconds = frameAt == 0 ? 0 : (now - frameAt) / 1000.0;
        frameAt = now;
        position.advance(target, seconds, DesignerPreferences.number("animation_ms") / 1000.0, DesignerPreferences.animate());
        var colors = DesignerTheme.colors();
        DesignerGlyphs.rounded(graphics, getX(), getY(), width, 20, 5, colors.input());
        graphics.pose().pushPose();
        graphics.pose().translate((float) position.position() * (cell + 3), 0, 0);
        DesignerGlyphs.rounded(graphics, getX(), getY(), cell, 20, 5, colors.selected());
        graphics.pose().popPose();
    }

    @Override
    public boolean isMouseOver(double x, double y) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // 选中背景的朗读由实际导航按钮提供。
    }
}
