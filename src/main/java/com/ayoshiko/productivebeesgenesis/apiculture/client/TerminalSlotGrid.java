package com.ayoshiko.productivebeesgenesis.apiculture.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 18px 连续凹槽网格，共用分隔线和外缘，不把物品格绘制为独立按钮。 */
public final class TerminalSlotGrid {
	public static void render(GuiGraphics graphics, AbstractContainerMenu menu, int left, int top) {
		if (menu.slots.size() < 36) return;
		var main = menu.slots.getFirst();
		grid(graphics, left + main.x, top + main.y, 9, 3);
		var hotbar = menu.slots.get(27);
		grid(graphics, left + hotbar.x, top + hotbar.y, 9, 1);
		if (menu.slots.size() >= 46 && menu.slots.get(36).isActive()) {
			var crafting = menu.slots.get(36); grid(graphics, left + crafting.x, top + crafting.y, 3, 3);
			var result = menu.slots.get(45); grid(graphics, left + result.x, top + result.y, 1, 1);
		}
	}
	public static void lockedTooltip(GuiGraphics g, net.minecraft.client.gui.Font font, AbstractContainerMenu menu, int left, int top, int mouseX, int mouseY) {
		if (!(menu instanceof com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCraftingMenu.Host host)) return;
		for (var slot : menu.slots) if (slot.index < 36 && host.lockedNativeStack(slot.getItem())
				&& mouseX >= left + slot.x && mouseX < left + slot.x + 16 && mouseY >= top + slot.y && mouseY < top + slot.y + 16) {
			var lines = new java.util.ArrayList<>(net.minecraft.client.gui.screens.Screen.getTooltipFromItem(net.minecraft.client.Minecraft.getInstance(), slot.getItem()));
			lines.add(net.minecraft.network.chat.Component.translatable("screen.productivebeesgenesis.network.terminal.active_device"));
			g.renderComponentTooltip(font, lines, mouseX, mouseY); return;
		}
	}
	private static void grid(GuiGraphics g, int x, int y, int columns, int rows) {
		int width = columns * 18, height = rows * 18;
		g.fill(x - 1, y - 1, x + width - 1, y + height - 1, 0xffc2c8cc);
		g.fill(x - 1, y - 1, x + width - 2, y + height - 2, 0xff46525b);
		for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
			int sx = x + column * 18, sy = y + row * 18;
			g.fill(sx, sy, sx + 16, sy + 16, 0xff7b8992);
			g.fill(sx, sy + 16, sx + 17, sy + 17, 0xffc2c8cc);
			g.fill(sx + 16, sy, sx + 17, sy + 16, 0xffc2c8cc);
		}
	}
	private TerminalSlotGrid() { }
}
