package com.ayoshiko.productivebeesgenesis.recipe;

import mekanism.api.inventory.IInventorySlot;
import mekanism.common.attachments.containers.ContainerType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 离心机升级按每车道的输入／三输出及末尾能量槽迁移，保留已存超堆叠物品。 */
final class CentrifugeCraftingInventoryTransfer {

	private static final int LANE_SLOTS = 4;

	private CentrifugeCraftingInventoryTransfer() {
	}

	static boolean transfer(List<ItemStack> inputs, ItemStack result) {
		result.set(ContainerType.ITEM.getComponentType(), ContainerType.ITEM.createNewAttachment(result));
		List<IInventorySlot> target = ContainerType.ITEM.createHandler(result).getInventorySlots(null);
		int energySlot = target.size() - 1;
		for (ItemStack input : inputs) {
			List<ItemStack> source = ContainerType.ITEM.getOrEmpty(input).containers();
			if (source.isEmpty()) {
				continue;
			}
			int sourceEnergySlot = source.size() - 1;
			if (sourceEnergySlot % LANE_SLOTS != 0) {
				return false;
			}
			for (int index = 0; index < source.size(); index++) {
				ItemStack remaining = source.get(index);
				if (remaining.isEmpty()) {
					continue;
				}
				if (index == sourceEnergySlot) {
					remaining = mergeIntoSlot(target.get(energySlot), remaining);
				} else {
					if (index < energySlot) {
						remaining = mergeIntoSlot(target.get(index), remaining);
					}
					for (int slot = 0; slot < energySlot && !remaining.isEmpty(); slot++) {
						boolean sameRole = (slot % LANE_SLOTS == 0) == (index % LANE_SLOTS == 0);
						if (slot != index && sameRole) {
							remaining = mergeIntoSlot(target.get(slot), remaining);
						}
					}
				}
				if (!remaining.isEmpty()) {
					return false;
				}
			}
		}
		return true;
	}

	private static ItemStack mergeIntoSlot(IInventorySlot slot, ItemStack stack) {
		ItemStack stored = slot.getStack();
		if (stored.isEmpty()) {
			slot.setStack(stack.copy());
			return ItemStack.EMPTY;
		}
		if (!ItemStack.isSameItemSameComponents(stored, stack)) {
			return stack;
		}
		int moved = Math.min(Math.max(0, slot.getLimit(stack) - stored.getCount()), stack.getCount());
		if (moved == 0) {
			return stack;
		}
		slot.setStack(stored.copyWithCount(stored.getCount() + moved));
		return stack.copyWithCount(stack.getCount() - moved);
	}
}
