package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 蜂箱输出槽同类合并器（虚拟栈）
 * <br/>
 * 从 {@link ApiaryDirectEjectHandler} 拆分而来，职责（SRP）：把相同类型
 * （同物品同组件）的输出槽合并为虚拟栈，减少直连弹出时的循环次数；
 * 实际物品仍从原始输出槽提取。
 * <p>
 * 线程安全：服务端单线程调用，无需同步。
 */
final class ApiaryOutputMerger {

	/** 虚拟栈列表（复用，避免每 tick 分配） */
	private final List<ItemStack> virtualStacks = new ArrayList<>(9);

	/** 每个虚拟栈对应的原始输出槽列表（与 virtualStacks 平行） */
	private final List<List<BasicInventorySlot>> sourceSlots = new ArrayList<>(9);

	/** 清空合并结果 */
	void clear() {
		virtualStacks.clear();
		sourceSlots.clear();
	}

	/**
	 * 加入一个输出槽：同类型（同物品同组件）合并到已有虚拟栈，否则新建。
	 * 虚拟栈仅用于计算分配，实际物品仍需从原始输出槽 extractItem。
	 *
	 * @param slot 输出槽
	 */
	void add(BasicInventorySlot slot) {
		ItemStack stack = slot.getStack();
		if (stack.isEmpty()) return;

		// 查找是否已有同类型虚拟栈
		int existIdx = -1;
		for (int i = 0; i < virtualStacks.size(); i++) {
			if (ItemStack.isSameItemSameComponents(virtualStacks.get(i), stack)) {
				existIdx = i;
				break;
			}
		}

		if (existIdx >= 0) {
			// 合并到已有虚拟栈
			virtualStacks.get(existIdx).grow(stack.getCount());
			sourceSlots.get(existIdx).add(slot);
		} else {
			// 新建虚拟栈（copyWithCount 避免修改原始 stack）
			virtualStacks.add(stack.copyWithCount(stack.getCount()));
			List<BasicInventorySlot> sources = new ArrayList<>();
			sources.add(slot);
			sourceSlots.add(sources);
		}
	}

	/** 虚拟栈数量 */
	int size() {
		return virtualStacks.size();
	}

	/** 获取指定虚拟栈（可能为空） */
	ItemStack getVirtualStack(int index) {
		return virtualStacks.get(index);
	}

	/** 获取指定虚拟栈对应的原始输出槽列表 */
	List<BasicInventorySlot> getSources(int index) {
		return sourceSlots.get(index);
	}
}
