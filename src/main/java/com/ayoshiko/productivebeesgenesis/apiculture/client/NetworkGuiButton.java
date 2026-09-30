package com.ayoshiko.productivebeesgenesis.apiculture.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 独立皮肤；保留原版按钮的键盘、焦点、提示与朗读行为。 */
final class NetworkGuiButton extends Button {
	private static final ResourceLocation BUTTON = texture("button");
	private static final ResourceLocation TABS = texture("tabs");
	private static final ResourceLocation ICONS = texture("icons");
	private final boolean selected;
	private final int icon;

	NetworkGuiButton(int x, int y, int width, int height, Component label, Runnable action, boolean selected, int icon) {
		super(x, y, width, height, label, ignored -> action.run(), DEFAULT_NARRATION);
		this.selected = selected; this.icon = icon;
	}
	static ResourceLocation texture(String name) {
		return ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "textures/gui/network/" + name + ".png");
	}
	@Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int state = !active ? 3 : selected ? 2 : isHoveredOrFocused() ? 1 : 0;
		if (icon >= 0) {
			graphics.blit(TABS, getX(), getY(), 0, icon * 28, 24, 28, 24, 84);
			if (selected || isHoveredOrFocused()) graphics.fill(getX() + 2, getY() + 2, getX() + 4, getY() + 26, active ? 0xffefd18a : 0xff75786a);
			graphics.blit(ICONS, getX() + 5, getY() + 6, icon * 16, 0, 16, 16, 48, 16);
		} else {
			// 只伸展中心，保留两侧 2 像素边框；美术可直接替换四态图集。
			graphics.blit(BUTTON, getX(), getY(), 2, height, 0, state * 16, 2, 16, 24, 64);
			graphics.blit(BUTTON, getX() + 2, getY(), width - 4, height, 2, state * 16, 20, 16, 24, 64);
			graphics.blit(BUTTON, getX() + width - 2, getY(), 2, height, 22, state * 16, 2, 16, 24, 64);
			var font = Minecraft.getInstance().font;
			String text = font.plainSubstrByWidth(getMessage().getString(), width - 6);
			graphics.drawString(font, text, getX() + (width - font.width(text)) / 2,
					getY() + (height - 8) / 2, active ? 0xfff0edda : 0xff969e8f, false);
		}
	}
}
