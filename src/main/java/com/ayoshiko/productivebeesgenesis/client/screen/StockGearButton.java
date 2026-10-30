package com.ayoshiko.productivebeesgenesis.client.screen;

import appeng.client.gui.Icon;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import com.ayoshiko.productivebeesgenesis.client.screen.ForgeGuiCompat.MekanismButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

/** Compact MEK-style gear button using AE2's familiar stock-configuration icon. */
final class StockGearButton extends MekanismButton {

	private boolean networkStock;
	private boolean unlimited;
	private final int slotIndex;

	StockGearButton(IGuiWrapper gui, int x, int y, int size, int slotIndex,
			IntConsumer configureCallback, IntConsumer toggleUnlimitedCallback,
			IntConsumer toggleNetworkStockCallback, IntConsumer configureReserveCallback) {
		super(gui, x, y, size, size, Component.empty(), (element, mouseX, mouseY) -> {
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		visible = false;
		this.configureCallback = configureCallback;
		this.toggleUnlimitedCallback = toggleUnlimitedCallback;
		this.toggleNetworkStockCallback = toggleNetworkStockCallback;
		this.configureReserveCallback = configureReserveCallback;
		this.slotIndex = slotIndex;
	}

	private final IntConsumer configureCallback;
	private final IntConsumer toggleUnlimitedCallback;
	private final IntConsumer toggleNetworkStockCallback;
	private final IntConsumer configureReserveCallback;

	@Override
	public mekanism.client.gui.element.GuiElement mouseClickedNested(double mouseX, double mouseY, int button) {
		if (!visible || !active || !isMouseOver(mouseX, mouseY)) return null;
		if (button != 0 && button != 1) return null;
		if (Screen.hasShiftDown() && button == 1) {
			configureReserveCallback.accept(slotIndex);
		} else if (Screen.hasShiftDown()) {
			toggleUnlimitedCallback.accept(slotIndex);
		} else if (button == 1) {
			toggleNetworkStockCallback.accept(slotIndex);
		} else {
			configureCallback.accept(slotIndex);
		}
		return this;
	}

	void setNetworkStock(boolean networkStock) {
		this.networkStock = networkStock;
	}

	void setUnlimited(boolean unlimited) {
		this.unlimited = unlimited;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) return;
		super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
		Icon icon = networkStock || unlimited || isMouseOver(mouseX, mouseY)
				? Icon.WRENCH : Icon.WRENCH_DISABLED;
		icon.getBlitter()
				.dest(relativeX + 1, relativeY + 1, width - 2, height - 2)
				.blit(guiGraphics);
		if (unlimited) {
			guiGraphics.drawString(Minecraft.getInstance().font, "∞",
					relativeX + 9, relativeY + 7, 0x00FF00, true);
		} else if (networkStock) {
			guiGraphics.fill(relativeX + 5, relativeY + 13, relativeX + 11, relativeY + 15,
					0xFFFFD84A);
		}
	}
}
