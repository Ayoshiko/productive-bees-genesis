package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

/** 本地物品／流体的整批只读计划；只增加产物，不调用外部容器或溢出淘汰队列。 */
final class ApiaryLocalOutputPlan {
	private final List<? extends IInventorySlot> slots;
	private final IExtendedFluidTank tank;
	private final ItemStack[] before, after;
	private final FluidStack fluidBefore, fluidAfter;
	private boolean used;

	private ApiaryLocalOutputPlan(List<? extends IInventorySlot> slots, IExtendedFluidTank tank,
			ItemStack[] before, ItemStack[] after, FluidStack fluidBefore, FluidStack fluidAfter) {
		this.slots = List.copyOf(slots);
		this.tank = tank;
		this.before = before;
		this.after = after;
		this.fluidBefore = fluidBefore;
		this.fluidAfter = fluidAfter;
	}

	static ApiaryLocalOutputPlan prepare(List<? extends IInventorySlot> slots, IExtendedFluidTank tank,
			List<ItemStack> items, FluidStack fluid) {
		var before = new ItemStack[slots.size()];
		var after = new ItemStack[slots.size()];
		for (int i = 0; i < slots.size(); i++) {
			before[i] = slots.get(i).getStack().copy();
			after[i] = before[i].copy();
		}
		for (var item : items) {
			int remaining = item.getCount();
			if (item.isEmpty()) continue;
			// 先合并同组件栈，再占空槽；多项产物共用同一份虚拟库存。
			for (int pass = 0; pass < 2 && remaining > 0; pass++) {
				for (int i = 0; i < after.length && remaining > 0; i++) {
					var existing = after[i];
					if ((pass == 0) == existing.isEmpty()
							|| (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, item))
							|| !slots.get(i).isItemValid(item)) continue;
					int room = Math.max(0, slots.get(i).getLimit(item) - existing.getCount());
					int amount = Math.min(room, remaining);
					if (amount > 0) after[i] = item.copyWithCount(existing.getCount() + amount);
					remaining -= amount;
				}
			}
			if (remaining != 0) return null;
		}
		var fluidBefore = tank.getFluid().copy();
		var fluidAfter = fluidBefore.copy();
		if (!fluid.isEmpty()) {
			if (!tank.insert(fluid, Action.SIMULATE, AutomationType.INTERNAL).isEmpty()
					|| (!fluidBefore.isEmpty() && !com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.isSameFluid(fluidBefore, fluid))) return null;
			long total = (long) fluidBefore.getAmount() + fluid.getAmount();
			if (total > tank.getCapacity() || total > Integer.MAX_VALUE) return null;
			fluidAfter = com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.copyWithAmount(fluid, (int) total);
		}
		return new ApiaryLocalOutputPlan(slots, tank, before, after, fluidBefore, fluidAfter);
	}

	void verify() {
		if (used) throw new IllegalStateException("Mixed local plan already used");
		for (int i = 0; i < before.length; i++) {
			if (!ItemStack.matches(before[i], slots.get(i).getStack())) throw new IllegalStateException("Mixed item output changed");
		}
		if (!com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.matches(fluidBefore, tank.getFluid())) throw new IllegalStateException("Mixed fluid output changed");
	}

	/** 至多 count 个产物可落在任意候选组件；不用同时预约所有候选的最大数量。 */
	boolean fitsAlternatives(List<ItemStack> alternatives, long count) {
		if (count <= 0 || alternatives.isEmpty()) return true;
		var rooms = new long[alternatives.size()];
		int empty = 0, limit = Integer.MAX_VALUE;
		for (int i = 0; i < after.length; i++) {
			if (after[i].isEmpty()) {
				int commonLimit = Integer.MAX_VALUE;
				for (var candidate : alternatives) {
					commonLimit = Math.min(commonLimit, slots.get(i).isItemValid(candidate) ? slots.get(i).getLimit(candidate) : 0);
				}
				if (commonLimit > 0) { empty++; limit = Math.min(limit, commonLimit); }
			} else for (int j = 0; j < alternatives.size(); j++) {
				var candidate = alternatives.get(j);
				if (ItemStack.isSameItemSameTags(after[i], candidate) && slots.get(i).isItemValid(candidate)) {
					rooms[j] += Math.max(0, slots.get(i).getLimit(candidate) - after[i].getCount());
				}
			}
		}
		return maximumExtraSlots(count, rooms, limit) <= empty;
	}

	/** 首次占槽需先填满该身份旧栈，后续每槽至少容纳 limit 件；候选域至多 24 项。 */
	static long maximumExtraSlots(long count, long[] rooms, int limit) {
		if (count < 0 || limit <= 0) throw new IllegalArgumentException("Invalid alternative capacity");
		rooms = rooms.clone();
		java.util.Arrays.sort(rooms);
		long cost = 0, maximum = 0;
		for (int active = 1; active <= rooms.length; active++) {
			if (rooms[active - 1] < 0) throw new IllegalArgumentException("Negative existing room");
			if (rooms[active - 1] >= count - cost) break;
			cost += rooms[active - 1] + 1;
			maximum = Math.max(maximum, active + (count - cost) / limit);
		}
		return maximum;
	}

	/** 进入后不回滚可能已被监听器观察的结果；异常由账户冻结并保留前后快照，禁止重放。 */
	void commit() {
		verify();
		used = true;
		for (int i = 0; i < after.length; i++) {
			if (ItemStack.matches(before[i], after[i])) continue;
			if (!ItemStack.matches(before[i], slots.get(i).getStack())
					|| after[i].getCount() > slots.get(i).getLimit(after[i])) {
				throw new IllegalStateException("Mixed item target changed during commit");
			}
			slots.get(i).setStack(after[i].copy());
		}
		if (!com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.matches(fluidBefore, fluidAfter)) {
			if (!com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.matches(fluidBefore, tank.getFluid()) || fluidAfter.getAmount() > tank.getCapacity()) {
				throw new IllegalStateException("Mixed fluid target changed during commit");
			}
			tank.setStack(fluidAfter.copy());
		}
		for (int i = 0; i < after.length; i++) {
			if (!ItemStack.matches(after[i], slots.get(i).getStack())) throw new IllegalStateException("Mixed item outcome uncertain");
		}
		if (!com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.matches(fluidAfter, tank.getFluid())) throw new IllegalStateException("Mixed fluid outcome uncertain");
	}

	/** 冻结结果包含完整组件和精确数量；不把大栈数量交给有 99 上限的 ItemStack codec。 */
	CompoundTag context(HolderLookup.Provider provider) {
		var result = new CompoundTag();
		var changes = new ListTag();
		for (int i = 0; i < after.length; i++) {
			if (ItemStack.matches(before[i], after[i])) continue;
			var change = new CompoundTag();
			change.putInt("slot", i);
			change.put("before", saveItem(before[i], provider));
			change.put("after", saveItem(after[i], provider));
			changes.add(change);
		}
		result.put("items", changes);
		if (!com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.matches(fluidBefore, fluidAfter)) {
			result.put("fluid_before", com.ayoshiko.productivebeesgenesis.port.ForgeNbt.save(fluidBefore,provider));
			result.put("fluid_after", com.ayoshiko.productivebeesgenesis.port.ForgeNbt.save(fluidAfter,provider));
		}
		return result;
	}

	private static CompoundTag saveItem(ItemStack stack, HolderLookup.Provider provider) {
		var data = new CompoundTag();
		if (!stack.isEmpty()) {
			data.put("template", stack.copyWithCount(1).save(new CompoundTag()));
			data.putInt("amount", stack.getCount());
		}
		return data;
	}
}
