package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/** 引导轮廓按半像素覆盖率缓存；同一目标移动时只平移绘制，不在每帧重建几何。 */
final class DesignerGuideOverlay {
	private List<Span> outline = List.of();
	private int outlineWidth;
	private int outlineHeight;
	private List<Span> arrow = List.of();
	private int arrowDx = Integer.MIN_VALUE;
	private int arrowDy;
	private int arrowLeft;
	private int arrowTop;

	void focus(GuiGraphics graphics, Rect2i target, int color, float opacity) {
		for (int spread = 8; spread >= 4; spread -= 2) {
			box(graphics, target.getX() - spread, target.getY() - spread,
					target.getWidth() + spread * 2, target.getHeight() + spread * 2, 6 + spread,
					alpha(color, (int) (5 * opacity)));
		}
		box(graphics, target.getX() - 2, target.getY() - 2, target.getWidth() + 4, target.getHeight() + 4,
				6, alpha(color, (int) (10 * opacity)));
		int width = (target.getWidth() + 8) * 2;
		int height = (target.getHeight() + 8) * 2;
		if (outlineWidth != width || outlineHeight != height) {
			outlineWidth = width;
			outlineHeight = height;
			outline = mask(width, height, (x, y) -> {
				double halfW = width / 2.0 - 2;
				double halfH = height / 2.0 - 2;
				double radius = Math.min(12, halfH);
				double dx = Math.abs(x - width / 2.0) - halfW + radius;
				double dy = Math.abs(y - height / 2.0) - halfH + radius;
				double distance = Math.hypot(Math.max(0, dx), Math.max(0, dy)) + Math.min(Math.max(dx, dy), 0) - radius;
				return coverage(1.65 - Math.abs(distance));
			});
		}
		draw(graphics, outline, target.getX() - 4, target.getY() - 4, alpha(color, (int) (185 * opacity)));
	}

	void connect(GuiGraphics graphics, Rect2i card, Rect2i target, int color, float opacity) {
		int cx = card.getX() + card.getWidth() / 2;
		int cy = card.getY() + card.getHeight() / 2;
		int tx = target.getX() + target.getWidth() / 2;
		int ty = target.getY() + target.getHeight() / 2;
		int sx;
		int sy;
		int ex;
		int ey;
		if (card.getY() >= target.getY() + target.getHeight() || card.getY() + card.getHeight() <= target.getY()) {
			boolean below = cy > ty;
			sx = Math.max(card.getX() + 20, Math.min(tx + 14, card.getX() + card.getWidth() - 20));
			sy = below ? card.getY() : card.getY() + card.getHeight();
			ex = Math.max(target.getX() + 6, Math.min(sx - 14, target.getX() + target.getWidth() - 6));
			ey = below ? target.getY() + target.getHeight() + 6 : target.getY() - 6;
		} else {
			boolean right = cx > tx;
			sx = right ? card.getX() : card.getX() + card.getWidth();
			sy = Math.max(card.getY() + 18, Math.min(ty + 10, card.getY() + card.getHeight() - 18));
			ex = right ? target.getX() + target.getWidth() + 6 : target.getX() - 6;
			ey = ty;
		}
		int dx = (ex - sx) * 2;
		int dy = (ey - sy) * 2;
		if (Math.hypot(dx, dy) > 260) {
			return;
		}
		if (dx != arrowDx || dy != arrowDy) {
			arrowDx = dx;
			arrowDy = dy;
			buildArrow(dx, dy);
		}
		draw(graphics, arrow, sx + arrowLeft / 2.0F, sy + arrowTop / 2.0F, alpha(color, (int) (200 * opacity)));
	}

	private void buildArrow(int dx, int dy) {
		double length = Math.max(1, Math.hypot(dx, dy));
		double bend = Math.min(12, length * 0.18);
		double mx = dx / 2.0 - dy / length * bend;
		double my = dy / 2.0 + dx / length * bend;
		List<double[]> segments = new ArrayList<>();
		double px = 0;
		double py = 0;
		for (int index = 1; index <= 20; index++) {
			double t = index / 20.0;
			double x = 2 * (1 - t) * t * mx + t * t * dx;
			double y = 2 * (1 - t) * t * my + t * t * dy;
			segments.add(new double[]{px, py, x, y});
			px = x;
			py = y;
		}
		double angle = Math.atan2(dy - my, dx - mx);
		for (double side : new double[]{-0.55, 0.55}) {
			segments.add(new double[]{dx, dy, dx - 9 * Math.cos(angle + side), dy - 9 * Math.sin(angle + side)});
		}
		arrowLeft = (int) Math.floor(Math.min(Math.min(0, dx), mx)) - 12;
		arrowTop = (int) Math.floor(Math.min(Math.min(0, dy), my)) - 12;
		int width = (int) Math.ceil(Math.max(Math.max(0, dx), mx)) - arrowLeft + 12;
		int height = (int) Math.ceil(Math.max(Math.max(0, dy), my)) - arrowTop + 12;
		arrow = mask(width, height, (x, y) -> {
			double nearest = Double.POSITIVE_INFINITY;
			for (double[] line : segments) {
				double vx = line[2] - line[0];
				double vy = line[3] - line[1];
				double t = Math.max(0, Math.min(1, ((x + arrowLeft - line[0]) * vx + (y + arrowTop - line[1]) * vy)
						/ Math.max(0.01, vx * vx + vy * vy)));
				nearest = Math.min(nearest, Math.hypot(x + arrowLeft - line[0] - vx * t, y + arrowTop - line[1] - vy * t));
			}
			return coverage(1.75 - nearest);
		});
	}

	static void box(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
		graphics.pose().pushPose();
		graphics.pose().translate(x, y, 0);
		graphics.pose().scale(0.5F, 0.5F, 1);
		DesignerGlyphs.rounded(graphics, 0, 0, width * 2, height * 2, radius * 2, color);
		graphics.pose().popPose();
	}

	private static int alpha(int color, int alpha) {
		return Math.max(0, Math.min(255, alpha)) << 24 | color & 0xFFFFFF;
	}

	private static int coverage(double value) {
		return (int) (Math.max(0, Math.min(1, value)) * 255);
	}

	private static List<Span> mask(int width, int height, Coverage coverage) {
		List<Span> spans = new ArrayList<>();
		for (int y = 0; y < height; y++) {
			int start = 0;
			int previous = 0;
			for (int x = 0; x <= width; x++) {
				int alpha = x == width ? 0 : coverage.at(x + 0.5, y + 0.5);
				if (alpha != previous) {
					if (previous > 0) {
						spans.add(new Span(start, y, x, previous));
					}
					start = x;
					previous = alpha;
				}
			}
		}
		return List.copyOf(spans);
	}

	private static void draw(GuiGraphics graphics, List<Span> spans, float x, float y, int color) {
		graphics.pose().pushPose();
		graphics.pose().translate(x, y, 0);
		graphics.pose().scale(0.5F, 0.5F, 1);
		for (Span span : spans) {
			graphics.fill(span.x, span.y, span.end, span.y + 1, alpha(color, (color >>> 24) * span.alpha / 255));
		}
		graphics.pose().popPose();
	}

	private interface Coverage {
		int at(double x, double y);
	}

	private record Span(int x, int y, int end, int alpha) {
	}
}
