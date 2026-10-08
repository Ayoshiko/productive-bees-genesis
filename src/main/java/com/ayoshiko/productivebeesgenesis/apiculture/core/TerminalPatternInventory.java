package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 批量原位改写：先核对并复制全部结果，再发布；没有外部存储或掉落回退。 */
public final class TerminalPatternInventory {
	public record Replacement(int slot, ItemStack before, ItemStack after) {
		public Replacement { before = before.copy(); after = after.copy(); }
		@Override public ItemStack before() { return before.copy(); }
		@Override public ItemStack after() { return after.copy(); }
	}
	public static TerminalCursorExchange.Result replace(ServerPlayer player, AbstractContainerMenu menu, ItemStack carried, List<Replacement> replacements) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Pattern inventory belongs to the server thread");
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || TerminalCursorExchange.unknown(player)) return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.UNKNOWN, 0);
		cursor.containerBusy = true;
		try {
			if (replacements.isEmpty() || replacements.size() > 36 || player.containerMenu != menu || !menu.stillValid(player)
					|| !cursor.pending.isEmpty() || !ItemStack.matches(carried, menu.getCarried()) || !ItemStack.matches(carried, cursor.item())) return invalid();
			var prepared = new ArrayList<ItemStack>(replacements.size()); long slots = 0; int count = 0;
			for (var change : replacements) {
				int slot = change.slot(); var before = change.before(); var after = change.after();
				if (slot < 0 || slot >= 36 || (slots & 1L << slot) != 0 || before.isEmpty() || after.isEmpty()
						|| before.getCount() < 1 || before.getCount() > Math.min(64, before.getMaxStackSize())
						|| before.getItem() != after.getItem() || before.getCount() != after.getCount() || after.getCount() > after.getMaxStackSize()
						|| !ItemStack.matches(before, player.getInventory().items.get(slot))) return invalid();
				slots |= 1L << slot; prepared.add(after); count += after.getCount();
			}
			// NonNullList 的已校验位置直接赋值；全部发布后才通知菜单，回调不能观察半次改写。
			for (int i = 0; i < replacements.size(); i++) player.getInventory().items.set(replacements.get(i).slot(), prepared.get(i));
			player.getInventory().setChanged(); menu.broadcastFullState();
			return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.MOVED, count);
		} finally { cursor.containerBusy = false; }
	}
	private static TerminalCursorExchange.Result invalid() { return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0); }
	private TerminalPatternInventory() { }
}
