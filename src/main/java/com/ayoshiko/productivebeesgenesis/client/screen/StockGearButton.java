package com.ayoshiko.productivebeesgenesis.client.screen;

import appeng.client.gui.Icon;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

/** Compact MEK-style gear button using AE2's familiar stock-configuration icon. */
final class StockGearButton extends MekanismButton {

	private boolean networkStock;

	StockGearButton(IGuiWrapper gui, int x, int y, int size, int slotIndex,
			IntConsumer configureCallback, IntConsumer toggleCallback) {
		super(gui, x, y, size, size, Component.empty(), (element, mouseX, mouseY) -> {
			if (Screen.hasShiftDown()) {
				toggleCallback.accept(slotIndex);
			} else {
				configureCallback.accept(slotIndex);
			}
			return true;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		visible = false;
	}

	void setNetworkStock(boolean networkStock) {
		this.networkStock = networkStock;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) return;
		super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
		Icon icon = networkStock || isMouseOver(mouseX, mouseY) ? Icon.COG : Icon.COG_DISABLED;
		icon.getBlitter()
				.dest(relativeX + 1, relativeY + 1, width - 2, height - 2)
				.zOffset(4)
				.blit(guiGraphics);
		if (networkStock) {
			guiGraphics.drawString(Minecraft.getInstance().font, "∞",
					relativeX + 9, relativeY + 7, 0x00FF00, true);
		}
	}
}
