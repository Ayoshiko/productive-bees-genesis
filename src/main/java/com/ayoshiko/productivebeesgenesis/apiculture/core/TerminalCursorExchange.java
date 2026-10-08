package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.function.ToIntFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 外部存取的逐玩家保管边界；鼠标栈和已知余量可操作，未决数量不可再次转移。 */
public final class TerminalCursorExchange {
	public enum Outcome { MOVED, NO_SPACE, RETAINED, UNKNOWN, INVALID }
	public record Result(Outcome outcome, int amount) { }
	record Request(ItemStack item, boolean insert, String source) {
		Request {
			if (item.isEmpty() || item.getCount() < 1 || item.getCount() > 64 || source == null || source.isBlank() || source.length() > 512)
				throw new IllegalArgumentException("Invalid cursor transfer");
			item = item.copy();
		}
		@Override public ItemStack item() { return item.copy(); }
	}
	public static boolean unknown(ServerPlayer player) { var cursor = TerminalCursor.get(player); return !cursor.available() || cursor.request != null || cursor.fluidRequest != null || cursor.energyRequest != null || cursor.chemicalRequest != null; }
	public static Result exchange(ServerPlayer player, AbstractContainerMenu menu, ItemStack wanted, boolean insert, boolean inventory,
			String source, ToIntFunction<ItemStack> external) {
		if (player.containerMenu != menu) return new Result(Outcome.INVALID, 0);
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || unknown(player)) return new Result(Outcome.UNKNOWN, 0);
		recover(player, menu, inventory);
		if (!cursor.pending.isEmpty()) return new Result(Outcome.RETAINED, 0);
		if (player.containerMenu != menu || !ItemStack.matches(cursor.item(), menu.getCarried()) || wanted.isEmpty()
				|| wanted.getCount() > 64 || wanted.getCount() < 1) return new Result(Outcome.INVALID, 0);
		var held = menu.getCarried(); int amount = wanted.getCount();
		if (insert) {
			if (!ItemStack.isSameItemSameComponents(held, wanted) || held.getCount() < amount) return new Result(Outcome.INVALID, 0);
		} else if (inventory) {
			var rest = TerminalCraftingPlan.insert(TerminalCraftingPlan.copy(player.getInventory().items), wanted);
			amount -= rest.getCount();
		} else {
			if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, wanted)) return new Result(Outcome.NO_SPACE, 0);
			amount = Math.min(amount, Math.max(0, wanted.getMaxStackSize() - held.getCount()));
		}
		if (amount <= 0) return new Result(Outcome.NO_SPACE, 0);
		var requested = wanted.copyWithCount(amount); var record = new Request(requested, insert, source);
		cursor.request = record;
		if (insert) { var rest = held.copyWithCount(held.getCount() - amount); cursor.set(rest); menu.setCarried(rest.copy()); }
		int actual;
		try {
			actual = external.applyAsInt(requested.copy());
			if (actual < 0 || actual > amount) throw new IllegalStateException("Invalid external cursor amount");
		} catch (RuntimeException | LinkageError failure) {
			com.mojang.logging.LogUtils.getLogger().error("Cursor transfer outcome unknown for {} at {}: insert={}, item={}; retained without retry",
					player.getUUID(), source, insert, requested, failure);
			if (player.containerMenu == menu) menu.broadcastFullState();
			return new Result(Outcome.UNKNOWN, 0);
		}
		// 明确的接收／拒收余量先进入持久保管；随后同步异常不能被误报成未知外部数量。
		cursor.pending = requested.copyWithCount(insert ? amount - actual : actual);
		cursor.request = null;
		if (player.containerMenu == menu) { recover(player, menu, inventory); menu.broadcastFullState(); }
		return new Result(cursor.pending.isEmpty() ? actual == 0 ? Outcome.NO_SPACE : Outcome.MOVED : Outcome.RETAINED, actual);
	}
	/** 服务器已准备的同类同数量物品重写；不调用外部库存，不创建第二份实物。 */
	public static Result rewrite(ServerPlayer player, AbstractContainerMenu menu, ItemStack expected, ItemStack replacement) {
		return replace(player, menu, expected, replacement, false);
	}
	/** 调用方已核实编码原料与结果类型；只允许同数量转换，不能用客户端物品构造结果。 */
	public static Result convert(ServerPlayer player, AbstractContainerMenu menu, ItemStack expected, ItemStack replacement) {
		return replace(player, menu, expected, replacement, true);
	}
	private static Result replace(ServerPlayer player, AbstractContainerMenu menu, ItemStack expected, ItemStack replacement, boolean changeItem) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || unknown(player)) return new Result(Outcome.UNKNOWN, 0);
		cursor.containerBusy = true;
		try {
			if (expected.isEmpty() || replacement.isEmpty() || expected.getCount() < 1 || expected.getCount() > 64
					|| replacement.getCount() != expected.getCount() || !changeItem && expected.getItem() != replacement.getItem()
					|| replacement.getCount() > replacement.getMaxStackSize() || !cursor.pending.isEmpty()
					|| player.containerMenu != menu || !ItemStack.matches(expected, menu.getCarried()) || !ItemStack.matches(expected, cursor.item()))
				return new Result(Outcome.INVALID, 0);
			cursor.set(replacement); menu.setCarried(replacement.copy()); menu.broadcastFullState();
			return new Result(Outcome.MOVED, replacement.getCount());
		} finally { cursor.containerBusy = false; }
	}
	static void recover(ServerPlayer player, AbstractContainerMenu menu, boolean inventoryFirst) {
		var cursor = TerminalCursor.get(player);
		if (!cursor.available() || cursor.request != null || cursor.pending.isEmpty() || !ItemStack.matches(cursor.item(), menu.getCarried())) return;
		if (inventoryFirst) {
			var before = TerminalCraftingPlan.copy(player.getInventory().items); var after = TerminalCraftingPlan.copy(before);
			var rest = TerminalCraftingPlan.insert(after, cursor.pending);
			cursor.pending = rest;
			for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) player.getInventory().items.set(i, after.get(i));
			player.getInventory().setChanged(); return;
		}
		var held = cursor.item();
		if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, cursor.pending)) return;
		int received = Math.min(cursor.pending.getCount(), Math.max(0, cursor.pending.getMaxStackSize() - held.getCount()));
		if (received == 0) return;
		var next = cursor.pending.copyWithCount(held.getCount() + received);
		cursor.pending = cursor.pending.copyWithCount(cursor.pending.getCount() - received);
		cursor.set(next); menu.setCarried(next.copy());
	}
	private TerminalCursorExchange() { }
}
