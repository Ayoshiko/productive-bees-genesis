package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** 设计像素与 4 倍覆盖率纹理解耦；九宫格只拉伸非零中心，阴影不改变控件边界。 */
final class DesignerMaterials {
	private static final RenderType CONTROL = renderType("control");
	private static final RenderType CARD = renderType("card");
	private static final RenderType WINDOW = renderType("window");
	private static final RenderType CONTROL_BORDER = renderType("control_border");
	private static final RenderType CARD_BORDER = renderType("card_border");
	private static final RenderType WINDOW_BORDER = renderType("window_border");
	private static final RenderType SHADOW = renderType("shadow");
	static final int CONTROL_RADIUS = 5;
	static final int CARD_RADIUS = 8;
	static final int WINDOW_RADIUS = 10;

	private DesignerMaterials() {
	}

	private static RenderType renderType(String name) {
		ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID,
				"textures/gui/bee_designer/materials/" + name + ".png");
		return DesignerFontRenderTypes.raster(texture, true);
	}

	static void surface(GuiGraphics graphics, int x, int y, int width, int height, int radius, int fill, int border) {
		if (width <= 0 || height <= 0) {
			return;
		}
		mask(graphics, x, y, width, height, radius, fill);
		RenderType outline = CONTROL_BORDER;
		int size = 18;
		if (radius >= WINDOW_RADIUS) {
			outline = WINDOW_BORDER;
			size = 28;
		} else if (radius >= CARD_RADIUS) {
			outline = CARD_BORDER;
			size = 24;
		}
		nineSlice(graphics, outline, x, y, width, height, size, radius, border);
	}

	static void mask(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
		RenderType type;
		int size;
		if (radius >= WINDOW_RADIUS) {
			type = WINDOW;
			size = 28;
		} else if (radius >= CARD_RADIUS) {
			type = CARD;
			size = 24;
		} else {
			type = CONTROL;
			size = 18;
		}
		nineSlice(graphics, type, x, y, width, height, size, radius, color);
	}

	static void shadow(GuiGraphics graphics, int x, int y, int width, int height, boolean window) {
		int opacity = DesignerTheme.light ? 36 : 62;
		if (!window) {
			opacity /= 2;
		}
		nineSlice(graphics, SHADOW, x - 6, y - 4, width + 12, height + 12, 48, 18, opacity << 24);
	}

	private static void nineSlice(GuiGraphics graphics, RenderType type, int x, int y, int width, int height,
			int sourceSize, int corner, int color) {
		if (width <= 0 || height <= 0 || color >>> 24 == 0) {
			return;
		}
		int edge = Math.min(corner, Math.min(width, height) / 2);
		float fraction = corner / (float) sourceSize;
		var consumer = graphics.bufferSource().getBuffer(type);
		var pose = graphics.pose().last().pose();
		for (int row = 0; row < 3; row++) {
			float top = coordinate(row, y, height, edge);
			float bottom = coordinate(row + 1, y, height, edge);
			for (int column = 0; column < 3; column++) {
				float left = coordinate(column, x, width, edge);
				float right = coordinate(column + 1, x, width, edge);
				if (right > left && bottom > top) {
					DesignerGlyphAtlas.quad(consumer, pose, left, top, right, bottom,
							sample(column, fraction), sample(row, fraction), sample(column + 1, fraction), sample(row + 1, fraction), color);
				}
			}
		}
	}

	private static float coordinate(int index, int start, int size, int edge) {
		return switch (index) {
			case 0 -> start;
			case 1 -> start + edge;
			case 2 -> start + size - edge;
			default -> start + size;
		};
	}

	private static float sample(int index, float edge) {
		return switch (index) {
			case 0 -> 0;
			case 1 -> edge;
			case 2 -> 1 - edge;
			default -> 1;
		};
	}
}
