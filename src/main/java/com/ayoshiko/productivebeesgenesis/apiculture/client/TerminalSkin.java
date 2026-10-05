package com.ayoshiko.productivebeesgenesis.apiculture.client;

import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 专用终端的独立像素皮肤；九宫格只拉伸中段，图标保持原始像素比例。 */
final class TerminalSkin {
	static final int INK = 0xffe7e9df, MUTED = 0xffa9b3b7, GOLD = 0xffefc66c;
	static final ResourceLocation FRAME = texture("frame"), BUTTON = texture("button"), ICONS = texture("icons");
	static ResourceLocation texture(String name) {
		return ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "textures/gui/terminal/" + name + ".png");
	}
	static void panel(GuiGraphics g, int x, int y, int width, int height) { patch(g, FRAME, x, y, width, height, 0, 16); }
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
			patch(g, BUTTON, getX(), getY(), width, height, state * 16, 64);
			if (painter != null) painter.accept(g);
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
