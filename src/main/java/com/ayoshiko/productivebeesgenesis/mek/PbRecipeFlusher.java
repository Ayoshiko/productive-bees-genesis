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
 *   <li>{@code PbRecipeFlusher} — 仅负责执行 flush(规划写入 + 流体插入 + 输入扣除)</li>
 *   <li>{@link PbOutputPlacementPlanner} — 仅负责三个物理输出槽的放置模拟与写入</li>
 * </ul>
 * <p>
 * <b>两种提交语义</b>：
 * <ul>
 *   <li><b>原子提交</b>（默认，输入尚未扣除）：全部产物能写入才提交，否则不做任何修改，
 *       由 {@link PbRecipeFlushHelper} 减半批量重试，保持「输入消耗 ↔ 产物产出」一致。</li>
 *   <li><b>延迟提交</b>（{@code maxDeferredPerType > 0}，或输入已扣除的 pending）：
 *       写入能放下的部分、扣除一次输入，其余产物留在 pending 下个 tick 继续排空。
 *       这是产物种类多于三个物理输出槽时（如屠夫蜜脾 4 种产物 + 7 个稳定性升级）
 *       唯一能推进的方式：减半批量不会减少产物<b>种类</b>，原子提交必然永久失败。</li>
 * </ul>
 * pending 会随方块实体持久化（见 {@link PbRecipeCompleter#saveCommittedPending}），
 * 且「已提交 pending」存在期间上层不再累积新产物，因此隐形缓冲有界、不会丢物品。
 * <p>
 * 线程安全:服务端单线程执行,无需同步锁。
 */
public final class PbRecipeFlusher {

	/** flush 结果 */
	enum Outcome {
		/** 全部产物与流体写出，输入已扣除，pending 已清空 */
		COMMITTED,
		/** 部分写出，输入已扣除一次，剩余产物留在 pending 等待排空 */
		DEFERRED,
		/** 需要延迟的数量超出预算，未做任何修改（调用方应压缩批量后重试） */
		DEFERRAL_TOO_LARGE,
		/** 没有任何空间，未做任何修改 */
		BLOCKED
	}

	/** 放置规划器 — 每进程独立实例，内部数组复用 */
	private final PbOutputPlacementPlanner planner = new PbOutputPlacementPlanner();

	/**
	 * reusableOutputSlots 中每个条目对应的原始 slotIdx 映射
	 * <br/>
	 * secondary 为 null 时列表索引与 (0=primary,1=secondary,2=tertiary) 不一致,
	 * 此映射保证 {@code updateSlotOnly} 收到正确的 slotIdx(0/1/2)。
	 */
	private final int[] reusableSlotIdxMap = new int[PbOutputPlacementPlanner.MAX_SLOTS];

	/** 可复用的输出槽列表(避免每次 flush 都创建新 ArrayList) */
	private final List<IInventorySlot> reusableOutputSlots =
			new ArrayList<>(PbOutputPlacementPlanner.MAX_SLOTS);

	// ===== 上一次规划的诊断信息（供 PbRecipeFlushHelper 选择重试策略） =====
	private boolean lastPlanUnplaceableType;
	private int lastPlanMaxDeferredPerType;
	private int lastPlanSlotCapacity = 64;

	/**
	 * 将聚合的 PB 配方输出实际插入槽位并扣除输入(单次直写优化版)
	 * <br/>
	 * 使用 {@link PbRecipeContext#productivebeesgenesis$beginOutputBatch} /
	 * {@link PbRecipeContext#productivebeesgenesis$endOutputBatch} 包装,
	 * 使输出槽 listener 只在本批次结束时扫描一次标志位。
	 *
	 * @param completer    PB配方聚合器(提供 pending 状态与 context)
	 * @param processIndex 进程索引
	 * @return true 全部输出成功插入并扣除输入;false 输出空间不足或仍有剩余 pending
	 */
	public boolean flush(PbRecipeCompleter completer, int processIndex) {
		return flush(completer, processIndex, 0) == Outcome.COMMITTED;
	}

	/**
	 * 允许延迟提交的 flush。
	 *
	 * @param maxDeferredPerType 单种产物允许延迟的最大数量（0 = 只接受原子提交）
	 * @return 见 {@link Outcome}
	 */
	Outcome flush(PbRecipeCompleter completer, int processIndex, int maxDeferredPerType) {
		PbRecipeContext context = completer.getContext();
		completer.discardSuppressedWaxOutputs();
		// 在 AE 和本地槽位规划前转换 pending 产物，保证两条输出路径行为一致。
		completer.convertPendingEssenceOutputs();
		completer.convertPendingRawOreOutputs();
		// 诊断信息只描述本次尝试：否则「流体满」导致的失败会读到上一次种类溢出的陈旧标记，
		// 让调用方误判为需要延迟提交（流体满应保持原语义：暂停并保留输入）。
		lastPlanUnplaceableType = false;
		lastPlanMaxDeferredPerType = 0;
		// 输入已扣除的 pending 不再有原子性约束：能写多少写多少，剩余继续挂起重试。
		boolean alreadyCommitted = completer.hasCommittedPendingOutputs();
		int deferralBudget = alreadyCommitted ? Integer.MAX_VALUE : Math.max(0, maxDeferredPerType);

		// 修复:纯流体输出配方(如 oritech 石油蜜蜂的蜜脾)没有物品输出,但仍有流体和输入扣除待处理
		if ((completer.getPendingRecipe() == null && !alreadyCommitted)
				|| (completer.getPendingOutputs().isEmpty()
						&& completer.getPendingFluidAmount() <= 0
						&& completer.getPendingInputShrink() <= 0)) {
			completer.clearPendingOutputs();
			return Outcome.COMMITTED;
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
					deferralBudget = Integer.MAX_VALUE;
				}
			}

			// 产物直通：AE 之后、写输出槽之前，先尝试把产物直接交给相邻容器/管道。
			// 与 AE 路径同样是「不可回滚」的外部写入，因此成功即提交一次输入并放开延迟预算。
			if (pushPendingDirectToNeighbors(completer, context)) {
				consumePendingInput(completer, context, processIndex);
				deferralBudget = Integer.MAX_VALUE;
			}

			// AE 接收全部产物时不读取或写入本地输出槽。
			if (completer.getPendingOutputs().isEmpty() && completer.getPendingFluidAmount() <= 0) {
				consumeInputAndFinish(completer, context, processIndex);
				return Outcome.COMMITTED;
			}

			int slotCount = buildOutputSlots(context, processIndex);

			// Resolve the route once for this flush. Capacity planning and the final commit run on
			// the server thread, so re-routing the same fluid only repeats map/hash work.
			FluidStack pendingFluidTemplate = completer.getPendingFluidTemplate();
			long pendingFluidAmount = completer.getPendingFluidAmount();
			IExtendedFluidTank pendingFluidTank = null;
			boolean hasPendingFluid = pendingFluidTemplate != null && !pendingFluidTemplate.isEmpty()
					&& pendingFluidAmount > 0;
			if (hasPendingFluid) {
				pendingFluidTank = context.fluidOutputTankForInsert(pendingFluidTemplate);
				// 原子提交必须先确认流体放得下，否则会出现「物品已写、输入未扣」的悬挂状态；
				// 延迟提交没有该约束，流体放不下就继续挂 pending。
				if (deferralBudget <= 0 && !canStorePendingFluidLocally(
						pendingFluidTemplate, pendingFluidAmount, pendingFluidTank)) {
					return Outcome.BLOCKED;
				}
			}

			Outcome itemOutcome = placeItems(completer, context, processIndex, slotCount, deferralBudget);
			if (itemOutcome == Outcome.BLOCKED || itemOutcome == Outcome.DEFERRAL_TOO_LARGE) {
				// 物品尚未写入任何槽位。输入已扣除的 pending 没有原子性约束，可先把流体排空；
				// 输入未扣除时必须保持「未做任何修改」语义，交由调用方压缩批量或下 tick 重试。
				if (hasPendingFluid && completer.getPendingInputShrink() <= 0) {
					insertPendingFluid(completer, context,
							pendingFluidTemplate, pendingFluidAmount, pendingFluidTank);
					return Outcome.DEFERRED;
				}
				return itemOutcome;
			}
			boolean deferred = itemOutcome == Outcome.DEFERRED;

			// 流体输出 — Task 10: 使用 fluidOutputTankForInsert 实现多槽路由
			// SINGLE: 等价于 fluidOutputTank();MULTI_PER_FLUID: 自动路由到对应类型槽
			if (hasPendingFluid && !insertPendingFluid(completer, context,
					pendingFluidTemplate, pendingFluidAmount, pendingFluidTank)) {
				deferred = true;
			}

			if (deferred) {
				// 已写出部分产物 → 输入只扣一次，剩余产物由后续 tick 继续排空
				consumePendingInput(completer, context, processIndex);
				return Outcome.DEFERRED;
			}
			consumeInputAndFinish(completer, context, processIndex);
			return Outcome.COMMITTED;
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
		}
	}

	/** 存在无处安放的产物种类（减半批量无效，只能延迟提交） */
	boolean lastPlanHasUnplaceableType() {
		return lastPlanUnplaceableType;
	}

	/** 上一次规划中单种产物需要延迟的最大数量 */
	int lastPlanMaxDeferredPerType() {
		return lastPlanMaxDeferredPerType;
	}

	/** 上一次规划观测到的单槽容量（延迟量预算基准） */
	int lastPlanSlotCapacity() {
		return lastPlanSlotCapacity;
	}

	/**
	 * 规划并写入物品产物。
	 * <br/>
	 * 全部放得下 → {@link Outcome#COMMITTED}；否则按 {@code deferralBudget} 决定是
	 * 原子放弃（{@link Outcome#BLOCKED}）、要求压缩批量（{@link Outcome#DEFERRAL_TOO_LARGE}）
	 * 还是部分写入（{@link Outcome#DEFERRED}）。
	 */
	private Outcome placeItems(PbRecipeCompleter completer, PbRecipeContext context,
			int processIndex, int slotCount, int deferralBudget) {
		Map<ItemStack, Integer> pendingOutputs = completer.getPendingOutputs();
		if (pendingOutputs.isEmpty()) {
			return Outcome.COMMITTED;
		}
		Map<ItemStack, ChancedOutput> recipeOutputs = resolveRecipeOutputs(completer);
		Iterable<ItemStack> orderedTemplates = context.productivebeesgenesis$hasEssenceConversionUpgrade()
				|| context.productivebeesgenesis$hasRawOreSmeltingUpgrade()
				? pendingOutputs.keySet()
				: recipeOutputs == null
				? pendingOutputs.keySet() : recipeOutputs.keySet();
		planner.snapshot(orderedTemplates, pendingOutputs);
		planner.simulate(reusableOutputSlots, slotCount);
		lastPlanUnplaceableType = planner.hasUnplaceableType();
		lastPlanMaxDeferredPerType = planner.maxDeferredPerType();
		lastPlanSlotCapacity = planner.slotCapacity();

		if (planner.placedAll()) {
			if (planner.execute(reusableOutputSlots, slotCount, reusableSlotIdxMap, context, processIndex)) {
				completer.consumeAllPendingItems();
				return Outcome.COMMITTED;
			}
			// 槽位对数量做了截断（异常路径）：写入无法回滚，按实际写入量记账并挂起剩余产物，
			// 保证「输入只扣一次、产物不丢失」。
			consumePlacedItems(completer, pendingOutputs);
			return Outcome.DEFERRED;
		}
		if (deferralBudget <= 0) {
			return Outcome.BLOCKED; // 原子语义：空间不足时不执行任何修改
		}
		if (planner.maxDeferredPerType() > deferralBudget) {
			return Outcome.DEFERRAL_TOO_LARGE; // 隐形缓冲会过大，交由调用方压缩批量
		}
		if (!planner.placedAny()) {
			return Outcome.BLOCKED;
		}
		planner.execute(reusableOutputSlots, slotCount, reusableSlotIdxMap, context, processIndex);
		consumePlacedItems(completer, pendingOutputs);
		DevLog.debug("pb_recipe",
				"产物种类多于输出槽，已部分提交并延迟剩余产物: 进程{} 延迟上限={}",
				processIndex, planner.maxDeferredPerType());
		return Outcome.DEFERRED;
	}

	/** 按规划结果扣减 pending 数量（写入成功的部分），剩余数量保留等待下次排空。 */
	private void consumePlacedItems(PbRecipeCompleter completer, Map<ItemStack, Integer> pendingOutputs) {
		int totalPlaced = 0;
		for (int t = 0, count = planner.templateCount(); t < count; t++) {
			int written = planner.placedAt(t);
			if (written <= 0) continue;
			ItemStack template = planner.templateAt(t);
			int remaining = Math.max(0, planner.requestedAt(t) - written);
			if (remaining == 0) {
				pendingOutputs.remove(template);
			} else {
				pendingOutputs.put(template, remaining);
			}
			totalPlaced += written;
		}
		completer.consumePendingItemCount(totalPlaced);
	}

	/**
	 * 校正 completer 的配方输出表引用。
	 * <br/>
	 * recipeOutputsCache 对同一 CentrifugeRecipe 实例返回同一 Map 引用，
	 * 不一致说明 completer 状态被污染，以缓存表为准（v2.0.9 产物锁定 bug 修复）。
	 */
	private static Map<ItemStack, ChancedOutput> resolveRecipeOutputs(PbRecipeCompleter completer) {
		CentrifugeRecipe pendingRecipe = completer.getPendingRecipe();
		Map<ItemStack, ChancedOutput> expectedOutputs = pendingRecipe != null
				? PbRecipeCompleter.getRecipeOutputsCached(pendingRecipe) : null;
		Map<ItemStack, ChancedOutput> recipeOutputs = completer.getPendingRecipeOutputs();
		return recipeOutputs != expectedOutputs ? expectedOutputs : recipeOutputs;
	}

	/**
	 * 构建输出槽列表并填充 slotIdx 映射。
	 *
	 * @return 有效槽位数量
	 */
	private int buildOutputSlots(PbRecipeContext context, int processIndex) {
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
		return slotCount;
	}

	/**
	 * 插入 pending 流体。
	 *
	 * @return true 表示本轮流体已全部写出
	 */
	private static boolean insertPendingFluid(PbRecipeCompleter completer, PbRecipeContext context,
			FluidStack template, long pending, IExtendedFluidTank tank) {
		if (tank == null) {
			DevLog.warn("pb_recipe", "找不到可用流体槽，流体产物挂起重试: fluid={}", template.getFluid());
			return false;
		}
		int scaledAmount = (int) Math.min(pending, Integer.MAX_VALUE);
		FluidStack scaledFluid = template.copyWithAmount(scaledAmount);
		FluidStack remainder = tank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
		long inserted = scaledAmount - (remainder.isEmpty() ? 0 : remainder.getAmount());
		completer.consumePendingFluid(inserted);
		if (inserted > 0L && LocalFluidDrainPolicy.shouldDrainAfterCommit(
				tank.getNeeded(), scaledAmount)) {
			context.productivebeesgenesis$onLocalFluidOutputCommitted();
		}
		return inserted >= scaledAmount && completer.getPendingFluidAmount() <= 0;
	}

	/** 物品完全放不下但流体仍可写出时，至少推进流体（输入随之扣除一次）。 */
	private static Outcome insertFluidOnly(PbRecipeCompleter completer, PbRecipeContext context,
			int processIndex, FluidStack template, long pending, IExtendedFluidTank tank) {
		boolean fluidDone = insertPendingFluid(completer, context, template, pending, tank);
		if (!fluidDone && completer.getPendingFluidAmount() >= pending) {
			return Outcome.BLOCKED; // 流体也没进去，保持未修改语义
		}
		consumePendingInput(completer, context, processIndex);
		if (completer.hasPendingOutputs()) {
			return Outcome.DEFERRED;
		}
		completer.clearPendingOutputs();
		return Outcome.COMMITTED;
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

	/**
	 * 产物直通：把待提交产物先模拟、再直接写入相邻容器，跳过输出槽中转。
	 * <p>
	 * 与 {@link #pushPendingDirectToAe} 同构的记账协议：按<b>实际</b>接收量扣减 pending，
	 * 全部接收则移除该条目，部分接收则回写剩余数量继续挂起。外部容器写入不可回滚，
	 * 因此只要有任何产物被接收，调用方就必须提交一次输入。
	 *
	 * @return true 表示至少有一件产物被相邻容器接收
	 */
	private static boolean pushPendingDirectToNeighbors(
			PbRecipeCompleter completer, PbRecipeContext context) {
		Map<ItemStack, Integer> pendingOutputs = completer.getPendingOutputs();
		if (pendingOutputs.isEmpty()) return false;
		boolean acceptedAny = false;
		Iterator<Map.Entry<ItemStack, Integer>> iterator = pendingOutputs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ItemStack, Integer> entry = iterator.next();
			int requested = Math.max(0, entry.getValue());
			if (requested <= 0) {
				iterator.remove();
				continue;
			}
			ItemStack stack = entry.getKey().copyWithCount(requested);
			int accepted = Math.max(0, Math.min(requested,
					context.productivebeesgenesis$pushGeneratedItemToNeighbors(stack)));
			if (accepted <= 0) continue;
			acceptedAny = true;
			completer.consumePendingItemCount(accepted);
			if (accepted >= requested) {
				iterator.remove();
			} else {
				entry.setValue(requested - accepted);
			}
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
}
