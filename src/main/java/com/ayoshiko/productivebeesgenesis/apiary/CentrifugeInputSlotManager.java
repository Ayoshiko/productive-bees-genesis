package com.ayoshiko.productivebeesgenesis.apiary;

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
	/** 预扫描的输入槽引用，避免分组和实际转移阶段重复解析槽位。 */
	private IInventorySlot[] reusableInputSlots = new IInventorySlot[0];

	/** 预扫描的输入槽当前 count 数组(复用) */
	private int[] reusableInputCounts = new int[0];

	/** 预扫描的输入槽 limit 数组(复用) */
	private int[] reusableInputLimits = new int[0];

	/** 预扫描的输入槽剩余空间数组(= limit - count,复用) */
	private int[] reusableInputRemaining = new int[0];
	/** 同类型非空槽的复用索引缓冲，避免每个虚拟产物分配 Integer 列表。 */
	private int[] reusableSameTypeIndices = new int[0];
	/** 空槽按容量排序后的复用索引缓冲。 */
	private int[] reusableEmptyIndices = new int[0];

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
			reusableInputSlots = new IInventorySlot[slotCount];
			reusableInputCounts = new int[slotCount];
			reusableInputLimits = new int[slotCount];
			reusableInputRemaining = new int[slotCount];
			reusableSameTypeIndices = new int[slotCount];
			reusableEmptyIndices = new int[slotCount];
		}
		// 预扫描填充
		for (int i = 0; i < slotCount; i++) {
			IInventorySlot inputSlot = centrifuge.productivebeesgenesis$getInputSlot(i);
			reusableInputSlots[i] = inputSlot;
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
	 * @return 写入复用索引缓冲的槽位数量
	 */
	int prepareSameTypeSlots(ItemStack stack, int slotCount) {
		int count = 0;
		int scanCount = Math.min(slotCount, reusableInputStacks.length);
		for (int i = 0; i < scanCount; i++) {
			ItemStack inputStack = reusableInputStacks[i];
			if (!inputStack.isEmpty() && reusableInputRemaining[i] > 0
					&& ItemStack.isSameItemSameComponents(inputStack, stack)) {
				reusableSameTypeIndices[count++] = i;
			}
		}
		return count;
	}

	/** 返回 {@link #prepareSameTypeSlots} 生成的第 {@code order} 个槽位索引。 */
	int getSameTypeSlotIndex(int order) {
		return reusableSameTypeIndices[order];
	}

	/**
	 * 查找空槽索引列表,按剩余空间降序排序(用于第二轮按负载分配)
	 * <br/>
	 * 空槽的 limit 在 preScan 时未计算(因为空槽的 limit 依赖于待插入的 ItemStack),
	 * 此方法接受 outputStack 参数,用于计算空槽对该 stack 的 limit。
	 * <p>
	 * 19 槽场景下排序开销 < 1μs(19 log 19 ≈ 80 比较)。
	 *
	 * @param outputStack 待插入的 ItemStack(用于计算空槽 limit)
	 * @param slotCount  输入槽数量
	 * @return 写入复用索引缓冲的空槽数量，索引按剩余空间降序排列
	 */
	int prepareEmptySlotsSortedByRemainingDesc(ItemStack outputStack, int slotCount) {
		int count = 0;
		int scanCount = Math.min(slotCount, reusableInputStacks.length);
		for (int i = 0; i < scanCount; i++) {
			if (reusableInputStacks[i].isEmpty()) {
				IInventorySlot inputSlot = reusableInputSlots[i];
				if (inputSlot == null) continue;
				int limit = inputSlot.getLimit(outputStack);
				if (limit <= 0) continue;
				reusableEmptyIndices[count++] = i;
				// 同步更新预扫描数组(避免下次重复计算)
				reusableInputLimits[i] = limit;
				reusableInputRemaining[i] = limit;
			}
		}
		// 输入槽通常不超过 19 个；原地插入排序避免 Comparator、Integer 和 int[] 分配。
		for (int i = 1; i < count; i++) {
			int index = reusableEmptyIndices[i];
			int remaining = reusableInputRemaining[index];
			int insertAt = i - 1;
			while (insertAt >= 0
					&& reusableInputRemaining[reusableEmptyIndices[insertAt]] < remaining) {
				reusableEmptyIndices[insertAt + 1] = reusableEmptyIndices[insertAt];
				insertAt--;
			}
			reusableEmptyIndices[insertAt + 1] = index;
		}
		return count;
	}

	/** 返回 {@link #prepareEmptySlotsSortedByRemainingDesc} 生成的第 {@code order} 个槽位索引。 */
	int getEmptySlotIndex(int order) {
		return reusableEmptyIndices[order];
	}

	/**
	 * 获取所有空槽中最大的剩余空间(用于短路优化判断)
	 * <br/>
	 * 注意:此方法假设空槽的 limit 已在 preScan 或 prepareEmptySlotsSortedByRemainingDesc 中计算。
	 * 若空槽 limit 未计算(空槽且 outputStack 未知),返回 0(短路优化不触发,走正常路径)。
	 *
	 * @param slotCount 输入槽数量
	 * @return 最大空槽剩余空间,无空槽返回 0
	 */
	public int getMaxEmptySlotRemaining(int slotCount) {
		int maxRemaining = 0;
		int scanCount = Math.min(slotCount, reusableInputStacks.length);
		for (int i = 0; i < scanCount; i++) {
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

	/** 获取预扫描的输入槽引用。 */
	IInventorySlot getInputSlot(int index) {
		return reusableInputSlots[index];
	}
}
