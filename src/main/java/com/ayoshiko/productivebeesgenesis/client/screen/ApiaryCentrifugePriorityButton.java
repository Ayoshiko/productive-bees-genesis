package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Toggles centrifuge-first routing for products that a connected centrifuge can process. */
final class ApiaryCentrifugePriorityButton extends MekanismButton {

	private static final int SIZE = 14;
	ApiaryDirectEjectOverlay.OverlayTarget target;

	ApiaryCentrifugePriorityButton(GuiMekanism<?> gui, int x, int y,
			ApiaryDirectEjectOverlay.OverlayTarget target) {
		super(gui, x, y, SIZE, SIZE, Component.literal("P"), (element, mouseX, mouseY) -> {
			if (element instanceof ApiaryCentrifugePriorityButton button && button.target != null) {
				PacketDistributor.sendToServer(new CycleAeOutputPayload(
						button.target.apiary().getBlockPos(),
						CycleAeOutputPayload.OutputType.APIARY_CENTRIFUGE_PRIORITY));
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return target != null && target.apiary().isCentrifugePriorityEnabled() ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() { return false; }
}
