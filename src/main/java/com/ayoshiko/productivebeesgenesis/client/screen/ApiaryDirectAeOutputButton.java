package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Toggles whether freshly generated apiary products try AE before local output slots. */
final class ApiaryDirectAeOutputButton extends MekanismButton {

	private static final int SIZE = 14;
	ApiaryDirectEjectOverlay.OverlayTarget target;

	ApiaryDirectAeOutputButton(GuiMekanism<?> gui, int x, int y,
			ApiaryDirectEjectOverlay.OverlayTarget target) {
		super(gui, x, y, SIZE, SIZE, Component.literal("M"), (element, mouseX, mouseY) -> {
			if (element instanceof ApiaryDirectAeOutputButton button && button.target != null
					&& (button.target.apiary().productivebeesgenesis$isAeItemOutputEnabled()
							|| button.target.apiary().productivebeesgenesis$isAeFluidOutputEnabled())) {
				PacketDistributor.sendToServer(new CycleAeOutputPayload(
						button.target.apiary().getBlockPos(), CycleAeOutputPayload.OutputType.APIARY_DIRECT));
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return active && target != null && target.apiary().isDirectAeOutputEnabled() ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() { return false; }
}
