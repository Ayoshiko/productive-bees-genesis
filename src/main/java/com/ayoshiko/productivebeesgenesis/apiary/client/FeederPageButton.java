package com.ayoshiko.productivebeesgenesis.apiary.client;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;

/**
	 * 翻页按钮 — 继承 MekanismButton 复用渲染管线
	 * <br/>
	 * DEFAULT 灰色背景，显示 ◀/▶ 箭头符号，点击触发翻页回调。
	 */
final class FeederPageButton extends MekanismButton {

	FeederPageButton(IGuiWrapper gui, int x, int y, int width, int height, String symbol, Runnable onClick) {
		super(gui, x, y, width, height, Component.literal(symbol), (e, mx, my) -> {
			onClick.run();
			return true;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
