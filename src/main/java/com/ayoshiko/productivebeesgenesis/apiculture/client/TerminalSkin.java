package com.ayoshiko.productivebeesgenesis.apiculture.client;

import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 专用终端的独立像素皮肤；九宫格只拉伸中段，图标保持原始像素比例。 */
final class TerminalSkin {
	static final int INK = 0xff30303f, MUTED = 0xff535366, GOLD = 0xff705523;
	static final ResourceLocation FRAME = texture("frame"), BUTTON = texture("button"), ICONS = texture("icons");
	static ResourceLocation texture(String name) {
		return ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "textures/gui/terminal/" + name + ".png");
	}
	static void panel(GuiGraphics g, int x, int y, int width, int height) {
		g.fill(x, y, x + width, y + height, 0xff4d4e65);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xfff1f1f5);
		g.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xff9b9eb3);
		g.fill(x + 3, y + 3, x + width - 3, y + height - 3, 0xffcbcbd5);
	}
	static void glyph(GuiGraphics g, int icon, int x, int y) {
		int ink = 0xff41465f;
		switch (icon) {
			case 8, 11, 12 -> { // 网格、资源类型、存储／合成。
				for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
					g.fill(x + 2 + col * 4, y + 2 + row * 4, x + 5 + col * 4, y + 5 + row * 4, icon == 11 && col == 1 ? 0xff5379ae : ink);
				if (icon == 12) { g.fill(x + 7, y + 3, x + 9, y + 13, 0xffe4bd56); g.fill(x + 3, y + 7, x + 13, y + 9, 0xffe4bd56); }
			}
			case 9 -> { for (int row = 0; row < 3; row++) { g.fill(x + 2, y + 3 + row * 4, x + 4, y + 5 + row * 4, ink); g.fill(x + 6, y + 3 + row * 4, x + 14 - row * 3, y + 5 + row * 4, ink); } }
			case 10, 16 -> { g.fill(x + 7, y + 2, x + 9, y + 13, ink); for (int i = 0; i < 4; i++) { int yy = icon == 10 ? y + 9 + i : y + 5 - i; g.fill(x + 4 + i, yy, x + 12 - i, yy + 1, ink); } }
			case 13 -> { g.fill(x + 3, y + 2, x + 13, y + 5, ink); g.fill(x + 7, y + 5, x + 9, y + 14, ink); }
			case 14 -> { g.fill(x + 3, y + 3, x + 12, y + 5, ink); g.fill(x + 3, y + 3, x + 5, y + 12, ink); g.fill(x + 3, y + 11, x + 12, y + 13, ink); g.fill(x + 11, y + 7, x + 13, y + 13, ink); g.fill(x + 9, y + 2, x + 13, y + 7, ink); }
			case 15 -> { g.fill(x + 3, y + 4, x + 13, y + 6, ink); g.fill(x + 5, y + 7, x + 11, y + 14, ink); g.fill(x + 6, y + 2, x + 10, y + 4, ink); }
			default -> { }
		}
	}
	static void patch(GuiGraphics g, ResourceLocation texture, int x, int y, int width, int height, int v, int textureHeight) {
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
			g.blit(texture, x + (a == 0 ? 0 : a == 1 ? 2 : width - 2), y + (b == 0 ? 0 : b == 1 ? 2 : height - 2),
					a == 1 ? width - 4 : 2, b == 1 ? height - 4 : 2, a == 0 ? 0 : a == 1 ? 2 : 14,
					v + (b == 0 ? 0 : b == 1 ? 2 : 14), a == 1 ? 12 : 2, b == 1 ? 12 : 2, 16, textureHeight);
	}
	static final class Control extends Button {
		private final boolean selected;
		private final int icon;
		private final IntConsumer action;
		private final java.util.function.Consumer<GuiGraphics> painter;
		private boolean slot;
		Control slot() { slot = true; return this; }
		Control(int x, int y, int width, int height, Component label, IntConsumer action, boolean selected,
				int icon, java.util.function.Consumer<GuiGraphics> painter) {
			super(x, y, width, height, label, ignored -> action.accept(0), DEFAULT_NARRATION);
			this.action = action; this.selected = selected; this.icon = icon; this.painter = painter;
		}
		@Override public boolean mouseClicked(double x, double y, int button) {
			if (!active || !visible || button < 0 || button > 1 || button == 1 && painter == null || !isMouseOver(x, y)) return false;
			playDownSound(Minecraft.getInstance().getSoundManager()); action.accept(button); return true;
		}
		@Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
			int state = !active ? 3 : selected ? 2 : isHoveredOrFocused() ? 1 : 0;
			if (slot) {
				if (isHoveredOrFocused()) g.fill(getX(), getY(), getX() + width, getY() + height, 0x80ffffff);
			} else {
				panel(g, getX(), getY(), width, height);
				g.fill(getX() + 3, getY() + 3, getX() + width - 3, getY() + height - 3, state == 2 ? 0xffa9c9e7 : state == 1 ? 0xffb9bfd4 : state == 3 ? 0xffb9b9c3 : 0xff999db5);
			}
			if (painter != null) painter.accept(g);
			else if (icon >= 8) glyph(g, icon, getX() + (width - 16) / 2, getY() + (height - 16) / 2);
			else if (icon >= 0) g.blit(ICONS, getX() + (width - 16) / 2, getY() + (height - 16) / 2, icon * 16, 0, 16, 16, 128, 16);
			else {
				var font = Minecraft.getInstance().font;
				String text = font.plainSubstrByWidth(getMessage().getString(), width - 6);
				g.drawString(font, text, getX() + (width - font.width(text)) / 2, getY() + (height - 8) / 2, active ? INK : MUTED, false);
			}
		}
	}
	private TerminalSkin() { }
}
