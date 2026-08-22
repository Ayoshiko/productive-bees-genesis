package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;

/** Text control for opening the reserve editor or toggling stock mode. */
final class ReserveModeButton extends MekanismButton {

	private final Runnable configureCallback;
	private final Runnable toggleCallback;

	ReserveModeButton(IGuiWrapper gui, int x, int y, Runnable configureCallback, Runnable toggleCallback) {
		super(gui, x, y, AeInputConfigLayout.RESERVE_BTN_WIDTH, AeInputConfigLayout.CTRL_BTN_HEIGHT,
				Component.translatable("productivebeesgenesis.gui.ae_input_config.reserve_button.label"),
				(element, mouseX, mouseY) -> false);
		this.configureCallback = configureCallback;
		this.toggleCallback = toggleCallback;
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!visible || !active || !isMouseOver(mouseX, mouseY)) return false;
		if (button == 0) {
			configureCallback.run();
			return true;
		}
		if (button == 1) {
			toggleCallback.run();
			return true;
		}
		return false;
	}
}
