package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
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
	private int[] virtualHashes = new int[9];
	private int activeGroupCount;

	/** 清空合并结果 */
	void clear() {
		for (int i = 0; i < activeGroupCount; i++) {
			virtualStacks.set(i, ItemStack.EMPTY);
			sourceSlots.get(i).clear();
		}
		activeGroupCount = 0;
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
		int stackHash = ItemStack.hashItemAndComponents(stack);

		// 查找是否已有同类型虚拟栈
		int existIdx = -1;
		for (int i = 0; i < activeGroupCount; i++) {
			if (virtualHashes[i] == stackHash
					&& ItemStack.isSameItemSameComponents(virtualStacks.get(i), stack)) {
				existIdx = i;
				break;
			}
		}

		if (existIdx >= 0) {
			// 合并到已有虚拟栈
			ItemStack virtualStack = virtualStacks.get(existIdx);
			virtualStack.setCount(SaturatingMath.saturatingToInt(
					SaturatingMath.saturatingAdd(virtualStack.getCount(), stack.getCount())));
			sourceSlots.get(existIdx).add(slot);
		} else {
			ensureHashCapacity(activeGroupCount + 1);
			List<BasicInventorySlot> sources;
			if (activeGroupCount < virtualStacks.size()) {
				virtualStacks.set(activeGroupCount, stack.copy());
				sources = sourceSlots.get(activeGroupCount);
			} else {
				virtualStacks.add(stack.copy());
				sources = new ArrayList<>(1);
				sourceSlots.add(sources);
			}
			sources.add(slot);
			virtualHashes[activeGroupCount] = stackHash;
			activeGroupCount++;
		}
	}

	private void ensureHashCapacity(int requiredCapacity) {
		if (requiredCapacity <= virtualHashes.length) return;
		int doubled = virtualHashes.length <= Integer.MAX_VALUE / 2
				? virtualHashes.length * 2 : Integer.MAX_VALUE;
		int[] expanded = new int[Math.max(requiredCapacity, doubled)];
		System.arraycopy(virtualHashes, 0, expanded, 0, activeGroupCount);
		virtualHashes = expanded;
	}

	/** 虚拟栈数量 */
	int size() {
		return activeGroupCount;
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
