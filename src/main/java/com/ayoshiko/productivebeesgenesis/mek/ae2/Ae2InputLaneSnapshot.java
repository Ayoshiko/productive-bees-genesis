package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 一轮输入拉取内的「车道快照」——把「候选类型 × 输入槽」嵌套循环里的逐对槽位读取，
 * 压缩成每轮一次快照 + 纯数组查询。
 * <p>
 * <b>为什么需要</b>：容量规划的形状是 {@code for (类型) for (槽位)}，19 车道 × 38 候选
 * 类型就是 722 次槽位读取（{@code List.get} + {@code slot.getStack()} 虚调用 + {@code getItem()}），
 * 而其中绝大多数只是「槽内物品与本类型不同 → 容量必为 0」。spark 报告里
 * {@code getSlotRemainingCapacity} 占拉取子树三分之一，就是这条形状的成本。
 * <p>
 * <b>为什么快照在一轮内有效</b>：容量规划与执行发生在同一个服务端 tick 的同一段代码里，
 * 机器自身的消耗发生在它自己的 tick 阶段、不会与本段交错；因此一轮内唯一会改变槽内容的是
 * <b>我方自己的插入</b>。执行阶段每插入一个车道就调用 {@link #refresh(int)} 刷新该条，
 * 后续类型的判定因此仍然精确。
 * <p>
 * <b>语义不变</b>：快照只缓存「不变部分」（槽位引用、槽内物品、该槽上限），
 * 组件匹配、validator、上限比较仍在使用点按原顺序判定，返回 0 的条件与
 * {@link Ae2InputPuller} 原逐槽实现逐条对应。
 * <p>
 * <b>线程安全</b>：per-host，仅服务端 tick 线程访问。
 */
final class Ae2InputLaneSnapshot {

	private IInventorySlot[] slots = new IInventorySlot[0];
	/** 槽内物品（空槽为 null）— 用于零虚调用的身份剪枝 */
	private Item[] items = new Item[0];
	/** 槽内栈引用 — 组件匹配需要（值比较，引用仅用于记忆表命中） */
	private ItemStack[] stacks = new ItemStack[0];
	private int[] counts = new int[0];
	/** 非空槽对「自身内容」的可插入上限；空槽恒为 0 */
	private long[] limits = new long[0];
	/** 空槽的可插入上限（{@code getLimit(EMPTY)}）；非空槽恒为 0 */
	private long[] emptyLimits = new long[0];
	/**
	 * 非空槽的组件补丁数量；空槽恒为 0。
	 * <p>
	 * <b>为什么预采</b>：{@link Ae2InputPuller.PullEntry#matchesComponents} 原先在
	 * 「类型 × 车道」内层循环里对每条车道都重新读一次 {@code getComponentsPatch().size()}
	 * （两次），而补丁数在一轮内不会变化 —— 只有我方插入才改内容，且插入后必然
	 * {@link #refresh(int)}。预采把每次比较的组件表查找降为一次数组读。
	 */
	private int[] patchSizes = new int[0];
	/**
	 * 非空槽的 {@code bee_type} 组件值 —— 仅当「补丁数恰为 1 且物品是可配置蜜脾/蜜脾块
	 * 且该唯一补丁就是 bee_type」时才非 null，否则恒为 null。
	 * <p>
	 * 该字段的存在等价于原实现里的复合条件
	 * {@code patchSize == 1 && isConfigurableCombItem(item) && has(bee_type)}，
	 * 因此使用点可以直接用它替代那三次判定，把「不同蜜蜂种类各占一格」场景下
	 * 每轮数百次的 {@code DataComponentHolder.has}（spark 4l77qmbvh7 中该调用树 56ms）
	 * 压成一次身份/值比较。
	 */
	private ResourceLocation[] beeTypes = new ResourceLocation[0];
	private int size;
	private List<IInventorySlot> source = List.of();

	/**
	 * 采集本轮快照。
	 * <p>
	 * 必须在「回送剩余物」之后调用，否则会采到回送前的旧内容。
	 *
	 * @param inputSlots 宿主输入槽列表
	 * @param laneCount  参与规划的车道数（通常等于进程数）
	 */
	void capture(List<IInventorySlot> inputSlots, int laneCount) {
		this.source = inputSlots == null ? List.of() : inputSlots;
		this.size = Math.max(0, laneCount);
		if (slots.length < size) {
			int length = Math.max(size, slots.length << 1);
			slots = new IInventorySlot[length];
			items = new Item[length];
			stacks = new ItemStack[length];
			counts = new int[length];
			limits = new long[length];
			emptyLimits = new long[length];
			patchSizes = new int[length];
			beeTypes = new ResourceLocation[length];
		}
		for (int i = 0; i < size; i++) {
			refresh(i);
		}
	}

	/** 重新读取单条车道（我方插入之后必须调用，其余时刻内容不会变化）。 */
	void refresh(int index) {
		if (index < 0 || index >= size) return;
		IInventorySlot slot = index < source.size() ? source.get(index) : null;
		slots[index] = slot;
		if (slot == null) {
			items[index] = null;
			stacks[index] = ItemStack.EMPTY;
			counts[index] = 0;
			limits[index] = 0L;
			emptyLimits[index] = 0L;
			patchSizes[index] = 0;
			beeTypes[index] = null;
			return;
		}
		ItemStack stack = slot.getStack();
		stacks[index] = stack;
		if (stack.isEmpty()) {
			items[index] = null;
			counts[index] = 0;
			limits[index] = 0L;
			emptyLimits[index] = safeLimit(slot, ItemStack.EMPTY);
			patchSizes[index] = 0;
			beeTypes[index] = null;
		} else {
			Item item = stack.getItem();
			items[index] = item;
			counts[index] = stack.getCount();
			limits[index] = safeLimit(slot, stack);
			emptyLimits[index] = 0L;
			int patchSize = stack.getComponentsPatch().size();
			patchSizes[index] = patchSize;
			beeTypes[index] = singleBeeType(stack, item, patchSize);
		}
	}

	/**
	 * 提取「唯一补丁即 bee_type」的栈签名，失败返回 null。
	 * <p>
	 * 与 {@code PullEntry.matchesComponents} 的原快径条件逐字对应：
	 * 补丁数必须恰为 1、物品必须是可配置蜜脾/蜜脾块、且该补丁确实是 bee_type。
	 * 任何一条不满足都返回 null，使用点便退回完整组件比较，不改变堆叠语义
	 * （例如玩家重命名过的蜜脾带两个补丁，仍走 {@code isSameItemSameComponents}）。
	 * <p>
	 * 包级共享：车道侧（本类 {@link #refresh(int)}）与条目侧
	 * （{@code Ae2InputPuller.PullEntry#prepareKeyComponents}）必须使用同一套判定，
	 * 否则两侧签名不同源会让快径给出错误结论。
	 */
	static ResourceLocation singleBeeType(ItemStack stack, Item item, int patchSize) {
		if (patchSize != 1 || !CombFuzzyMatcher.isConfigurableCombItem(item)) return null;
		DataComponentType<ResourceLocation> beeTypeComponent = PbDataComponents.beeType();
		return stack.has(beeTypeComponent) ? stack.get(beeTypeComponent) : null;
	}


	/** 与 {@code getSlotRemainingCapacity} 一致：上限查询异常视为不可插入。 */
	private static long safeLimit(IInventorySlot slot, ItemStack probe) {
		try {
			return Math.max(0, slot.getLimit(probe));
		} catch (RuntimeException e) {
			return 0L;
		}
	}

	/** 读取单条车道的槽内物品；空槽或越界返回 null。 */
	Item item(int index) {
		return index >= 0 && index < size ? items[index] : null;
	}

	/** 读取单条车道的槽内栈引用；空槽返回 {@link ItemStack#EMPTY}。 */
	ItemStack stack(int index) {
		if (index < 0 || index >= size) return ItemStack.EMPTY;
		ItemStack stack = stacks[index];
		return stack == null ? ItemStack.EMPTY : stack;
	}

	/** 读取单条车道的槽位引用。 */
	IInventorySlot slot(int index) {
		return index >= 0 && index < size ? slots[index] : null;
	}

	/** 读取单条车道的槽内数量（空槽为 0）。 */
	int count(int index) {
		return index >= 0 && index < size ? counts[index] : 0;
	}

	/** 读取非空车道的可插入上限（空槽为 0）。 */
	long limit(int index) {
		return index >= 0 && index < size ? limits[index] : 0L;
	}

	/** 读取空车道的可插入上限（非空槽为 0）。 */
	long emptyLimit(int index) {
		return index >= 0 && index < size ? emptyLimits[index] : 0L;
	}

	/** 读取车道栈的组件补丁数（空槽为 0）。 */
	int patchSize(int index) {
		return index >= 0 && index < size ? patchSizes[index] : 0;
	}

	/**
	 * 读取车道的「唯一 bee_type 签名」；不满足快径条件时返回 null（见 {@link #beeTypes} 说明）。
	 */
	ResourceLocation beeType(int index) {
		return index >= 0 && index < size ? beeTypes[index] : null;
	}
}
