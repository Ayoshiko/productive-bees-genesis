package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 流体和 FE 共用的单件容器交付边界，不调用背包溢出掉落入口。 */
final class TerminalContainerItems {
	static boolean ready(ServerPlayer player, AbstractContainerMenu menu, TerminalCursor cursor) {
		if (!cursor.available() || player.containerMenu != menu) return false;
		TerminalCursorExchange.recover(player, menu, false);
		return cursor.pending.isEmpty() && ItemStack.matches(cursor.item(), menu.getCarried()) && !menu.getCarried().isEmpty();
	}
	static boolean unchanged(ServerPlayer player, AbstractContainerMenu menu, TerminalCursor cursor, ItemStack held) {
		return player.containerMenu == menu && cursor.available() && cursor.pending.isEmpty()
				&& ItemStack.matches(held, menu.getCarried()) && ItemStack.matches(held, cursor.item());
	}
	static boolean fits(ServerPlayer player, ItemStack held, ItemStack output, boolean inventory) {
		if (!inventory && (held.getCount() == 1 || ItemStack.isSameItemSameComponents(held, output) && held.getCount() <= output.getMaxStackSize())) return true;
		return TerminalCraftingPlan.insert(TerminalCraftingPlan.copy(player.getInventory().items), output).isEmpty();
	}
	static void replaceOne(AbstractContainerMenu menu, TerminalCursor cursor, ItemStack held, ItemStack output) {
		var rest = held.copyWithCount(held.getCount() - 1); cursor.set(rest); menu.setCarried(rest.copy()); cursor.pending = output.copy();
	}
	private TerminalContainerItems() { }
}
