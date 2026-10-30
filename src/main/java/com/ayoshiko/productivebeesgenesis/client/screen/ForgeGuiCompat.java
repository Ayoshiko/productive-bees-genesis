package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.List;
import mekanism.client.gui.element.GuiElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Rendering helpers for the text API introduced after Mekanism 10.4. */
public final class ForgeGuiCompat {
    private ForgeGuiCompat() {}

    public enum TextAlignment { LEFT, CENTER, RIGHT }

    /** Bridges the element-aware click callback and tooltip update hook. */
    public static class MekanismButton extends mekanism.client.gui.element.button.MekanismButton {
        private final GuiElement.IClickable callback;

        public MekanismButton(mekanism.client.gui.IGuiWrapper gui, int x, int y, int width, int height,
                Component text, GuiElement.IClickable callback) {
            super(gui, x, y, width, height, text, () -> {}, null);
            this.callback = callback;
        }

        @Override public void onClick(double mouseX, double mouseY, int button) {
            if (button == 0 && callback != null) callback.onClick(this, (int) mouseX, (int) mouseY);
        }

        public void updateTooltip(int mouseX, int mouseY) {}
        protected boolean displayButtonTextShadow() { return true; }

        @Override public void renderToolTip(GuiGraphics graphics, int mouseX, int mouseY) {
            updateTooltip(mouseX, mouseY);
            super.renderToolTip(graphics, mouseX, mouseY);
        }

        @Override protected void drawButtonText(GuiGraphics graphics, int mouseX, int mouseY) {
            drawScaledScrollingString(this, graphics, getMessage(), 0, (getHeight() - 8) / 2,
                    TextAlignment.CENTER, getButtonTextColor(mouseX, mouseY), getWidth(), 2,
                    displayButtonTextShadow(), 1F);
        }
    }

    public static final class TooltipUtils {
        private TooltipUtils() {}
        public static Tooltip create(Component text) { return Tooltip.create(text); }
        public static Tooltip create(List<Component> lines) {
            MutableComponent text = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) text.append("\n");
                text.append(lines.get(i));
            }
            return Tooltip.create(text);
        }
    }

    public static void drawScrollingString(GuiElement element, GuiGraphics graphics, Component text,
            int x, int y, TextAlignment alignment, int color, int padding, boolean shadow) {
        drawScaledScrollingString(element, graphics, text, x, y, alignment, color,
                element.getWidth(), padding, shadow, 1F);
    }

    public static void drawScaledScrollingString(GuiElement element, GuiGraphics graphics, Component text,
            int x, int y, TextAlignment alignment, int color, int width, int padding,
            boolean shadow, float scale) {
        drawScaledScrollingString(element, graphics, text, x, y, alignment, color, width,
                padding, shadow, scale, 0L);
    }

    public static void drawScaledScrollingString(GuiElement element, GuiGraphics graphics, Component text,
            int x, int y, TextAlignment alignment, int color, int width, int padding,
            boolean shadow, float scale, long startTime) {
        if (scale <= 0 || width <= 2 * padding) return;
        var font = Minecraft.getInstance().font;
        float left = element.getRelativeX() + x + padding;
        float top = element.getRelativeY() + y;
        float available = width - 2F * padding;
        float textWidth = font.width(text) * scale;
        float offset = 0;
        if (textWidth > available) {
            double seconds = Math.max(0, net.minecraft.Util.getMillis() - startTime) / 1000D;
            offset = (float) ((textWidth - available) * (0.5 - 0.5 * Math.cos(seconds * 0.8)));
        } else if (alignment == TextAlignment.CENTER) {
            left += (available - textWidth) / 2;
        } else if (alignment == TextAlignment.RIGHT) {
            left += available - textWidth;
        }
        // Scissor uses absolute screen coordinates, while the pose is GUI-relative.
        int clipX = element.getX() + x + (int) padding;
        int clipY = element.getY() + y;
        graphics.enableScissor(clipX, clipY, clipX + width - 2 * (int) padding,
                clipY + (int) Math.ceil(font.lineHeight * scale) + 2);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(left - offset, top, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(font, text, 0, 0, color, shadow);
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }
}
