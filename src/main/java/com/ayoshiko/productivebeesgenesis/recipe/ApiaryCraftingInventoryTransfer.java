package com.ayoshiko.productivebeesgenesis.recipe;

import java.util.List;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.attachments.containers.ContainerType;
import net.minecraft.world.item.ItemStack;

/** 工作台升级按蜂箱槽位用途重建库存，避免 MEK 通用插入把小食塞进蜂笼输出槽。 */
final class ApiaryCraftingInventoryTransfer {

	private static final int OUTPUT_START = 2;
	private static final int TAIL_SLOTS = 2;

	private ApiaryCraftingInventoryTransfer() {
	}

	static boolean transfer(List<ItemStack> inputs, ItemStack result) {
		// 回退结果可能仍带着旧等级的库存长度；必须从目标物品的创建器获取新布局。
		result.set(ContainerType.ITEM.getComponentType(), ContainerType.ITEM.createNewAttachment(result));
		var target = ContainerType.ITEM.createHandler(result);
		if (target == null) return false;
		List<IInventorySlot> slots = target.getInventorySlots(null);
		for (ItemStack input : inputs) {
			if (!transferSlots(ContainerType.ITEM.getOrEmpty(input).containers(), slots)) return false;
		}
		return true;
	}

	static boolean transferSlots(List<ItemStack> source, List<? extends IInventorySlot> target) {
		if (source.isEmpty()) return true;
		if (source.size() < OUTPUT_START + TAIL_SLOTS || target.size() < OUTPUT_START + TAIL_SLOTS) {
			return false;
		}
		int sourceOutputEnd = source.size() - TAIL_SLOTS;
		int targetOutputEnd = target.size() - TAIL_SLOTS;
		for (int index = 0; index < source.size(); index++) {
			ItemStack stack = source.get(index);
			if (stack.isEmpty()) continue;
			if (index < OUTPUT_START) {
				if (!mergeIntoSlot(target.get(index), stack).isEmpty()) return false;
			} else if (index >= sourceOutputEnd) {
				// 末尾依次为能量槽、小食槽，输出槽数量随工厂等级改变。
				int destination = targetOutputEnd + index - sourceOutputEnd;
				if (!mergeIntoSlot(target.get(destination), stack).isEmpty()) return false;
			} else {
				ItemStack remaining = stack;
				// 单输入保留产物原位置和超堆叠数量；多输入冲突只在产物区内合并。
				if (index < targetOutputEnd) remaining = mergeIntoSlot(target.get(index), remaining);
				for (int output = OUTPUT_START; output < targetOutputEnd && !remaining.isEmpty(); output++) {
					if (output != index) remaining = mergeIntoSlot(target.get(output), remaining);
				}
				if (!remaining.isEmpty()) return false;
			}
		}
		return true;
	}

	private static ItemStack mergeIntoSlot(IInventorySlot slot, ItemStack stack) {
		ItemStack stored = slot.getStack();
		if (stored.isEmpty()) {
			// 与安装器/NBT 恢复一致，已有超堆叠库存不能被物品组件的默认 64 上限截断。
			slot.setStack(stack.copy());
			return ItemStack.EMPTY;
		}
		if (!ItemStack.isSameItemSameComponents(stored, stack)) return stack;
		int room = Math.max(0, slot.getLimit(stack) - stored.getCount());
		int moved = Math.min(room, stack.getCount());
		if (moved == 0) return stack;
		slot.setStack(stored.copyWithCount(stored.getCount() + moved));
		return stack.copyWithCount(stack.getCount() - moved);
	}
}
