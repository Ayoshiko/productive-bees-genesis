package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.function.ToIntFunction;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 外部存取的逐玩家保管边界；鼠标栈和已知余量可操作，未决数量不可再次转移。 */
public final class TerminalCursorExchange {
	public enum Outcome { MOVED, NO_SPACE, RETAINED, UNKNOWN, INVALID }
	public record Result(Outcome outcome, int amount) { }
	record Observed(ItemStack item, int amount) {
		Observed {
			if (item.isEmpty() || item.getCount() != 1 || amount <= 0) throw new IllegalArgumentException("Invalid observed cursor return");
			item = item.copy();
		}
		@Override public ItemStack item() { return item.copy(); }
	}
	record Request(ItemStack item, boolean insert, String source, Observed observed) {
		Request(ItemStack item, boolean insert, String source) { this(item, insert, source, null); }
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
		cursor.containerBusy = true;
		try { return exchangeLocked(player, menu, wanted, insert, inventory, source, external); }
		finally { cursor.containerBusy = false; }
	}
	/** 库存回调返回插入余量或实际提取栈；异常返回仅作隔离证据，不能直接退款或交付。 */
	public static Result exchangeItems(ServerPlayer player, AbstractContainerMenu menu, ItemStack wanted, boolean insert, boolean inventory,
			String source, Function<ItemStack, ItemStack> external) {
		return exchange(player, menu, wanted, insert, inventory, source, requested -> transferItems(TerminalCursor.get(player), requested, insert, external));
	}
	static int transferItems(TerminalCursor cursor, ItemStack requested, boolean insert, Function<ItemStack, ItemStack> external) {
		var expected = requested.copy(); var returned = external.apply(requested); var record = cursor.request;
		if (returned != null && !returned.isEmpty())
			cursor.request = new Request(record.item(), record.insert(), record.source(), new Observed(returned.copyWithCount(1), returned.getCount()));
		if (returned == null || !ItemStack.matches(expected, requested) || !returned.isEmpty()
				&& (!ItemStack.isSameItemSameComponents(expected, returned) || returned.getCount() > expected.getCount()))
			throw new IllegalStateException("Invalid external cursor stack; observed return retained");
		return insert ? expected.getCount() - returned.getCount() : returned.getCount();
	}
	private static Result exchangeLocked(ServerPlayer player, AbstractContainerMenu menu, ItemStack wanted, boolean insert, boolean inventory,
			String source, ToIntFunction<ItemStack> external) {
		var cursor = TerminalCursor.get(player);
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
		return complete(player, menu, requested, insert, inventory, source, external);
	}
	/** 调用前请求中的资产已由鼠标或真实背包移交；这里只结算外部实际结果。 */
	private static Result complete(ServerPlayer player, AbstractContainerMenu menu, ItemStack requested, boolean insert, boolean inventory,
			String source, ToIntFunction<ItemStack> external) {
		return complete(player, menu, requested, insert, source, external, () -> recover(player, menu, inventory));
	}
	private static Result complete(ServerPlayer player, AbstractContainerMenu menu, ItemStack requested, boolean insert,
			String source, ToIntFunction<ItemStack> external, Runnable deliver) {
		var cursor = TerminalCursor.get(player); int amount = requested.getCount();
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
		if (player.containerMenu == menu) { deliver.run(); menu.broadcastFullState(); }
		return new Result(cursor.pending.isEmpty() ? actual == 0 ? Outcome.NO_SPACE : Outcome.MOVED : Outcome.RETAINED, actual);
	}
	/** 补足服务器选中的槽；已取回但失去原槽资格的物品留在原 pending。 */
	static Result restockSlot(ServerPlayer player, AbstractContainerMenu menu, int slot, ItemStack expected, int target,
			String source, ToIntFunction<ItemStack> external, BooleanSupplier current) {
		return restockSlot(player, menu, slot, expected, expected, target, source, external, current);
	}
	static Result restockSlot(ServerPlayer player, AbstractContainerMenu menu, int slot, ItemStack expected, ItemStack template, int target,
			String source, ToIntFunction<ItemStack> external, BooleanSupplier current) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || unknown(player)) return new Result(Outcome.UNKNOWN, 0);
		cursor.containerBusy = true;
		try {
			if (!WirelessPickRequest.validSlot(slot) || !WirelessRestockSlots.validTarget(target)) return new Result(Outcome.INVALID, 0);
			var inventory = player.getInventory();
			var destination = slot == 40 ? inventory.offhand : inventory.items; int index = slot == 40 ? 0 : slot;
			var snapshot = expected.copy(); var sample = template.copyWithCount(1);
			if (!current.getAsBoolean() || player.containerMenu != menu || player.getInventory() != inventory
					|| !menu.getCarried().isEmpty() || !cursor.item().isEmpty() || !cursor.pending.isEmpty()
					|| !ItemStack.matches(snapshot, destination.get(index))) return new Result(Outcome.INVALID, 0);
			int missing = WirelessRestockSlots.missing(snapshot, sample, target);
			if (missing <= 0) return new Result(Outcome.NO_SPACE, 0);
			var wanted = sample.copyWithCount(missing);
			cursor.request = new Request(wanted, false, source);
			return complete(player, menu, wanted, false, source, external, () -> {
				if (!current.getAsBoolean() || player.containerMenu != menu || player.getInventory() != inventory
						|| !menu.getCarried().isEmpty() || !cursor.item().isEmpty()) return;
				var delivery = WirelessRestockSlots.deliver(snapshot, destination.get(index), sample, cursor.pending, target);
				if (delivery == null) return;
				// 原生列表与保管量一起提交；两次写入之间没有外部回调。
				cursor.pending = delivery.remainder(); destination.set(index, delivery.slot());
				inventory.setChanged();
			});
		} finally { cursor.containerBusy = false; }
	}
	/** 仅从仍与服务端快照一致的主背包取源；拒收回背包，未知量留在原 Request。 */
	static Result depositInventory(ServerPlayer player, AbstractContainerMenu menu, java.util.List<ItemStack> expected,
			ItemStack wanted, String source, ToIntFunction<ItemStack> external) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || unknown(player)) return new Result(Outcome.UNKNOWN, 0);
		cursor.containerBusy = true;
		try {
			if (player.containerMenu != menu || !menu.getCarried().isEmpty() || !cursor.item().isEmpty() || !cursor.pending.isEmpty()
					|| expected.size() != 36 || wanted.isEmpty() || wanted.getCount() > 64 || wanted.getItem() instanceof WirelessTerminalItem
					|| !ItemStack.listMatches(expected, player.getInventory().items)) return new Result(Outcome.INVALID, 0);
			var after = TerminalCraftingPlan.copy(expected); int needed = wanted.getCount();
			for (int i = 35; i >= 0 && needed > 0; i--) {
				var stack = after.get(i);
				if (!ItemStack.isSameItemSameComponents(stack, wanted)) continue;
				int amount = Math.min(needed, stack.getCount()); stack.shrink(amount); needed -= amount;
			}
			if (needed != 0 || !ItemStack.listMatches(expected, player.getInventory().items)) return new Result(Outcome.INVALID, 0);
			var record = new Request(wanted, true, source);
			// 以下发布只写已准备的原生 36 格，无外部库存／世界回调；先撤销可操作来源再访问 ME。
			cursor.request = record;
			for (int i = 0; i < 36; i++) if (!ItemStack.matches(expected.get(i), after.get(i))) player.getInventory().items.set(i, after.get(i));
			player.getInventory().setChanged();
			return complete(player, menu, wanted, true, true, source, external);
		} finally { cursor.containerBusy = false; }
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
