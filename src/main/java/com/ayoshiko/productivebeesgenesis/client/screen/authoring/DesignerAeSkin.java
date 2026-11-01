package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 复用已安装 AE2 的资源位置，不解析可选类；控件边框统一使用平滑圆角绘制。 */
final class DesignerAeSkin {
	private static final ResourceLocation STATES = new ResourceLocation("ae2", "textures/guis/states.png");
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
        var palette = DesignerTheme.colors();
        int border = inset ? DesignerPalette.mix(palette.border(), palette.text(), 0.12F) : palette.border();
        DesignerMaterials.surface(graphics, x, y, width, height, DesignerMaterials.CONTROL_RADIUS, fill, border);
    }

    static void button(GuiGraphics graphics, int x, int y, int width, int height, int fill, boolean hovered, boolean focused, boolean pressed) {
        // The caller interpolates fill and press motion; avoid snapping between pixel-art states.
        frame(graphics, x, y, width, height, fill, pressed);
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
