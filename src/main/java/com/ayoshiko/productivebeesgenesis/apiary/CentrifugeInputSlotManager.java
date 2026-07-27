package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

/**
 * 离心机输入槽状态管理器 — 封装蜂箱直连弹出的输入槽预扫描、类型缓存、按负载排序
 * <br/>
 * 设计原则:
 * <ul>
 *   <li>SRP:仅负责输入槽状态管理,不涉及物品转移逻辑(由 ApiaryDirectEjectHandler 负责)</li>
 *   <li>数组复用:槽数不变时复用数组,避免每 tick 分配(参考 BeeProduceProcessor.reusableSlotStacks 模式)</li>
 *   <li>性能优化:19 输入槽场景下,预扫描 + 类型查找 + 按负载排序总开销 < 5μs/tick</li>
 * </ul>
 * <p>
 * 线程安全:服务端单线程调用,无需同步。
 */
class CentrifugeInputSlotManager {

	/** 预扫描的输入槽 ItemStack 数组(复用,槽数不变时不重新分配) */
	private ItemStack[] reusableInputStacks = new ItemStack[0];

	/** 预扫描的输入槽当前 count 数组(复用) */
	private int[] reusableInputCounts = new int[0];

	/** 预扫描的输入槽 limit 数组(复用) */
	private int[] reusableInputLimits = new int[0];

	/** 预扫描的输入槽剩余空间数组(= limit - count,复用) */
	private int[] reusableInputRemaining = new int[0];

	/**
	 * 预扫描所有输入槽的当前状态
	 * <br/>
	 * 在 tryDirectEject 遍历输出槽前调用,将输入槽状态预读取到复用数组,
	 * 后续 tryTransferToInput 直接读数组值,避免每次 targetSlot.getStack() API 调用。
	 * <p>
	 * 数组复用:槽数不变时复用数组(仅清空 stacks 引用);槽数变化时重新分配(防御性,正常不触发)。
	 *
	 * @param centrifuge 离心机接口
	 * @param slotCount  输入槽数量
	 */
	public void preScanInputSlots(IMekCentrifugeTile centrifuge, int slotCount) {
		// 数组复用:槽数不变时复用,变化时重新分配
		if (reusableInputStacks.length != slotCount) {
			reusableInputStacks = new ItemStack[slotCount];
			reusableInputCounts = new int[slotCount];
			reusableInputLimits = new int[slotCount];
			reusableInputRemaining = new int[slotCount];
		}
		// 预扫描填充
		for (int i = 0; i < slotCount; i++) {
			IInventorySlot inputSlot = centrifuge.productivebeesgenesis$getInputSlot(i);
			if (inputSlot == null) {
				reusableInputStacks[i] = ItemStack.EMPTY;
				reusableInputCounts[i] = 0;
				reusableInputLimits[i] = 0;
				reusableInputRemaining[i] = 0;
				continue;
			}
			ItemStack current = inputSlot.getStack();
			reusableInputStacks[i] = current;
			if (current.isEmpty()) {
				reusableInputCounts[i] = 0;
				// 空槽 limit 待填入时计算(暂设为 0,后续填入时更新)
				reusableInputLimits[i] = 0;
				reusableInputRemaining[i] = 0;
			} else {
				int count = current.getCount();
				int limit = inputSlot.getLimit(current);
				reusableInputCounts[i] = count;
				reusableInputLimits[i] = limit;
				reusableInputRemaining[i] = limit - count;
			}
		}
	}

	/**
	 * 在 tryTransferToInput 完成转移后,更新预扫描数组中的槽位状态
	 * <br/>
	 * 避免 tryDirectEject 循环中读取脏数据。若 newCount == newLimit,标记 remaining = 0。
	 *
	 * @param index    输入槽索引
	 * @param newStack 转移后的新 ItemStack(可能是原 stack.grow 后的引用,或新 setStack 的引用)
	 * @param newCount 转移后的新 count
	 */
	public void updateSlotAfterTransfer(int index, ItemStack newStack, int newCount) {
		if (index < 0 || index >= reusableInputStacks.length) return;
		reusableInputStacks[index] = newStack;
		int limit = reusableInputLimits[index];
		reusableInputCounts[index] = newCount;
		reusableInputRemaining[index] = Math.max(0, limit - newCount);
	}

	/**
	 * 查找同类型非空输入槽的索引列表(用于第一轮同类型合并)
	 * <br/>
	 * 使用 ItemStack.isSameItemSameComponents 比较(与 MEK insertItem 内部比较一致),
	 * 正确处理 BEE_TYPE 数据组件不同的蜜脾。
	 * <p>
	 * 19 槽场景下最多 19 次 isSameItemSameComponents 比较,开销 < 1μs。
	 *
	 * @param stack     待匹配的 ItemStack
	 * @param slotCount 输入槽数量
	 * @return 同类型非空输入槽的索引列表(可能为空)
	 */
	public List<Integer> findSameTypeSlots(ItemStack stack, int slotCount) {
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < slotCount; i++) {
			ItemStack inputStack = reusableInputStacks[i];
			if (!inputStack.isEmpty() && ItemStack.isSameItemSameComponents(inputStack, stack)) {
				result.add(i);
			}
		}
		return result;
	}

	/**
	 * 查找空槽索引列表,按剩余空间降序排序(用于第二轮按负载分配)
	 * <br/>
	 * 空槽的 limit 在 preScan 时未计算(因为空槽的 limit 依赖于待插入的 ItemStack),
	 * 此方法接受 outputStack 参数,用于计算空槽对该 stack 的 limit。
	 * <p>
	 * 19 槽场景下排序开销 < 1μs(19 log 19 ≈ 80 比较)。
	 *
	 * @param centrifuge 离心机接口(用于计算空槽 limit)
	 * @param outputStack 待插入的 ItemStack(用于计算空槽 limit)
	 * @param slotCount  输入槽数量
	 * @return 空槽索引列表,按剩余空间(limit)降序排序
	 */
	public List<Integer> findEmptySlotsSortedByRemainingDesc(
			IMekCentrifugeTile centrifuge, ItemStack outputStack, int slotCount) {
		List<int[]> emptySlotsWithLimit = new ArrayList<>();  // [index, limit]
		for (int i = 0; i < slotCount; i++) {
			if (reusableInputStacks[i].isEmpty()) {
				IInventorySlot inputSlot = centrifuge.productivebeesgenesis$getInputSlot(i);
				if (inputSlot == null) continue;
				int limit = inputSlot.getLimit(outputStack);
				if (limit <= 0) continue;
				emptySlotsWithLimit.add(new int[]{i, limit});
				// 同步更新预扫描数组(避免下次重复计算)
				reusableInputLimits[i] = limit;
				reusableInputRemaining[i] = limit;
			}
		}
		// 按 limit 降序排序
		emptySlotsWithLimit.sort((a, b) -> Integer.compare(b[1], a[1]));
		List<Integer> result = new ArrayList<>(emptySlotsWithLimit.size());
		for (int[] entry : emptySlotsWithLimit) {
			result.add(entry[0]);
		}
		return result;
	}

	/**
	 * 获取所有空槽中最大的剩余空间(用于短路优化判断)
	 * <br/>
	 * 注意:此方法假设空槽的 limit 已在 preScan 或 findEmptySlotsSortedByRemainingDesc 中计算。
	 * 若空槽 limit 未计算(空槽且 outputStack 未知),返回 0(短路优化不触发,走正常路径)。
	 *
	 * @param slotCount 输入槽数量
	 * @return 最大空槽剩余空间,无空槽返回 0
	 */
	public int getMaxEmptySlotRemaining(int slotCount) {
		int maxRemaining = 0;
		for (int i = 0; i < slotCount; i++) {
			if (reusableInputStacks[i].isEmpty()) {
				maxRemaining = Math.max(maxRemaining, reusableInputRemaining[i]);
			}
		}
		return maxRemaining;
	}

	/** 获取预扫描的输入槽 ItemStack */
	public ItemStack getInputStack(int index) {
		return reusableInputStacks[index];
	}

	/** 获取预扫描的输入槽当前 count */
	public int getInputCount(int index) {
		return reusableInputCounts[index];
	}

	/** 获取预扫描的输入槽 limit */
	public int getInputLimit(int index) {
		return reusableInputLimits[index];
	}

	/** 获取预扫描的输入槽剩余空间 */
	public int getInputRemaining(int index) {
		return reusableInputRemaining[index];
	}
}
