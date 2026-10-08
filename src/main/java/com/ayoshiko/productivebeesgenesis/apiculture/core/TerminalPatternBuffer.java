package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.*;

/** 玩家拥有的九格实物缓冲；只在服务器线程准备副本和发布，不借用材料账户。 */
public final class TerminalPatternBuffer {
	public static final int SLOTS = 9;
	public static final class Snapshot {
		private final TerminalCursor owner;
		private final List<ItemStack> state;
		private Snapshot(TerminalCursor owner) { this.owner = owner; state = owner.patternBuffer; }
		public ItemStack item(int slot) { return state.get(slot).copy(); }
		public boolean current(ServerPlayer player) { return TerminalCursor.get(player) == owner && owner.available() && owner.patternBuffer == state; }
	}
	static List<ItemStack> empty() { return Collections.nCopies(SLOTS, ItemStack.EMPTY); }
	static List<ItemStack> read(Tag value, HolderLookup.Provider registries) {
		if (!(value instanceof ListTag list) || list.size() != SLOTS) throw new IllegalArgumentException("Invalid pattern buffer");
		var items = new ArrayList<ItemStack>(SLOTS);
		for (var entry : list) {
			if (!(entry instanceof CompoundTag raw)) throw new IllegalArgumentException("Invalid pattern buffer slot");
			var item = raw.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, raw).orElseThrow();
			if (!item.saveOptional(registries).equals(raw) || !item.isEmpty() && (item.getCount() < 1 || item.getCount() > Math.min(64, item.getMaxStackSize())))
				throw new IllegalArgumentException("Lossy pattern buffer slot");
			items.add(item);
		}
		return List.copyOf(items);
	}
	static ListTag write(List<ItemStack> items, HolderLookup.Provider registries) {
		var result = new ListTag(); for (var item : items) result.add(item.saveOptional(registries)); return result;
	}
	public static Snapshot capture(ServerPlayer player) { var cursor = TerminalCursor.get(player); return cursor.available() ? new Snapshot(cursor) : null; }
	public static Result exchange(ServerPlayer player, AbstractContainerMenu menu, Snapshot snapshot, int slot, int amount, boolean insert, boolean inventory) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || !cursor.available() || !inventory && (unknown(player) || !cursor.pending.isEmpty())) return new Result(Outcome.UNKNOWN, 0);
		if (slot < 0 || slot >= SLOTS || amount < 1 || amount > 64 || snapshot == null || !snapshot.current(player)
				|| player.containerMenu != menu || !menu.stillValid(player)) return new Result(Outcome.INVALID, 0);
		cursor.containerBusy = true;
		try {
			var carried = menu.getCarried().copy();
			if (!inventory && !ItemStack.matches(carried, cursor.item())) return new Result(Outcome.INVALID, 0);
			var buffer = TerminalCraftingPlan.copy(snapshot.state); var old = buffer.get(slot); int moved;
			var nextCarried = carried.copy(); List<ItemStack> beforeInventory = null, nextInventory = null;
			if (insert) {
				if (inventory || carried.isEmpty()) return new Result(Outcome.INVALID, 0);
				var probe = carried.copyWithCount(1); var expected = probe.copy();
				if (!MeBridgeIntegration.bufferPattern(probe) || !ItemStack.matches(expected, probe)) return new Result(Outcome.INVALID, 0);
				if (!old.isEmpty() && !ItemStack.isSameItemSameComponents(old, carried)) return new Result(Outcome.NO_SPACE, 0);
				moved = Math.min(Math.min(amount, carried.getCount()), Math.max(0, Math.min(64, carried.getMaxStackSize()) - old.getCount()));
				if (moved <= 0) return new Result(Outcome.NO_SPACE, 0);
				buffer.set(slot, carried.copyWithCount(old.getCount() + moved)); nextCarried.shrink(moved);
			} else {
				if (old.isEmpty()) return new Result(Outcome.NO_SPACE, 0);
				moved = Math.min(amount, old.getCount());
				if (inventory) {
					beforeInventory = TerminalCraftingPlan.copy(player.getInventory().items); nextInventory = TerminalCraftingPlan.copy(beforeInventory);
					moved -= TerminalCraftingPlan.insert(nextInventory, old.copyWithCount(moved)).getCount();
				} else {
					if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, old)) return new Result(Outcome.NO_SPACE, 0);
					moved = Math.min(moved, Math.max(0, Math.min(64, old.getMaxStackSize()) - carried.getCount()));
					nextCarried = old.copyWithCount(carried.getCount() + moved);
				}
				if (moved <= 0) return new Result(Outcome.NO_SPACE, 0);
				buffer.set(slot, old.copyWithCount(old.getCount() - moved));
			}
			var nextBuffer = List.copyOf(buffer);
			// 样板识别可能进入附属解码器；返回后再核对真实菜单、鼠标和缓冲根。
			if (!snapshot.current(player) || player.containerMenu != menu || !menu.stillValid(player)
					|| inventory && !sameInventory(player, beforeInventory)
					|| !inventory && (!ItemStack.matches(carried, menu.getCarried()) || !ItemStack.matches(carried, cursor.item()) || unknown(player))) return new Result(Outcome.INVALID, 0);
			cursor.patternBuffer = nextBuffer;
			if (inventory) publishInventory(player, nextInventory);
			else { cursor.set(nextCarried); menu.setCarried(nextCarried.copy()); }
			menu.broadcastFullState(); return new Result(Outcome.MOVED, moved);
		} finally { cursor.containerBusy = false; }
	}
	/** 已确认的批量样板改写；保留张数及未选择格，一次发布整个缓冲根。 */
	public static Result replace(ServerPlayer player, AbstractContainerMenu menu, ItemStack carried, Snapshot snapshot, List<TerminalPatternInventory.Replacement> replacements) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy || unknown(player)) return new Result(Outcome.UNKNOWN, 0);
		cursor.containerBusy = true;
		try {
			if (snapshot == null || !snapshot.current(player) || replacements.isEmpty() || replacements.size() > SLOTS
					|| player.containerMenu != menu || !menu.stillValid(player) || !cursor.pending.isEmpty()
					|| !ItemStack.matches(carried, menu.getCarried()) || !ItemStack.matches(carried, cursor.item())) return new Result(Outcome.INVALID, 0);
			var next = TerminalCraftingPlan.copy(snapshot.state); int seen = 0, count = 0;
			for (var change : replacements) {
				int slot = change.slot(); var before = change.before(); var after = change.after();
				if (slot < 0 || slot >= SLOTS || (seen & 1 << slot) != 0 || before.isEmpty() || after.isEmpty()
						|| before.getCount() < 1 || before.getCount() > Math.min(64, before.getMaxStackSize())
						|| before.getItem() != after.getItem() || before.getCount() != after.getCount()
						|| after.getCount() < 1 || after.getCount() > Math.min(64, after.getMaxStackSize())
						|| !ItemStack.matches(before, snapshot.state.get(slot))) return new Result(Outcome.INVALID, 0);
				seen |= 1 << slot; next.set(slot, after); count += after.getCount();
			}
			// 全部校验及结果复制后只发布一个九格根；不移动鼠标或背包，不调用外部库存。
			cursor.patternBuffer = List.copyOf(next); menu.broadcastFullState(); return new Result(Outcome.MOVED, count);
		} finally { cursor.containerBusy = false; }
	}
	/** 只回收已知缓冲物品；未决外部请求独立保留，满背包余量仍在原格。 */
	public static Result returnAll(ServerPlayer player, AbstractContainerMenu menu) {
		var cursor = TerminalCursor.get(player);
		if (!cursor.available() || cursor.containerBusy) return new Result(Outcome.UNKNOWN, 0);
		if (player.containerMenu != menu || !menu.stillValid(player)) return new Result(Outcome.INVALID, 0);
		cursor.containerBusy = true;
		try {
			var state = cursor.patternBuffer; var before = TerminalCraftingPlan.copy(player.getInventory().items);
			var buffer = new ArrayList<ItemStack>(SLOTS); var inventory = TerminalCraftingPlan.copy(before); int moved = 0;
			for (var item : state) { var rest = TerminalCraftingPlan.insert(inventory, item); moved += item.getCount() - rest.getCount(); buffer.add(rest); }
			if (moved == 0) return new Result(Outcome.NO_SPACE, 0);
			var next = List.copyOf(buffer);
			if (!cursor.available() || cursor.patternBuffer != state || player.containerMenu != menu || !menu.stillValid(player) || !sameInventory(player, before)) return new Result(Outcome.INVALID, 0);
			cursor.patternBuffer = next; publishInventory(player, inventory); menu.broadcastFullState();
			return new Result(next.stream().anyMatch(item -> !item.isEmpty()) ? Outcome.RETAINED : Outcome.MOVED, moved);
		} finally { cursor.containerBusy = false; }
	}
	private static void publishInventory(ServerPlayer player, List<ItemStack> inventory) {
		for (int i = 0; i < 36; i++) if (!ItemStack.matches(player.getInventory().items.get(i), inventory.get(i))) player.getInventory().items.set(i, inventory.get(i));
		player.getInventory().setChanged();
	}
	private static boolean sameInventory(ServerPlayer player, List<ItemStack> expected) {
		for (int i = 0; i < 36; i++) if (!ItemStack.matches(player.getInventory().items.get(i), expected.get(i))) return false;
		return true;
	}
	private TerminalPatternBuffer() { }
}
