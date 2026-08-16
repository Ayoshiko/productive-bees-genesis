package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 蜜蜂产出分发器（直写输出槽 + 分段流体注入）
 * <br/>
 * 从 {@link BeeProduceProcessor} 拆分而来，职责（SRP）：把合并后的产物栈
 * 直接写入输出槽（绕过 insertItem 的组件比较热点），并把累积流体分段注入流体罐。
 * 持有可复用数组，跨 tick 零扩容。
 * <p>
 * 未成功插入的剩余产物通过返回值交还调用方（F4），由调用方送入
 * {@link ApiaryOutputBuffer} 下 tick 重试，绝不静默丢弃。
 */
final class BeeProduceOutputDispatcher {

	/** 合并阈值：超过该数量的栈才做预合并（覆盖万象创世 9 stack 场景跳过；PB 原版蜜蜂 2-3 stack 跳过） */
	private static final int MERGE_THRESHOLD = 8;

	/**
	 * distributeToOutput 数组复用 — 避免每 20 tick × 类型数次分配 3 数组（对齐 PbRecipeCompleter 模式）
	 * <br/>
	 * 槽位数不变时直接复用实例字段数组，仅清空 slotStacks 引用；
	 * 槽位数变化时（防御性，正常场景不触发）重新分配。
	 */
	private ItemStack[] reusableSlotStacks = new ItemStack[0];
	private int[] reusableSlotCounts = new int[0];
	private int[] reusableSlotLimits = new int[0];

	/** Reusable open-addressed table mapping item/component identity to a primitive slot index. */
	private int[] sameTypeGroupTable = new int[0];
	private OutputGroup[] reusableOutputGroups = new OutputGroup[0];
	private int activeOutputGroupCount;
	/** Reusable index of empty slots in physical GUI order. */
	private final OrderedSlotIndex emptySlots = new OrderedSlotIndex();

	/**
	 * 分发物品列表到输出槽（直写优化版）
	 * <br/>
	 * 仿照 {@link com.ayoshiko.productivebeesgenesis.mek.PbRecipeCompleter#planAndExecute} 的直写模式：
	 * 先合并相同物品+组件的栈，再预扫描输出槽状态，对空槽直接 {@code setStack}，
	 * 对同类型槽直接 {@code grow}，完全绕过 {@code insertItem} 内部的
	 * {@code isSameItemSameComponents} 组件比较（含 GeckoLib wrapOperation 拦截）。
	 * <p>
	 * Spark 分析显示旧版 {@code insertItem} 路径消耗 22.69 ms（占蜂箱 tick 的 42%），
	 * 其中 17.8 ms 花在 {@code isSameItemSameComponents} → {@code PatchedDataComponentMap.equals} 上。
	 * 直写模式将组件比较替换为 Item 引用比较（{@code ==}），预期减少 15-17 ms。
	 *
	 * @param outputSlots 输出槽列表
	 * @param stacks      待插入物品栈列表（会被合并）
	 * @return 未成功插入的剩余产物列表（F4：供调用方送入 ApiaryOutputBuffer）
	 */
	List<ItemStack> distribute(List<? extends IInventorySlot> outputSlots, List<ItemStack> stacks) {
		if (stacks.isEmpty()) return List.of();
		if (outputSlots.isEmpty()) return stacks;
		// mergeStacks 条件化：小批量（≤8 stack）跳过合并，避免小批量场景的 hashCode 预分组纯开销
		List<ItemStack> merged = (stacks.size() > MERGE_THRESHOLD)
				? ItemStackMergeHelper.mergeStacks(stacks)
				: stacks;

		int slotCount = outputSlots.size();
		// F4: 收集未成功插入的剩余产物，返回给调用方送入 ApiaryOutputBuffer
		List<ItemStack> leftovers = null;
		// 数组复用：槽位数不变时直接复用实例字段数组，避免每 20 tick × 类型数次分配 3 数组
		if (reusableSlotStacks.length != slotCount) {
			// 防御性：槽位数变化时重新分配（正常场景不触发）
			reusableSlotStacks = new ItemStack[slotCount];
			reusableSlotCounts = new int[slotCount];
			reusableSlotLimits = new int[slotCount];
		} else {
			// 复用：仅清空 slotStacks 引用（slotCounts / slotLimits 会被覆盖写入，无需清空）
			Arrays.fill(reusableSlotStacks, null);
		}
		// 预扫描输出槽当前状态，并建立同类槽/空槽索引，避免每个产物从槽 0 全扫描。
		resetOutputGroups(slotCount);
		emptySlots.reset(slotCount);
		for (int i = 0; i < slotCount; i++) {
			ItemStack current = outputSlots.get(i).getStack();
			reusableSlotStacks[i] = current;
			if (current.isEmpty()) {
				reusableSlotCounts[i] = 0;
				reusableSlotLimits[i] = 0; // 空槽 limit 待填入时计算
				emptySlots.add(i);
			} else {
				reusableSlotCounts[i] = current.getCount();
				reusableSlotLimits[i] = outputSlots.get(i).getLimit(current);
				findOutputGroup(current, true, slotCount).slots.add(i);
			}
		}

		// 逐个合并后的栈分发到槽位
		for (ItemStack stack : merged) {
			if (stack.isEmpty()) continue;
			int remaining = stack.getCount();

			OutputGroup matchingGroup = findOutputGroup(stack, false, slotCount);
			if (matchingGroup != null) for (int groupIndex = 0;
					groupIndex < matchingGroup.slots.size(); groupIndex++) {
				if (remaining <= 0) break;
				int i = matchingGroup.slots.get(groupIndex);
				ItemStack slotStack = reusableSlotStacks[i];
				if (!slotStack.isEmpty() && slotStack.getItem() == stack.getItem()
						&& ItemStack.isSameItemSameComponents(slotStack, stack)) {
					// Bug 2 修复：同 Item 同 BEE_TYPE 组件才可叠加，防止不同 bee_type 蜜脾互相覆盖
					int space = reusableSlotLimits[i] - reusableSlotCounts[i];
					if (space <= 0) continue;
					int canFit = Math.min(remaining, space);
					// M3-1 修复：显式 setStack 替代 grow，避免依赖 ItemStack 可变性
					ItemStack grownStack = reusableSlotStacks[i].copyWithCount(reusableSlotCounts[i] + canFit);
					outputSlots.get(i).setStack(grownStack);
					// 回读 actual stack，按实际写入量扣减 remaining
					ItemStack actualStack = outputSlots.get(i).getStack();
					int actualCount = actualStack.isEmpty() ? 0 : actualStack.getCount();
					int actualGrown = Math.max(0, actualCount - reusableSlotCounts[i]);
					reusableSlotStacks[i] = actualStack;
					reusableSlotCounts[i] = actualCount;
					remaining -= actualGrown;
				}
			}
			for (int emptyIndex = 0; emptyIndex < emptySlots.size() && remaining > 0; emptyIndex++) {
				int i = emptySlots.get(emptyIndex);
				if (i < 0 || !reusableSlotStacks[i].isEmpty()) continue;
				int limit = outputSlots.get(i).getLimit(stack);
				if (limit <= 0) continue;
				int canFit = Math.min(remaining, limit);
				outputSlots.get(i).setStack(stack.copyWithCount(canFit));
				ItemStack actualStack = outputSlots.get(i).getStack();
				int actualCount = actualStack.isEmpty() ? 0 : actualStack.getCount();
				reusableSlotStacks[i] = actualStack;
				reusableSlotCounts[i] = actualCount;
				reusableSlotLimits[i] = limit;
				remaining -= actualCount;
				if (actualCount > 0) {
					findOutputGroup(actualStack, true, slotCount).slots.add(i);
					emptySlots.consume(emptyIndex);
				}
			}
			// F4: 收集未成功插入的剩余产物，返回给调用方送入 ApiaryOutputBuffer
			if (remaining > 0) {
				if (leftovers == null) leftovers = new ArrayList<>();
				leftovers.add(stack.copyWithCount(remaining));
			}
		}
		return leftovers == null ? List.of() : leftovers;
	}

	/**
	 * 注入流体到流体罐（支持任意流体类型）
	 * <br/>
	 * 流体类型由 {@link BeeFluidOutputResolver#resolveFluidOutput} 从离心配方推断：
	 * 蜂蜜蜜蜂注入蜂蜜，非蜂蜜流体蜜蜂不调用此方法（fluidTemplate 为 EMPTY）。
	 * <p>
	 * 超高倍率（如 4096x × 256x）场景下单次 tick 累积量可能超过 Integer.MAX_VALUE，
	 * 因此 amount 使用 long 类型；FluidStack 构造器仅接受 int，需分段注入。
	 *
	 * @param tank     流体罐
	 * @param template 流体模板（含流体类型，amount 字段不使用，由 amount 参数覆盖）
	 * @param amount   注入量（mB），批量场景为累积总量（long 避免溢出）
	 */
	long injectFluid(IExtendedFluidTank tank, FluidStack template, long amount) {
		if (tank == null || amount <= 0 || template == null || template.isEmpty()) return Math.max(0L, amount);
		// FluidStack 构造器仅接受 int，long 总量需分段注入
		// 单次上限 Integer.MAX_VALUE（约 21.47 亿 mB），避免溢出
		long remaining = amount;
		while (remaining > 0) {
			int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
			// 使用 template 的流体类型，覆盖 amount 为当前分段量
			FluidStack stack = template.copyWithAmount(chunk);
			// M3-2 修复：读取 tank.insert 返回值，计算实际注入量
			// 原实现直接 remaining -= chunk，tank 已满时实际注入 0 但 remaining 已扣完，
			// 导致后续 chunk 不再尝试，但实际注入量为 0，流体产物静默丢失
			FluidStack leftover = tank.insert(stack, Action.EXECUTE, AutomationType.INTERNAL);
			int actualInserted = chunk - (leftover.isEmpty() ? 0 : leftover.getAmount());
			remaining -= actualInserted;
			// 实际注入量为 0（tank 已满），跳出避免无限循环
			if (actualInserted == 0 && chunk > 0) break;
		}
		return remaining;
	}

	private void resetOutputGroups(int slotCount) {
		if (reusableOutputGroups.length < slotCount) {
			int previousLength = reusableOutputGroups.length;
			reusableOutputGroups = Arrays.copyOf(reusableOutputGroups, slotCount);
			for (int i = previousLength; i < slotCount; i++) {
				reusableOutputGroups[i] = new OutputGroup();
			}
		}
		int requiredTableSize = 1;
		while (requiredTableSize < slotCount * 2) requiredTableSize <<= 1;
		if (sameTypeGroupTable.length < requiredTableSize) {
			sameTypeGroupTable = new int[requiredTableSize];
		} else {
			Arrays.fill(sameTypeGroupTable, 0);
		}
		activeOutputGroupCount = 0;
	}

	private OutputGroup findOutputGroup(ItemStack stack, boolean create, int slotCount) {
		int hash = ItemStack.hashItemAndComponents(stack);
		int tableMask = sameTypeGroupTable.length - 1;
		int bucket = (hash ^ (hash >>> 16)) & tableMask;
		while (true) {
			int encodedGroup = sameTypeGroupTable[bucket];
			if (encodedGroup == 0) {
				if (!create) return null;
				OutputGroup group = reusableOutputGroups[activeOutputGroupCount];
				group.reset(stack, hash, slotCount);
				sameTypeGroupTable[bucket] = ++activeOutputGroupCount;
				return group;
			}
			OutputGroup group = reusableOutputGroups[encodedGroup - 1];
			if (group.matches(stack, hash)) return group;
			bucket = (bucket + 1) & tableMask;
		}
	}

	private static final class OutputGroup {
		private Object item;
		private Object components;
		private int hash;
		private final OrderedSlotIndex slots = new OrderedSlotIndex();

		void reset(ItemStack stack, int hash, int slotCount) {
			this.item = stack.getItem();
			this.components = stack.getComponents();
			this.hash = hash;
			slots.reset(slotCount);
		}

		boolean matches(ItemStack stack, int hash) {
			return this.hash == hash && item == stack.getItem() && components.equals(stack.getComponents());
		}
	}
}
