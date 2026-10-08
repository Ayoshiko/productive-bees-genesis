package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** FE 能力只操作单件副本；返回量必须等于物品数据中可重新读取的电量变化。 */
final class EnergyContainerPlan {
	record Change(ItemStack container, int energy) {
		Change { container = container.copy(); }
		@Override public ItemStack container() { return container.copy(); }
	}
	static int content(ItemStack item) {
		if (item.isEmpty()) return 0;
		var handler = item.copyWithCount(1).getCapability(Capabilities.EnergyStorage.ITEM);
		return handler == null || !handler.canExtract() ? 0 : stored(handler);
	}
	static Change prepare(ItemStack original, int requested, boolean charge) {
		if (original.isEmpty() || requested <= 0) return null;
		var copy = original.copyWithCount(1); var beforeItem = copy.copy();
		var handler = copy.getCapability(Capabilities.EnergyStorage.ITEM);
		if (handler == null || (charge ? !handler.canReceive() : !handler.canExtract())) return null;
		int before = stored(handler);
		int possible = charge ? handler.receiveEnergy(requested, true) : handler.extractEnergy(requested, true);
		if (possible < 0 || possible > requested || stored(handler) != before || !ItemStack.matches(copy, beforeItem))
			throw new IllegalStateException("Invalid energy container simulation");
		if (possible == 0) return null;
		int actual = charge ? handler.receiveEnergy(possible, false) : handler.extractEnergy(possible, false);
		int after = stored(handler);
		if (copy.getCount() != 1 || actual < 0 || actual > possible || (long) after - before != (charge ? actual : -actual))
			throw new IllegalStateException("Energy container delta differs from its return value");
		// 能力对象中的临时电量不能作为已交付物品；新副本必须具有相同的持久电量。
		var result = copy.copy(); var persisted = result.getCapability(Capabilities.EnergyStorage.ITEM);
		if (persisted == null || stored(persisted) != after || !ItemStack.matches(result, copy) || actual > 0 && ItemStack.matches(result, beforeItem))
			throw new IllegalStateException("Energy container did not persist its result");
		return actual == 0 ? null : new Change(result, actual);
	}
	private static int stored(IEnergyStorage handler) {
		int amount = handler.getEnergyStored(), capacity = handler.getMaxEnergyStored();
		if (amount < 0 || capacity < amount) throw new IllegalStateException("Invalid energy container contents");
		return amount;
	}
	private EnergyContainerPlan() { }
}
