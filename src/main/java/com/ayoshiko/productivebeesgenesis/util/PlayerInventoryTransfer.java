package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 服务器线程上的原生玩家背包计划；容量不足时不改槽位，也不生成掉落物。 */
public final class PlayerInventoryTransfer {
	private PlayerInventoryTransfer() {
	}

	@Nullable
	public static Plan prepare(Player player, List<ItemStack> items) {
		Plan plan = new Plan(player.getInventory());
		return plan.insertAll(items) ? plan : null;
	}

	public static final class Plan {
		private final Inventory inventory;
		private final List<ItemStack> before;
		private final List<ItemStack> after;
		private boolean used;
		private boolean committed;

		private Plan(Inventory inventory) {
			this.inventory = inventory;
			this.before = new ArrayList<>(inventory.getContainerSize());
			this.after = new ArrayList<>(inventory.getContainerSize());
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack stack = inventory.getItem(slot);
				before.add(stack.copy());
				after.add(stack.copy());
			}
		}

		public long countItem(Item item) {
			long count = 0;
			for (ItemStack stack : after) {
				if (stack.is(item)) {
					count += stack.getCount();
				}
			}
			return count;
		}

		public boolean consume(Item item, int amount) {
			if (used || amount < 0 || countItem(item) < amount) {
				return false;
			}
			int remaining = amount;
			for (int slot = 0; slot < after.size() && remaining > 0; slot++) {
				ItemStack stack = after.get(slot);
				if (stack.is(item)) {
					int taken = Math.min(remaining, stack.getCount());
					stack.shrink(taken);
					remaining -= taken;
				}
			}
			return true;
		}

		/** 仅修改计划；任一物品放不下时，调用方必须丢弃整个计划。 */
		public boolean insertAll(List<ItemStack> items) {
			if (used) {
				return false;
			}
			for (ItemStack offered : items) {
				if (offered.isEmpty()) {
					continue;
				}
				int remaining = offered.getCount();
				int selected = inventory.selected;
				if (selected >= 0 && selected < inventory.items.size()) {
					remaining = merge(selected, offered, remaining);
				}
				remaining = merge(Inventory.SLOT_OFFHAND, offered, remaining);
				for (int slot = 0; slot < inventory.items.size() && remaining > 0; slot++) {
					remaining = merge(slot, offered, remaining);
				}
				for (int slot = 0; slot < inventory.items.size() && remaining > 0; slot++) {
					if (after.get(slot).isEmpty()) {
						int inserted = Math.min(remaining, inventory.getMaxStackSize(offered));
						after.set(slot, offered.copyWithCount(inserted));
						remaining -= inserted;
					}
				}
				if (remaining > 0) {
					return false;
				}
			}
			return true;
		}

		private int merge(int slot, ItemStack offered, int remaining) {
			if (remaining <= 0 || slot < 0 || slot >= after.size()) {
				return remaining;
			}
			ItemStack existing = after.get(slot);
			if (existing.isEmpty() || !existing.isStackable()
					|| !ItemStack.isSameItemSameComponents(existing, offered)) {
				return remaining;
			}
			int space = Math.max(0, inventory.getMaxStackSize(existing) - existing.getCount());
			int inserted = Math.min(remaining, space);
			existing.grow(inserted);
			return remaining - inserted;
		}

		public boolean matchesCurrent() {
			if (used || inventory.getContainerSize() != before.size()) {
				return false;
			}
			for (int slot = 0; slot < before.size(); slot++) {
				if (!ItemStack.matches(inventory.getItem(slot), before.get(slot))) {
					return false;
				}
			}
			return true;
		}

		public boolean commit() {
			if (!matchesCurrent()) {
				return false;
			}
			used = true;
			for (int slot = 0; slot < after.size(); slot++) {
				if (!ItemStack.matches(before.get(slot), after.get(slot))) {
					inventory.setItem(slot, after.get(slot).copy());
				}
			}
			inventory.setChanged();
			committed = true;
			return true;
		}

		/** 只恢复本计划改过且仍保持提交值的槽位，不覆盖移除回调对其它槽的操作。 */
		public boolean rollback() {
			if (!committed || inventory.getContainerSize() != before.size()) {
				return false;
			}
			for (int slot = 0; slot < after.size(); slot++) {
				if (!ItemStack.matches(before.get(slot), after.get(slot))
						&& !ItemStack.matches(inventory.getItem(slot), after.get(slot))) {
					return false;
				}
			}
			for (int slot = 0; slot < after.size(); slot++) {
				if (!ItemStack.matches(before.get(slot), after.get(slot))) {
					inventory.setItem(slot, before.get(slot).copy());
				}
			}
			inventory.setChanged();
			committed = false;
			return true;
		}
	}
}
