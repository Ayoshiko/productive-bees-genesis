package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
	 * PB配方输出执行器 — 封装聚合输出的实际插入与输入扣除逻辑
	 * <br/>
	 * 从 {@link PbRecipeCompleter} 抽取,遵循单一职责原则:
	 * <ul>
	 *   <li>{@link PbRecipeCompleter} — 仅负责聚合缓冲(accumulate)</li>
	 *   <li>{@code PbRecipeFlusher} — 仅负责执行 flush(planAndExecute + 流体插入 + 输入扣除)</li>
	 * </ul>
	 * <p>
	 * 性能优化(Task 4):复用实例字段数组(simStacks/simCounts 等),避免每次 flush 分配 5 个数组。
	 * 单进程独立实例,单线程执行,可安全复用。数组大小固定 3(主+副1+副2)。
	 * <p>
	 * 线程安全:服务端单线程执行,无需同步锁。
	 */
public final class PbRecipeFlusher {

	/**
	 * Task 4 性能优化:planAndExecute 实例字段复用数组(避免每次调用分配 5 个数组)
	 * <br/>
	 * 每进程独立实例化,单线程执行,可安全复用。数组大小固定 3(主+副1+副2)。
	 */
	private final ItemStack[] simStacks = new ItemStack[3];
	private final int[] simCounts = new int[3];
	private final int[] simLimits = new int[3];
	private final int[] addAmounts = new int[3];
	private final ItemStack[] setTemplates = new ItemStack[3];

	/**
	 * Task 7:reusableOutputSlots 中每个条目对应的原始 slotIdx 映射
	 * <br/>
	 * secondary 为 null 时列表索引与 (0=primary,1=secondary,2=tertiary) 不一致,
	 * 此映射保证 {@code updateSlotOnly} 收到正确的 slotIdx(0/1/2)。
	 */
	private final int[] reusableSlotIdxMap = new int[3];

	/** 可复用的输出槽列表(避免每次 flush 都创建新 ArrayList) */
	private final List<IInventorySlot> reusableOutputSlots = new ArrayList<>(3);

	/**
	 * 将聚合的 PB 配方输出实际插入槽位并扣除输入(单次直写优化版)
	 * <br/>
	 * 使用 {@link PbRecipeContext#productivebeesgenesis$beginOutputBatch} /
	 * {@link PbRecipeContext#productivebeesgenesis$endOutputBatch} 包装,
	 * 使输出槽 listener 只在本批次结束时扫描一次标志位。
	 * <p>
	 * 优化要点:合并模拟与执行({@link #planAndExecute}),用 grow/setStack 替代 insertItem,
	 * 消除 copy/remainder 开销。空间不足时不执行任何修改,返回 false 让调用方暂停处理。
	 *
	 * @param completer    PB配方聚合器(提供 pending 状态与 context)
	 * @param processIndex 进程索引
	 * @return true 全部输出成功插入并扣除输入;false 输出空间不足,未执行任何修改
	 */
	public boolean flush(PbRecipeCompleter completer, int processIndex) {
		PbRecipeContext context = completer.getContext();
		completer.discardSuppressedWaxOutputs();
		// 修复:纯流体输出配方(如 oritech 石油蜜蜂的蜜脾)没有物品输出,但仍有流体和输入扣除待处理
		if ((completer.getPendingRecipe() == null && !completer.hasCommittedPendingOutputs())
				|| (completer.getPendingOutputs().isEmpty()
						&& completer.getPendingFluidAmount() <= 0
						&& completer.getPendingInputShrink() <= 0)) {
			completer.clearPendingOutputs();
			return true;
		}

		FluidStack bufferedFluid = completer.getPendingFluidTemplate();
		if (context.suppressesUselessByproducts()
				&& bufferedFluid != null
				&& UselessByproductUpgradeHelper.isHoney(bufferedFluid)) {
			completer.consumePendingFluid(completer.getPendingFluidAmount());
		}
		context.productivebeesgenesis$beginOutputBatch();
		try {
			if (context.productivebeesgenesis$isDirectAeOutputEnabled()) {
				boolean committedToAe = pushPendingDirectToAe(completer, context);
				if (committedToAe) {
					// AE 写入不可回滚。先提交一次输入，剩余产物保留 pending 重试，
					// 避免本地回退槽已满时保留原料并复制已经进入 AE 的产物。
					consumePendingInput(completer, context, processIndex);
				}
			}

			// AE 接收全部产物时不读取或写入本地输出槽。
			if (completer.getPendingOutputs().isEmpty() && completer.getPendingFluidAmount() <= 0) {
				consumeInputAndFinish(completer, context, processIndex);
				return true;
			}

			// Task 7:构建 reusableOutputSlots 时同步填充 slotIdx 映射,
			// 保证 secondary 为 null 时 updateSlotOnly 收到正确的 slotIdx
			reusableOutputSlots.clear();
			reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
			reusableSlotIdxMap[0] = 0; // primary
			int slotCount = 1;
			IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
			if (secondary != null) {
				reusableOutputSlots.add(secondary);
				reusableSlotIdxMap[slotCount++] = 1; // secondary
			}
			IInventorySlot tertiary = context.tertiaryOutputSlot(processIndex);
			if (tertiary != null) {
				reusableOutputSlots.add(tertiary);
				reusableSlotIdxMap[slotCount++] = 2; // tertiary
			}

			// Resolve the route once for this flush. Capacity planning and the final commit run on
			// the server thread, so re-routing the same fluid only repeats map/hash work.
			FluidStack pendingFluidTemplate = completer.getPendingFluidTemplate();
			long pendingFluidAmount = completer.getPendingFluidAmount();
			IExtendedFluidTank pendingFluidTank = null;
			if (pendingFluidTemplate != null && !pendingFluidTemplate.isEmpty()
					&& pendingFluidAmount > 0) {
				pendingFluidTank = context.fluidOutputTankForInsert(pendingFluidTemplate);
				if (!canStorePendingFluidLocally(
						pendingFluidTemplate, pendingFluidAmount, pendingFluidTank)) {
					return false;
				}
			}

			// 单次直写:模拟 + 执行一体化
			// planAndExecute 返回 false 表示空间不足,已不做任何修改
			if (!planAndExecute(completer, processIndex, slotCount)) {
				return false;
			}
			completer.consumeAllPendingItems();

			// 流体输出 — Task 10: 使用 fluidOutputTankForInsert 实现多槽路由
			// SINGLE: 等价于 fluidOutputTank();MULTI_PER_FLUID: 自动路由到对应类型槽,
			// 槽位已满且无匹配时 fallback 主槽(由 isOutputBlocked 拦截)
			//
			// SRP 原则要求调用方知情,不能静默丢弃产物导致输入被消耗但产物丢失:
			// tank==null 或空间不足时必须返回 false 触发暂停,让外层下个 tick 重试,
			// 而非静默跳过让输入被扣除后产物丢失(违反配方原子性契约)。
			if (pendingFluidTemplate != null && pendingFluidAmount > 0) {
				int scaledAmount = (int) Math.min(pendingFluidAmount, Integer.MAX_VALUE);
				FluidStack scaledFluid = pendingFluidTemplate.copyWithAmount(scaledAmount);
				IExtendedFluidTank tank = pendingFluidTank;
				FluidStack remainder = tank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
				long inserted = scaledAmount - (remainder.isEmpty() ? 0 : remainder.getAmount());
				completer.consumePendingFluid(inserted);
				if (inserted > 0L && LocalFluidDrainPolicy.shouldDrainAfterCommit(
						tank.getNeeded(), scaledAmount)) {
					context.productivebeesgenesis$onLocalFluidOutputCommitted();
				}
				if (inserted < scaledAmount || completer.getPendingFluidAmount() > 0) {
					return false;
				}
			}

			consumeInputAndFinish(completer, context, processIndex);
			return true;
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
		}
	}

	private static boolean pushPendingDirectToAe(PbRecipeCompleter completer, PbRecipeContext context) {
		boolean acceptedAny = false;
		Iterator<Map.Entry<ItemStack, Integer>> iterator = completer.getPendingOutputs().entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ItemStack, Integer> entry = iterator.next();
			int requested = Math.max(0, entry.getValue());
			if (requested <= 0) {
				iterator.remove();
				continue;
			}
			ItemStack stack = entry.getKey().copyWithCount(requested);
			int accepted = Math.max(0, Math.min(requested,
					context.productivebeesgenesis$pushGeneratedItemToAe(stack)));
			if (accepted <= 0) continue;
			acceptedAny = true;
			completer.consumePendingItemCount(accepted);
			if (accepted >= requested) {
				iterator.remove();
			} else {
				entry.setValue(requested - accepted);
			}
		}

		FluidStack template = completer.getPendingFluidTemplate();
		long requestedFluid = completer.getPendingFluidAmount();
		if (template != null && !template.isEmpty() && requestedFluid > 0) {
			long accepted = Math.max(0L, Math.min(requestedFluid,
					context.productivebeesgenesis$pushGeneratedFluidToAe(template, requestedFluid)));
			acceptedAny |= accepted > 0;
			completer.consumePendingFluid(accepted);
		}
		return acceptedAny;
	}

	private static boolean canStorePendingFluidLocally(
			FluidStack template, long pending, IExtendedFluidTank tank) {
		// 本地 FluidStack 使用 int 数量。超大批次必须先由调用方减半，
		// 不能先插入 Integer.MAX_VALUE 再返回失败，否则输入未提交会复制流体。
		if (pending > Integer.MAX_VALUE) return false;
		int required = (int) Math.min(pending, Integer.MAX_VALUE);
		FluidStack current = tank == null ? FluidStack.EMPTY : tank.getFluid();
		boolean typeMismatch = !current.isEmpty()
				&& !FluidStack.isSameFluidSameComponents(current, template);
		if (tank == null || typeMismatch || tank.getNeeded() < required) {
			DevLog.warn("pb_recipe", "流体插入失败: tank={}, needed={}, required={}, fluid={}",
					tank, tank == null ? -1 : tank.getNeeded(), required, template.getFluid());
			return false;
		}
		return true;
	}

	private static void consumeInputAndFinish(PbRecipeCompleter completer, PbRecipeContext context, int processIndex) {
		consumePendingInput(completer, context, processIndex);
		completer.clearPendingOutputs();
	}

	private static void consumePendingInput(PbRecipeCompleter completer, PbRecipeContext context, int processIndex) {
		int pendingInputShrink = completer.getPendingInputShrink();
		if (pendingInputShrink > 0) {
			context.inputSlot(processIndex).shrinkStack(pendingInputShrink, Action.EXECUTE);
			completer.markPendingInputConsumed();
		}
	}

	/**
	 * 单次直写:模拟空间检查 + 执行插入(一体化,零拷贝)
	 * <br/>
	 * 第一遍遍历 pending 输出,数学计算每个槽位应增加的数量并记录到 addAmounts/addTemplates。
	 * 若任何输出无法完全容纳,立即返回 false。第二遍直接 grow/setStack 操作槽位。
	 * 相比旧版 canAccommodateAllOutputs + insertItem 双路径,省去 copy/remainder/
	 * isSameItemSameComponents 重复计算。
	 * <p>
	 * <b>Task 10 多槽流体路由:</b>流体检查使用 {@link PbRecipeContext#fluidOutputTankForInsert}
	 * 替代 {@link PbRecipeContext#fluidOutputTank}。SINGLE 模式等价于主槽,行为不变;
	 * MULTI_PER_FLUID 模式自动路由到对应类型槽,无匹配槽时 fallback 主槽,
	 * 主槽类型不匹配则 return false(由 isOutputBlocked 在后续 tick 拦截)。
	 *
	 * @param completer    PB配方聚合器(提供 pending 状态与 context)
	 * @param processIndex 进程索引(供 Task 7 updateSlotOnly 使用)
	 * @param slotCount     输出槽数量(由调用方计算后传入,避免重复计算)
	 * @return true 全部输出成功直写;false 空间不足,未做任何修改
	 */
	private boolean planAndExecute(PbRecipeCompleter completer, int processIndex, int slotCount) {
		// Task 4 性能优化:复用实例字段数组,避免每次调用分配 5 个数组
		// 数组大小固定 3(输出槽数),按 slotCount 重新填充
		// 调用前清空 addAmounts/setTemplates 防止上次调用的残留值影响
		for (int i = 0; i < slotCount; i++) {
			addAmounts[i] = 0;
			setTemplates[i] = null;
		}

		for (int i = 0; i < slotCount; i++) {
			IInventorySlot slot = reusableOutputSlots.get(i);
			ItemStack current = slot.getStack();
			simStacks[i] = current;
			if (current.isEmpty()) {
				simCounts[i] = 0;
				simLimits[i] = 0; // 空槽 limit 待填入时计算
			} else {
				simCounts[i] = current.getCount();
				simLimits[i] = slot.getLimit(current);
			}
		}

		// 第一遍:模拟 + 生成执行计划
		// v2.0.9 修复产物锁定 bug：防御性检查 pendingRecipeOutputs 与 pendingRecipe 一致性
		CentrifugeRecipe pendingRecipe = completer.getPendingRecipe();
		Map<ItemStack, ChancedOutput> recipeOutputs = completer.getPendingRecipeOutputs();
		Map<ItemStack, ChancedOutput> expectedOutputs = pendingRecipe != null
				? PbRecipeCompleter.getRecipeOutputsCached(pendingRecipe) : null;
		// 引用比较：recipeOutputsCache 是 LRU 缓存，同一 CentrifugeRecipe 实例返回同一 Map 引用
		// 若不一致说明 completer 状态被污染，使用 expectedOutputs 强制纠正
		if (recipeOutputs != expectedOutputs) {
			recipeOutputs = expectedOutputs;
		}
		Map<ItemStack, Integer> pendingOutputs = completer.getPendingOutputs();
		PbRecipeContext context = completer.getContext();

		Iterable<ItemStack> orderedTemplates = recipeOutputs == null
				? pendingOutputs.keySet() : recipeOutputs.keySet();
		for (ItemStack outputTemplate : orderedTemplates) {
			Integer count = pendingOutputs.get(outputTemplate);
			if (count == null || count <= 0) {
				continue;
			}
			int remaining = count;

			for (int i = 0; i < slotCount && remaining > 0; i++) {
				ItemStack simStack = simStacks[i];
				if (simStack.isEmpty()) {
					// 空槽:计算 limit 并分配
					int limit = reusableOutputSlots.get(i).getLimit(outputTemplate);
					if (limit <= 0) continue;
					int canFit = Math.min(remaining, limit);
					simStacks[i] = outputTemplate;
					simCounts[i] = canFit;
					simLimits[i] = limit;
					addAmounts[i] += canFit;
					setTemplates[i] = outputTemplate;
					remaining -= canFit;
				} else if (simStack.getItem() == outputTemplate.getItem()
					&& ItemStack.isSameItemSameComponents(simStack, outputTemplate)) {
					// 同类型槽:填充剩余空间
					int space = simLimits[i] - simCounts[i];
					if (space <= 0) continue;
					int canFit = Math.min(remaining, space);
					simCounts[i] += canFit;
					addAmounts[i] += canFit;
					remaining -= canFit;
				}
			}
			if (remaining > 0) {
				return false; // 空间不足,不执行任何修改
			}
		}

		// 第二遍:执行直写(已验证空间充足,零拷贝)
		// Task 4 关键修复:使用 slot.growStack() 触发 listener,不能用 ItemStack.grow() 绕过 listener
		// (IInventorySlot 契约禁止修改返回的 ItemStack,否则 onContentsChanged 不被调用 → 标志位陈旧 → 误判)
		// Task 7:每次 setStack/growStack 后调用 updateSlotOnly 标记槽位已知状态,避免 endBatch 重复扫描
		for (int i = 0; i < slotCount; i++) {
			if (addAmounts[i] <= 0) continue;
			IInventorySlot slot = reusableOutputSlots.get(i);
			if (slot == null) continue; // 防御性处理
			ItemStack current = slot.getStack();
			if (current.isEmpty()) {
				// 空槽 → setStack(BasicInventorySlot.setStack 总是触发 onContentsChanged)
				ItemStack template = setTemplates[i];
				context.productivebeesgenesis$expectOutputSlotChange();
				slot.setStack(template.copyWithCount(addAmounts[i]));
			} else {
				// 非空槽 → growStack(内部走 setStack → onContentsChanged,批量模式下仅标记 dirty)
				context.productivebeesgenesis$expectOutputSlotChange();
				slot.growStack(addAmounts[i], Action.EXECUTE);
			}
			context.productivebeesgenesis$updateSlotOnly(processIndex, reusableSlotIdxMap[i], slot);
		}

		return true;
	}
}
