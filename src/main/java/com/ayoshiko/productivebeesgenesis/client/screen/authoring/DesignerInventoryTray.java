package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 背包仅供展示和复制声明；虚拟拖拽从不取得或修改真实物品的所有权。 */
final class DesignerInventoryTray {
	private int x;
	private int y;
	private ItemStack dragging = ItemStack.EMPTY;

	void layout(int frameLeft, int frameWidth, int height) {
		x = frameLeft + (frameWidth - 162) / 2;
		y = height - 86;
	}

	Rect2i bounds() {
		return new Rect2i(x - 7, y - 14, 176, 96);
	}

	Rect2i slotBounds(int index) {
		if (index < 0 || index >= 36) {
			throw new IllegalArgumentException("Invalid inventory slot: " + index);
		}
		int row = index < 9 ? 3 : (index - 9) / 9;
		return new Rect2i(x + index % 9 * 18, y + row * 18 + (row == 3 ? 4 : 0), 18, 18);
	}

	int top() {
		return y;
	}

	boolean press(double mouseX, double mouseY, int button) {
		Minecraft mc = Minecraft.getInstance();
		if (button != 0 || mc.player == null) {
			return false;
		}
		for (int index = 0; index < 36; index++) {
			if (slotBounds(index).contains((int) mouseX, (int) mouseY)) {
				dragging = mc.player.getInventory().items.get(index).copyWithCount(1);
				return true;
			}
		}
		return false;
	}

	boolean isDragging() {
		return !dragging.isEmpty();
	}

	ItemStack dragged() {
		return dragging;
	}

	ItemStack release() {
		ItemStack result = dragging;
		dragging = ItemStack.EMPTY;
		return result;
	}

	void render(GuiGraphics graphics, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		DesignerTheme.panel(graphics, "panel", x - 7, y - 14, 176, 96);
		graphics.drawString(mc.font, Component.translatable("container.inventory"), x + 1, y - 10, DesignerFormScreen.MUTED, false);
		ItemStack hovered = ItemStack.EMPTY;
		for (int index = 0; index < 36; index++) {
			Rect2i slot = slotBounds(index);
			DesignerTheme.panel(graphics, "slot", slot.getX(), slot.getY(), 18, 18);
			ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getInventory().items.get(index);
			graphics.renderItem(stack, slot.getX() + 1, slot.getY() + 1);
			graphics.renderItemDecorations(mc.font, stack, slot.getX() + 1, slot.getY() + 1);
			if (slot.contains(mouseX, mouseY)) {
				hovered = stack;
				graphics.fill(slot.getX() + 1, slot.getY() + 1, slot.getX() + 17, slot.getY() + 17, 0x30FFFFFF);
				graphics.renderOutline(slot.getX(), slot.getY(), 18, 18, DesignerFormScreen.ACCENT);
			}
		}
		if (isDragging()) {
			graphics.pose().pushPose();
			graphics.pose().translate(0, 0, 400);
			graphics.renderItem(dragging, mouseX - 8, mouseY - 8);
			graphics.pose().popPose();
		} else if (!hovered.isEmpty()) {
			graphics.renderTooltip(mc.font, hovered, mouseX, mouseY);
		}
	}
}
