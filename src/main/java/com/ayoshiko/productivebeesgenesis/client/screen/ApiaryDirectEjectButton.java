package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.ToggleApiaryDirectEjectPayload;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Mekanism 侧面配置 ITEM 页中的蜂箱快速直连切换按钮。 */
final class ApiaryDirectEjectButton extends MekanismButton {

	private static final int SIZE = 14;
	ApiaryDirectEjectOverlay.OverlayTarget target;

	ApiaryDirectEjectButton(GuiMekanism<?> gui, int x, int y,
			ApiaryDirectEjectOverlay.OverlayTarget target) {
		super(gui, x, y, SIZE, SIZE, Component.literal("D"), (element, mouseX, mouseY) -> {
			if (element instanceof ApiaryDirectEjectButton button && button.target != null) {
				PacketDistributor.sendToServer(new ToggleApiaryDirectEjectPayload(
						button.target.apiary().getBlockPos()));
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return target != null && target.apiary().isDirectEjectEnabled() ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
