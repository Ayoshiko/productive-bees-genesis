package com.ayoshiko.productivebeesgenesis.apiculture.client;

import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;

/** 小格点击只提交服务器页行；左键一组、右键一个，背包接收槽由服务器选择。 */
final class NetworkProductButton extends Button {
	private static final ResourceLocation TEXTURE = NetworkGuiButton.texture("product_slot");
	private final TerminalProductIcon icon;
	private final IntConsumer action;
	NetworkProductButton(int x, int y, TerminalProductIcon icon, IntConsumer action) {
		super(x, y, 20, 20, icon.name(), ignored -> action.accept(0), DEFAULT_NARRATION);
		this.icon = icon; this.action = action;
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (!active || !visible || button < 0 || button > 1 || !isMouseOver(x, y)) return false;
		playDownSound(Minecraft.getInstance().getSoundManager()); action.accept(button); return true;
	}
	@Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int state = !active ? 3 : isHoveredOrFocused() ? 1 : 0;
		graphics.blit(TEXTURE, getX(), getY(), state * 20, 0, 20, 20, 80, 20);
		icon.render(graphics, getX(), getY());
	}
}
