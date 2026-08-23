package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/** Text control for opening the reserve editor or toggling stock mode. */
final class ReserveModeButton extends MekanismButton {

	private static final int ENABLED_TEXT_COLOR = 0x54E38A;
	private static final int DISABLED_TEXT_COLOR = 0xA0A0A0;
	private static final int ENABLED_STATE_COLOR = 0x35C979;
	private static final int DISABLED_STATE_COLOR = 0x707070;

	private final Runnable configureCallback;
	private final Runnable toggleCallback;
	private boolean globalStock;

	ReserveModeButton(IGuiWrapper gui, int x, int y, Runnable configureCallback, Runnable toggleCallback) {
		super(gui, x, y, AeInputConfigLayout.RESERVE_BTN_WIDTH, AeInputConfigLayout.CTRL_BTN_HEIGHT,
				Component.translatable("productivebeesgenesis.gui.ae_input_config.reserve_button.label"),
				(element, mouseX, mouseY) -> false);
		this.configureCallback = configureCallback;
		this.toggleCallback = toggleCallback;
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
	}

	/** Updates the visible state label for the filter-level stock policy. */
	void setGlobalStock(boolean enabled) {
		if (globalStock == enabled) return;
		globalStock = enabled;
		setMessage(Component.translatable("productivebeesgenesis.gui.ae_input_config.reserve_button.label"));
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return globalStock ? ENABLED_TEXT_COLOR : DISABLED_TEXT_COLOR;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) return;
		super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
		int stateColor = globalStock ? ENABLED_STATE_COLOR : DISABLED_STATE_COLOR;
		guiGraphics.fill(relativeX + 3, relativeY + height - 3,
				relativeX + width - 3, relativeY + height - 1, stateColor);
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
