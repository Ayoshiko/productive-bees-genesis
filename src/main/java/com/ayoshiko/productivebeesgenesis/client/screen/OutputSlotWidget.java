package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.AeInputOutputSlotPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.SlotType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
	 * AE2LT overloaded-interface style third-row output slot.
	 * <br/>
	 * Displays the configured direct entry with the pullable network amount and
	 * proxies player interaction straight to the ME network (extract into cursor /
	 * insert carried stack), mirroring {@code OverloadedInterfaceLogic.ProxiedStorageInv}.
	 */
public final class OutputSlotWidget extends GuiElement {

	public static final int SIZE = 18;

	private final BlockPos pos;
	private final int pageSlotIndex;
	private int globalSlotIndex;
	private ItemStack icon = ItemStack.EMPTY;
	private long amount;
	private boolean unlimited;
	private boolean hasEntry;

	public OutputSlotWidget(IGuiWrapper gui, int x, int y, BlockPos pos, int pageSlotIndex) {
		super(gui, x, y, SIZE, SIZE);
		this.pos = pos;
		this.pageSlotIndex = pageSlotIndex;
		this.globalSlotIndex = pageSlotIndex;
	}

	/** Refreshes the displayed direct entry for the current page. */
	public void setDirectEntry(ItemStack icon, long amount, boolean unlimited, int globalSlotIndex) {
		this.icon = icon;
		this.amount = Math.max(0L, amount);
		this.unlimited = unlimited;
		this.globalSlotIndex = globalSlotIndex;
		this.hasEntry = !icon.isEmpty();
	}

	public void clear() {
		this.icon = ItemStack.EMPTY;
		this.amount = 0L;
		this.unlimited = false;
		this.hasEntry = false;
	}

	public boolean hasEntry() {
		return hasEntry;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) return;
		guiGraphics.blit(SlotType.NORMAL.getTexture(), relativeX, relativeY, 0, 0, SIZE, SIZE, SIZE, SIZE);
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		if (!visible || !hasEntry) return;
		guiGraphics.renderFakeItem(icon, relativeX + 1, relativeY + 1);
		if (amount > 0L) {
			drawAmount(guiGraphics, Minecraft.getInstance().font, amount);
		}
	}

	/**
	 * 绘制槽位数量数字，复用 AE2 蜜脾拉取窗口的固定字号样式。
	 */
	private void drawAmount(GuiGraphics guiGraphics, Font font, long amount) {
		LargeStackCountRenderer.renderCountAt(guiGraphics, font, relativeX, relativeY, amount);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!visible || !hasEntry || !isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (button != 0 && button != 1) return false;
		boolean shift = Screen.hasShiftDown();
		boolean rightClick = button == 1;
		PacketDistributor.sendToServer(new AeInputOutputSlotPayload(pos, globalSlotIndex, shift, rightClick));
		return true;
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		// No default button text; the icon + amount overlay is drawn in drawBackground.
	}

	@Override
	@NotNull
	public Component getMessage() {
		return hasEntry ? icon.getHoverName() : Component.empty();
	}

}
