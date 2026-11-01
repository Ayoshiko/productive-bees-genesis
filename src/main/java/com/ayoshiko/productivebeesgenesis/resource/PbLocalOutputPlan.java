package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper.BatchPlan;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.ItemStackHandler;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** 规划 PB 配方在蜂箱或 JDTE 的本地输出槽；整批组件精确合并，不丢弃余量。 */
public final class PbLocalOutputPlan {
	private final ItemStackHandler inventory;
	private final Supplier<int[]> layout;
	private final int[] slots;
	private final ItemStack[] before, after;
	private boolean used;

	private PbLocalOutputPlan(ItemStackHandler inventory, Supplier<int[]> layout, int[] slots,
			ItemStack[] before, ItemStack[] after) {
		this.inventory = inventory;
		this.layout = layout;
		this.slots = slots;
		this.before = before;
		this.after = after;
	}

	static PbLocalOutputPlan prepare(ItemStackHandler inventory, List<ItemStack> items) {
		return prepare(inventory, items, null, false);
	}

	static PbLocalOutputPlan prepare(ItemStackHandler inventory, List<ItemStack> items,
			BatchPlan conversion, boolean maximum) {
		if(!(inventory instanceof BlockEntityItemStackHandler nativeInventory))return null;
        return prepare(inventory, nativeInventory::getOutputSlots, items, conversion, maximum);
	}

	/** layout 只返回当前启用的本地输出槽；不访问外部能力。 */
	public static PbLocalOutputPlan prepare(ItemStackHandler inventory, Supplier<int[]> layout, List<ItemStack> items) {
		return prepare(inventory, layout, items, null, false);
	}

	/** PB 保留整库存转换语义，旧余料和本批产物都属于同一次本地写入。 */
	public static PbLocalOutputPlan prepare(ItemStackHandler inventory, Supplier<int[]> layout,
			List<ItemStack> items, BatchPlan conversion, boolean maximum) {
		int[] slots = layout.get().clone();
		if (Arrays.stream(slots).distinct().count() != slots.length) {
			throw new IllegalArgumentException("Duplicate PB output slots");
		}
		var before = new ItemStack[slots.length];
		var after = new ItemStack[slots.length];
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] < 0 || slots[i] >= inventory.getSlots()) throw new IllegalArgumentException("Invalid PB output slot");
			before[i] = inventory.getStackInSlot(slots[i]).copy();
			after[i] = before[i].copy();
		}
		if (conversion != null) {
			var combined = new java.util.ArrayList<ItemStack>(items);
			for (var stack : before) if (!stack.isEmpty()) combined.add(stack);
			items = maximum ? conversion.maximum(combined) : conversion.convert(combined);
			Arrays.fill(after, ItemStack.EMPTY);
		}
		for (var item : items) {
			if (item.isEmpty()) continue;
			int remaining = item.getCount();
			for (int pass = 0; pass < 2 && remaining > 0; pass++) {
				for (int i = 0; i < slots.length && remaining > 0; i++) {
					var existing = after[i];
					if ((pass == 0) == existing.isEmpty()
							|| (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, item))) continue;
					int limit = Math.min(inventory.getSlotLimit(slots[i]), item.getMaxStackSize());
					int accepted = Math.min(remaining, Math.max(0, limit - existing.getCount()));
					if (accepted > 0) after[i] = item.copyWithCount(existing.getCount() + accepted);
					remaining -= accepted;
				}
			}
			if (remaining != 0) return null;
		}
		return new PbLocalOutputPlan(inventory, layout, slots, before, after);
	}

	/** 监听器异常后不回滚已观察到的库存；账户保留快照并暂停，禁止自动重放。 */
	public void commit(Runnable validateHost) {
		if (used) throw new IllegalStateException("PB mixed output already committed");
		validateHost.run();
		verify(before);
		used = true;
		for (int i = 0; i < slots.length; i++) {
			if (ItemStack.matches(before[i], after[i])) continue;
			validateHost.run();
			verifyLayout();
			if (!ItemStack.matches(before[i], inventory.getStackInSlot(slots[i]))
					|| after[i].getCount() > Math.min(inventory.getSlotLimit(slots[i]), after[i].getMaxStackSize())) {
				throw new IllegalStateException("PB mixed output changed during commit");
			}
			inventory.setStackInSlot(slots[i], after[i].copy());
		}
		validateHost.run();
		verify(after);
	}

	private void verify(ItemStack[] expected) {
		verifyLayout();
		for (int i = 0; i < slots.length; i++) {
			if (!ItemStack.matches(expected[i], inventory.getStackInSlot(slots[i]))) {
				throw new IllegalStateException("PB mixed inventory outcome changed");
			}
		}
	}

	private void verifyLayout() {
		if (!Arrays.equals(slots, layout.get())) throw new IllegalStateException("PB output layout changed");
	}

	public CompoundTag context(HolderLookup.Provider provider) {
		var context = new CompoundTag();
		var changes = new ListTag();
		for (int i = 0; i < slots.length; i++) {
			if (ItemStack.matches(before[i], after[i])) continue;
			var change = new CompoundTag();
			change.putInt("slot", slots[i]);
			change.put("before", save(before[i], provider));
			change.put("after", save(after[i], provider));
			changes.add(change);
		}
		context.put("items", changes);
		return context;
	}

	private static CompoundTag save(ItemStack stack, HolderLookup.Provider provider) {
		var result = new CompoundTag();
		if (!stack.isEmpty()) {
			result.put("template", stack.copyWithCount(1).save(new CompoundTag()));
			result.putInt("amount", stack.getCount());
		}
		return result;
	}
}
