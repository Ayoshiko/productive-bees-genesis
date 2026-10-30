package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 标题图标使用同一笔宽和圆端线条；覆盖率在类加载时计算，绘制只提交固定色段。 */
final class DesignerGlyphs {
    private static final Map<DesignerTheme.Symbol, List<Span>> GLYPHS = new EnumMap<>(DesignerTheme.Symbol.class);

    static {
        for (var symbol : DesignerTheme.Symbol.values()) {
            List<Span> spans = new ArrayList<>();
            for (int y = 0; y < 24; y++) {
                int start = 0;
                int previous = 0;
                for (int x = 0; x <= 24; x++) {
                    int alpha = x == 24 ? 0 : coverage(symbol, x, y);
                    if (alpha != previous) {
                        if (previous != 0) {
                            spans.add(new Span(start, y, x, previous));
                        }
                        start = x;
                        previous = alpha;
                    }
                }
            }
            GLYPHS.put(symbol, List.copyOf(spans));
        }
    }

    private DesignerGlyphs() {
    }

    static void draw(GuiGraphics graphics, DesignerTheme.Symbol symbol, int color) {
        graphics.pose().pushPose();
        graphics.pose().scale(0.5F, 0.5F, 1);
        for (Span span : GLYPHS.get(symbol)) {
            int alpha = (color >>> 24) * span.alpha / 255;
            graphics.fill(span.start - 12, span.y - 12, span.end - 12, span.y - 11, alpha << 24 | color & 0xFFFFFF);
        }
        graphics.pose().popPose();
    }

    static void rounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		graphics.fill(x, y + r, x + width, y + height - r, color);
		for (int row = 0; row < r; row++) {
			double distance = r - row - 0.5;
			int inset = (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - distance * distance)));
			graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
			graphics.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
		}
    }

    static void ripple(GuiGraphics graphics, int x, int y, int width, int height, double hotspotX, double hotspotY,
                       double progress, int color) {
        double radius = Math.hypot(width, height) * DesignerMotionValue.easeOut(progress);
        int alpha = (int) (28 * (1 - progress));
        if (alpha <= 0) {
            return;
        }
        for (int row = 2; row < height - 2; row++) {
            double dy = row + 0.5 - hotspotY;
            if (Math.abs(dy) < radius) {
                double half = Math.sqrt(radius * radius - dy * dy);
                int start = (int) Math.max(2, hotspotX - half);
                int end = (int) Math.min(width - 2, hotspotX + half);
                if (end > start) {
                    graphics.fill(x + start, y + row, x + end, y + row + 1, alpha << 24 | color & 0xFFFFFF);
                }
            }
        }
    }

    private static int coverage(DesignerTheme.Symbol symbol, int x, int y) {
        int samples = 0;
        for (int sy = 0; sy < 4; sy++) {
            for (int sx = 0; sx < 4; sx++) {
                if (inside(symbol, x + (sx + 0.5) / 4, y + (sy + 0.5) / 4)) {
                    samples++;
                }
            }
        }
        return samples * 255 / 16;
    }

    private static boolean inside(DesignerTheme.Symbol symbol, double x, double y) {
        double radius = Math.hypot(x - 12, y - 12);
        return switch (symbol) {
            case THEME -> Math.abs(radius - 9) < 0.95 || x > 12 && radius < 7.4;
            case CLOSE -> line(x, y, 5, 5, 19, 19, 1) || line(x, y, 19, 5, 5, 19, 1);
            case BACK -> line(x, y, 15, 4, 7, 12, 1) || line(x, y, 7, 12, 15, 20, 1);
            case PIN -> line(x, y, 6, 4, 18, 4, 1) || line(x, y, 8, 4, 8, 11, 0.9)
                    || line(x, y, 16, 4, 16, 11, 0.9) || line(x, y, 8, 11, 5, 15, 0.9)
                    || line(x, y, 16, 11, 19, 15, 0.9) || line(x, y, 5, 15, 19, 15, 0.9)
                    || line(x, y, 12, 15, 12, 21, 0.9);
            case SETTINGS -> gear(x, y, radius);
            case COPY -> line(x, y, 4, 15, 4, 5, 0.9) || line(x, y, 4, 5, 14, 5, 0.9)
                    || line(x, y, 9, 9, 19, 9, 0.9) || line(x, y, 19, 9, 19, 20, 0.9)
                    || line(x, y, 19, 20, 9, 20, 0.9) || line(x, y, 9, 20, 9, 9, 0.9);
            case DELETE -> line(x, y, 5, 6, 19, 6, 0.9) || line(x, y, 9, 3, 15, 3, 0.9)
                    || line(x, y, 7, 7, 8, 20, 0.9) || line(x, y, 17, 7, 16, 20, 0.9)
                    || line(x, y, 8, 20, 16, 20, 0.9) || line(x, y, 10, 10, 10.5, 16, 0.75)
                    || line(x, y, 14, 10, 13.5, 16, 0.75);
        };
    }

    private static boolean gear(double x, double y, double radius) {
        if (Math.abs(radius - 3.3) < 0.85 || Math.abs(radius - 7.2) < 0.85) {
            return true;
        }
        for (int spoke = 0; spoke < 8; spoke++) {
            double angle = spoke * Math.PI / 4;
            if (line(x, y, 12 + Math.cos(angle) * 7, 12 + Math.sin(angle) * 7,
                    12 + Math.cos(angle) * 9.4, 12 + Math.sin(angle) * 9.4, 0.9)) {
                return true;
            }
        }
        return false;
    }

    private static boolean line(double x, double y, double ax, double ay, double bx, double by, double radius) {
        double dx = bx - ax;
        double dy = by - ay;
        double progress = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(x - ax - progress * dx, y - ay - progress * dy) <= radius;
    }

    private record Span(int start, int y, int end, int alpha) {
    }
}
