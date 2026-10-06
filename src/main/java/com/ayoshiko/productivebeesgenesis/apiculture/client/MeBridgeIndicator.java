package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 两类菜单共享连接指示，悬停给出具体不可用原因。 */
public final class MeBridgeIndicator {
	public static void render(GuiGraphics graphics, Font font, MeBridgeStatus status, int x, int y, int mouseX, int mouseY) {
		int color = status == MeBridgeStatus.ONLINE ? 0xff87d7ae : 0xffb3a690;
		graphics.fill(x - 3, y - 2, x + 16, y + 10, 0xff17252c);
		graphics.drawString(font, "ME", x, y, color, false);
		if (mouseX >= x - 3 && mouseX < x + 16 && mouseY >= y - 2 && mouseY < y + 10)
			graphics.renderTooltip(font, status.message(), mouseX, mouseY);
	}
	private MeBridgeIndicator() { }
}
