package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** 选中背景独立于按钮文字，切换栏目时保留同一个动画值。 */
final class DesignerTabIndicator extends AbstractWidget {
    private final int[] cells;
    private final int[] offsets;
    private final int target;
    private final DesignerMotionValue position;
    private long frameAt;

    DesignerTabIndicator(int x, int y, int[] cells, int target, DesignerMotionValue position) {
        super(x, y, java.util.Arrays.stream(cells).sum() + (cells.length - 1) * 3, 20, Component.empty());
        this.cells = cells.clone();
        offsets = new int[cells.length];
        for (int index = 1; index < cells.length; index++) {
            offsets[index] = offsets[index - 1] + cells[index - 1] + 3;
        }
        this.target = target;
        this.position = position;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = net.minecraft.Util.getMillis();
        double seconds = frameAt == 0 ? 0 : (now - frameAt) / 1000.0;
        frameAt = now;
        if (target < 0) {
            position.snap(-1);
        } else {
            if (position.position() < 0) {
                position.snap(target);
            }
            position.advance(target, seconds, Math.min(0.22, DesignerPreferences.number("animation_ms") / 1000.0),
                    DesignerPreferences.animate() && DesignerPreferences.enabled("motion_selection"));
        }
        var colors = DesignerTheme.colors();
		if (DesignerAeSkin.enabled()) {
			DesignerAeSkin.frame(graphics, getX(), getY(), width, 20, colors.input(), true);
		} else {
			DesignerMaterials.surface(graphics, getX(), getY(), width, 20, DesignerMaterials.CONTROL_RADIUS,
					colors.input(), DesignerPalette.mix(colors.input(), colors.border(), 0.5F));
		}
		if (target < 0) {
			return;
		}
		double value = Math.max(0, Math.min(cells.length - 1, position.position()));
        int first = (int) Math.floor(value);
        int second = Math.min(first + 1, cells.length - 1);
        double fraction = value - first;
        int offset = (int) Math.round(offsets[first] + (offsets[second] - offsets[first]) * fraction);
        int selectedWidth = (int) Math.round(cells[first] + (cells[second] - cells[first]) * fraction);
		if (DesignerAeSkin.enabled()) {
			DesignerAeSkin.frame(graphics, getX() + offset, getY(), selectedWidth, 20, colors.panel(), false);
			graphics.fill(getX() + offset + 3, getY() + 17, getX() + offset + selectedWidth - 3, getY() + 19, colors.accent());
		} else {
			DesignerMaterials.surface(graphics, getX() + offset + 1, getY() + 1, selectedWidth - 2, 18,
					DesignerMaterials.CONTROL_RADIUS, colors.selected(), DesignerPalette.mix(colors.border(), colors.accent(), 0.25F));
		}
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
