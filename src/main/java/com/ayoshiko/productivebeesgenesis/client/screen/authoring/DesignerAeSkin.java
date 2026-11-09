package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 复用已安装 AE2 的资源位置，不解析可选类；无资源时按同一像素边框绘制。 */
final class DesignerAeSkin {
	private static final ResourceLocation STATES = ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/states.png");
	private static boolean statesAvailable;

	private DesignerAeSkin() {
	}

	static void refresh() {
		statesAvailable = Minecraft.getInstance().getResourceManager().getResource(STATES).isPresent();
	}

	static boolean enabled() {
		return DesignerPreferences.text("skin").equals("ae") || DesignerPreferences.text("theme").startsWith("ae_");
	}

	static void frame(GuiGraphics graphics, int x, int y, int width, int height, int fill, boolean inset) {
		if (width < 4 || height < 4) {
			graphics.fill(x, y, x + width, y + height, fill);
			return;
		}
		boolean light = DesignerTheme.colors().light();
		int dark = light ? 0xFF413F54 : 0xFF14131E;
		int shine = light ? 0xFFF2F2F2 : 0xFF79798F;
		int edge = light ? 0xFF878FA5 : 0xFF484B62;
		graphics.fill(x, y, x + width, y + height, dark);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, edge);
		graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, fill);
		int upper = inset ? edge : shine;
		int lower = inset ? shine : edge;
		graphics.fill(x + 1, y + 1, x + width - 1, y + 2, upper);
		graphics.fill(x + 1, y + 1, x + 2, y + height - 1, upper);
		graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, lower);
		graphics.fill(x + width - 2, y + 1, x + width - 1, y + height - 1, lower);
	}

	static void button(GuiGraphics graphics, int x, int y, int width, int height, int fill, boolean hovered, boolean focused, boolean pressed) {
		if (statesAvailable && DesignerTheme.colors().light() && width >= 8 && height >= 8) {
			int sourceX = 176;
			if (hovered) {
				sourceX = 212;
			} else if (focused) {
				sourceX = 194;
			}
			nineSlice(graphics, x, y + (pressed ? 1 : 0), width, height - (pressed ? 1 : 0), sourceX, 128, 18, 20);
		} else {
			frame(graphics, x, y, width, height, fill, pressed);
		}
	}

	private static void nineSlice(GuiGraphics graphics, int x, int y, int width, int height, int u, int v, int sourceWidth, int sourceHeight) {
		RenderSystem.enableBlend();
		for (int row = 0; row < 3; row++) {
			int dy = switch (row) {
				case 0 -> 0;
				case 1 -> 2;
				default -> height - 2;
			};
			int dh = row == 1 ? height - 4 : 2;
			int sy = switch (row) {
				case 0 -> 0;
				case 1 -> 2;
				default -> sourceHeight - 2;
			};
			int sh = row == 1 ? sourceHeight - 4 : 2;
			for (int column = 0; column < 3; column++) {
				int dx = switch (column) {
					case 0 -> 0;
					case 1 -> 2;
					default -> width - 2;
				};
				int dw = column == 1 ? width - 4 : 2;
				int sx = switch (column) {
					case 0 -> 0;
					case 1 -> 2;
					default -> sourceWidth - 2;
				};
				int sw = column == 1 ? sourceWidth - 4 : 2;
				graphics.blit(STATES, x + dx, y + dy, dw, dh, u + sx, v + sy, sw, sh, 256, 256);
			}
		}
	}

	static boolean icon(GuiGraphics graphics, DesignerTheme.Symbol symbol, int x, int y) {
		if (!statesAvailable) {
			return false;
		}
		int u;
		int v;
		switch (symbol) {
			case SETTINGS -> {
				u = 32;
				v = 64;
			}
			case PIN -> {
				u = 144;
				v = 0;
			}
			case CLOSE -> {
				u = 96;
				v = 0;
			}
			case BACK -> {
				u = 96;
				v = 16;
			}
			case DELETE -> {
				u = 0;
				v = 112;
			}
			default -> {
				return false;
			}
		}
		RenderSystem.enableBlend();
		graphics.blit(STATES, x, y, u, v, 16, 16);
		return true;
	}
}
