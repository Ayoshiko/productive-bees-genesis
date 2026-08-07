package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.function.Consumer;

import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;

/** 离心机新产物直输 AE 开关按钮。 */
public class CentrifugeDirectAeOutputButton extends MekanismButton {

	private static final int BUTTON_SIZE = 14;
	AeInputOverlay.OverlayTarget target;
	private final Consumer<AeInputOverlay.OverlayTarget> onClick;

	public CentrifugeDirectAeOutputButton(GuiMekanism<?> gui, int x, int y, Component label,
			Consumer<AeInputOverlay.OverlayTarget> onClick, AeInputOverlay.OverlayTarget target) {
		super(gui, x, y, BUTTON_SIZE, BUTTON_SIZE, label, (element, mouseX, mouseY) -> {
			if (element instanceof CentrifugeDirectAeOutputButton button && button.target != null) {
				button.onClick.accept(button.target);
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
		this.onClick = onClick;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		if (target != null && target.tile() instanceof IAe2OutputHostBase host
				&& host.productivebeesgenesis$getAe2StateHolder() != null
				&& host.productivebeesgenesis$getAe2StateHolder().isCentrifugeDirectAeOutputEnabled()) {
			return 0x009E45;
		}
		return 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
