package com.ayoshiko.productivebeesgenesis.client.screen;

import appeng.client.gui.Icon;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
	 * 全配置页全局齿轮按钮（无需标记物品即可使用）
	 * <br/>
	 * 普通点击：打开应用到全部直连条目的数量编辑器；
	 * Shift+点击：一键切换全部直连条目的无限拉取状态。
	 */
final class GlobalGearButton extends MekanismButton {

	private boolean unlimitedAllFallback;

	GlobalGearButton(IGuiWrapper gui, int x, int y, Runnable configureCallback, Runnable toggleCallback) {
		super(gui, x, y, AeInputConfigLayout.GEAR_SIZE, AeInputConfigLayout.GEAR_SIZE, Component.empty(),
			(element, mouseX, mouseY) -> {
			if (Screen.hasShiftDown()) {
				toggleCallback.run();
			} else {
				configureCallback.run();
			}
			return true;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		visible = true;
		active = true;
	}

	void setUnlimitedAllFallback(boolean active) {
		this.unlimitedAllFallback = active;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) return;
		super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
		Icon icon = unlimitedAllFallback || isMouseOver(mouseX, mouseY) ? Icon.COG : Icon.COG_DISABLED;
		icon.getBlitter()
				.dest(relativeX + 1, relativeY + 1, width - 2, height - 2)
				.zOffset(4)
				.blit(guiGraphics);
		if (unlimitedAllFallback) {
			guiGraphics.drawString(Minecraft.getInstance().font, "\u221E",
					relativeX + 9, relativeY + 7, 0x00FF00, true);
		}
	}
}
