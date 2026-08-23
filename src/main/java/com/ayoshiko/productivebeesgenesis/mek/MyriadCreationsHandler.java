package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
	 * 万象创世处理器 — 封装万象创世蜜脾/蜜脾块的特殊处理路径。
	 * 从 {@link PbRecipeProcessor} 抽取，只负责万象创世产物向随机蜜脾/蜜脾块的转化与插入。
	 * 共享状态通过构造时传入的数组引用直接读写，线程安全由服务端单线程执行保证。
	 * <p>
	 * 职责分离（SRP）：
	 * <ul>
	 *   <li>本类：配方处理流程（能量计算、并行操作、批次完成）</li>
	 *   <li>{@link MyriadCreationsCache}：ticksForBase / maxOpsPerTick 缓存与输出空间判断</li>
	 *   <li>{@link MyriadCreationsLogger}：日志节流与洪水治理</li>
	 * </ul>
	 */
public class MyriadCreationsHandler {

	/** 万象创世输出槽总数（主+副1+副2 = 3，必须与 MekCentrifugeSlotManager 实际输出槽数一致，与 MyriadBatchPlanner 硬编码上限 3 匹配） */
	private static final int OUTPUT_SLOT_COUNT = 3;

	/** 批量降级重试最大次数（每次减半） */
	private static final int MAX_DEGRADATION_ATTEMPTS = 3;

	/** 高 STACK 路径阈值：batchSize ≥ 此值时走 WeightedTypeSelector 工厂级共享选型 */
	private static final int WEIGHTED_SELECTOR_THRESHOLD = 1024;

	/** PB配方处理上下文 */
	private final PbRecipeContext context;

	/** 日志前缀（区分原版/ME/EME工厂） */
	private final String logPrefix;

	/** PB配方处理进度（tick） — 每进程独立，与协调器共享 */
	private final int[] pbOperatingTicks;

	/** PB配方是否正在处理 — 每进程独立，与协调器共享 */
	private final boolean[] pbProcessing;

	/** PB配方处理总时间（tick） — 每进程独立，与协调器共享 */
	private final int[] pbProcessingTime;

	/** PB离心配方缓存 — 每进程独立，与协调器共享（万象路径设为 null） */
	private final RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes;

	/** 流体输出处理器 — 委托处理配方定义的流体输出与流体槽满载缓存 */
	private final MyriadFluidOutputHandler fluidOutputHandler;

	/** 每进程的万象创世概率池 — Task 3 简化为委托 WeightedTypeSelector */
	private final MyriadProductPool[] myriadProductPools;

	/** 可复用的输出槽列表（避免每次完成配方都创建新ArrayList） */
	private final List<IInventorySlot> reusableOutputSlots = new ArrayList<>(3);

	/**
	 * reusableOutputSlots 中的列表索引到工厂物理输出槽索引（0=主，1=副1，2=副2）。
	 * 副槽可能为 null，列表会跳过它们，因此不能直接使用列表索引更新增量缓存。
	 */
	private final int[] reusableSlotIdxMap = new int[OUTPUT_SLOT_COUNT];

	/** 缓存与过滤管理器（ticksForBase / maxOpsPerTick 缓存 + 输出槽满载判断） */
	private final MyriadCreationsCache cache = new MyriadCreationsCache();

	/** 日志管理器（带冷却和抑制计数的日志输出） */
	private final MyriadCreationsLogger logger;
	/** Coalesces all blocked processes into one diagnostic probe per real game tick. */
	private long lastFluidBlockedProbeTick = Long.MIN_VALUE;

	/** 构造万象创世处理器 */
	public MyriadCreationsHandler(PbRecipeContext context, String logPrefix,
			int[] pbOperatingTicks, boolean[] pbProcessing,
			int[] pbProcessingTime, RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes,
			PbRecipeFinder recipeFinder) {
		this.context = context;
		this.logPrefix = logPrefix;
		this.pbOperatingTicks = pbOperatingTicks;
		this.pbProcessing = pbProcessing;
		this.pbProcessingTime = pbProcessingTime;
		this.cachedPbRecipes = cachedPbRecipes;
		int processes = context.processes();
		this.fluidOutputHandler = new MyriadFluidOutputHandler(context, recipeFinder, logPrefix, processes);
		this.logger = new MyriadCreationsLogger(logPrefix, context, processes);
		this.myriadProductPools = new MyriadProductPool[processes];
		for (int i = 0; i < processes; i++) {
			// factoryKey = context（工厂实例），用于 WeightedTypeSelector 工厂级 tick 缓存的 WeakHashMap key
			this.myriadProductPools[i] = new MyriadProductPool(context);
		}
	}

	/**
	 * 尝试处理万象创世蜜脾/蜜脾块（单进程，使用调用方已缓存的能量和操作数）
	 *
	 * @param virtualTicks 本调用应推进的虚拟 tick 数（加速倍率，至少 1）— 用于进度显示对齐标准 PB 路径
	 */
	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long cachedEnergyPerTick, int cachedOperationsPerTick, int virtualTicks) {
		return tryProcessMyriadCreations(processIndex, input, cachedEnergyPerTick,
				cachedOperationsPerTick, virtualTicks, Long.MAX_VALUE);
	}

	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long cachedEnergyPerTick, int cachedOperationsPerTick, int virtualTicks, long energyBudget) {
		if (!fluidOutputHandler.flushPendingFluid(processIndex)) {
			pbProcessing[processIndex] = false;
			return false;
		}
		// Task 3 性能优化：每 tick 缓存流体槽满载状态
		fluidOutputHandler.initFluidTankFullCache();

		// 万象创世使用固定的处理时间（参考PB原版离心机）
		int processingTime = cache.getCachedTicksForBase(context);
		pbProcessingTime[processIndex] = processingTime;

		// 配方变更时重置进度
		if (cachedPbRecipes[processIndex] != null) {
			cachedPbRecipes[processIndex] = null;
			pbOperatingTicks[processIndex] = 0;
		}

		long availableEnergy = Math.min(context.energyContainer().getEnergy(), Math.max(0L, energyBudget));
		Level level = context.level();

		// 计算每周期并行操作数（STACK升级：2^stackUpgrades，受maxOpsPerTick配置限制）
		// 并行处理：进度由 PbVirtualTickPlan 按虚拟 tick 推进，完成一个周期（processingTime tick）
		// 时处理 opsPerCycle 个输入并消耗对应能量，语义与 PB 原版蜜脾路径完全一致
		// SubTask 5.1: maxOpsPerTick 配置 100-tick CAS 缓存，对齐 PbRecipeProcessor:261-264
		int maxOpsPerTick = cache.refreshAndGetMaxOps(level);
		int effectiveOps = (maxOpsPerTick > 0 && cachedOperationsPerTick > 1)
				? Math.min(cachedOperationsPerTick, maxOpsPerTick)
				: cachedOperationsPerTick;

		int inputCount = context.inputSlot(processIndex).getStack().getCount();
		effectiveOps = Math.min(effectiveOps, inputCount);

		// 与 PB 标准路径共用高并行边际能耗曲线，并按当前能量反算可执行操作数。
		if (cachedEnergyPerTick > 0) {
			int energyLimitedOps = MekCentrifugeEnergyScaling.affordableOperations(
					cachedEnergyPerTick, effectiveOps, availableEnergy);
			if (energyLimitedOps <= 0) {
				pbProcessing[processIndex] = false;
				return false;
			}
			effectiveOps = energyLimitedOps;
		}

		if (effectiveOps <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}

		if (!context.productivebeesgenesis$isDirectAeOutputEnabled()
				&& fluidOutputHandler.isFluidTankFull()
				&& pbOperatingTicks[processIndex] >= processingTime) {
			long gameTick = level == null ? Long.MIN_VALUE : level.getGameTime();
			if (gameTick != lastFluidBlockedProbeTick) {
				lastFluidBlockedProbeTick = gameTick;
				logger.logThrottledWarnGlobal(logger.globalFullLogThrottle,
						"{}万象创世流体槽已满，暂停完成批次：进程{} batchSize={}",
						logPrefix, processIndex, effectiveOps);
			}
			return true;
		}

		// 输出受阻且进度已满时不消耗能量。流体输出属于原子事务，
		// 满载时由上面的边界检查暂停，不能跳过并丢弃蜂蜜。
		if (MyriadCreationsCache.areOutputSlotsFull(context, processIndex)
				&& pbOperatingTicks[processIndex] >= processingTime) {
			pbOperatingTicks[processIndex] = processingTime;
			return true;
		}

		// 激活处理
		pbProcessing[processIndex] = true;

		// 输出受阻时最多推进到本周期完成边界，不能把后续虚拟 tick 也计入
		// 完成批次（对齐 PB 原版路径的 outputBlocked 裁剪语义，避免越过完成边界后
		// 在无输出空间时反复尝试完成周期）。
		if (MyriadCreationsCache.areOutputSlotsFull(context, processIndex)) {
			if (pbOperatingTicks[processIndex] >= processingTime) {
				pbOperatingTicks[processIndex] = processingTime;
				return true;
			}
			virtualTicks = Math.min(virtualTicks, Math.max(1, processingTime - pbOperatingTicks[processIndex]));
		}

		// 并行处理：复用 PbVirtualTickPlan 推进进度与完成周期，与 PB 原版蜜脾路径保持
		// 完全一致的语义 — 每调用推进 virtualTicks 个进度，达到 processingTime 完成一个周期
		// （处理 opsPerCycle 个输入），剩余虚拟 tick 继续推进下一周期进度。
		// 修复：此前每调用只完成一个周期并丢弃剩余 ticks，加速时万象蜜脾的进度条
		// 节奏与 PB 原版不一致（PB 会显示下一周期进度位置，万象却停在 0 重新开始）。
		int opsPerCycle = effectiveOps;
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(
				pbOperatingTicks[processIndex], Math.max(1, virtualTicks), processingTime,
				opsPerCycle, inputCount, cachedEnergyPerTick, availableEnergy);
		if (plan.executedTicks() <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}
		pbOperatingTicks[processIndex] = plan.remainingProgress();
		int actualOps = plan.completedOperations();
		int committedOps = actualOps;

		if (actualOps > 0) {
			// 输出受阻时暂停处理（仅物品槽满载才阻塞，流体满载可跳过）
			if (MyriadCreationsCache.areOutputSlotsFull(context, processIndex)) {
				pbOperatingTicks[processIndex] = processingTime;
				return true;
			}

			int completed;
			if (actualOps <= 1) {
				completed = completeMyriadCreations(input, processIndex, context.productivityModifier());
			} else {
				completed = completeMyriadCreationsBatch(input, processIndex, actualOps);
			}

			if (completed <= 0) {
				// The processing work reached a completion boundary, but the atomic
				// output transaction could not commit. Keep the boundary pending so the
				// next tick retries output without advancing or charging another cycle.
				pbOperatingTicks[processIndex] = processingTime;
				return true;
			}
			committedOps = completed;
			if (completed < actualOps) {
				// Output capacity can be smaller than the requested parallel batch.
				// Preserve a completion boundary for the uncommitted remainder instead
				// of silently dropping those operations.
				pbOperatingTicks[processIndex] = processingTime;
			}
			if (context.inputSlot(processIndex).getStack().isEmpty()) {
				context.setPbActiveState(false, processIndex);
			}
		}
		// 按实际提交的批次扣能量。输出槽容量可能小于虚拟并行数，
		// 此时 completeMyriadCreationsBatch 只提交 completed 个输入；若仍扣完整
		// plan.energyUsed()，会在输出受限时过度扣能量。
		long energyUsed = plan.energyUsed();
		if (actualOps > 0 && committedOps < actualOps) {
			energyUsed = MekCentrifugeEnergyScaling.batchEnergyCost(
					cachedEnergyPerTick, committedOps, plan.executedTicks());
		}
		if (energyUsed > 0L) {
			context.energyContainer().extract(energyUsed, Action.EXECUTE, AutomationType.INTERNAL);
		}

		return true;
	}

	/**
	 * 完成万象创世蜜脾/蜜脾块处理 — 按 bee_type 聚合后统一插入
	 * <br/>
	 * 返回实际完成的操作数（0 表示失败，1 表示单次操作成功）。
	 * 调用方根据返回值扣除能量：0 不扣能量（保留进度重试），1 扣 1 * cachedEnergyPerTick。
	 */
	private int completeMyriadCreations(ItemStack input, int processIndex, int productivityModifier) {
		boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		int modifier = Math.max(1, productivityModifier);

		// 万象创世蜜脾块 = 4个蜜脾，输出总数乘以4
		int totalCount = isCombBlock ? SaturatingMath.saturatingToInt(
				SaturatingMath.saturatingMultiply(modifier, 4)) : modifier;

		// 限制种类数不超过输出槽数和总数量
		int maxTypes = Math.min(OUTPUT_SLOT_COUNT, totalCount);
		// Task 23: 使用带缓存的类型选择，降低 256x 加速下每 tick 多次随机采样的开销
		List<ResourceLocation> selectedTypes = MyriadCreationsEventHandler.selectDistinctBeeTypesCached(maxTypes,
			context.level());
		if (selectedTypes.isEmpty()) {
			// 缓存为空时保留进度等待预热完成（不扣能量、不扣输入）
			logger.logEmptyCacheAndPreserve(processIndex);
			return 0;
		}

		// SubTask 4.6: 概率池模式 — effectiveOps=1（本路径仅单次操作）时池返回原列表保留原版语义
		Level level = context.level();
		long currentTick = level != null ? level.getGameTime() : 0L;
		selectedTypes = myriadProductPools[processIndex].getOrRefresh(selectedTypes, currentTick, 1, level);

		// SubTask 5.4: 按权重比例分配 totalCount，权重高的类型获得较多产出（替代 allocateEvenly）
		double[] weights = WeightedTypeSelector.getInstance().getWeightsFor(selectedTypes);
		Map<ResourceLocation, Integer> allocation = WeightedAllocation.allocateByWeight(totalCount, selectedTypes, weights);

		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();

		buildOutputSlots(processIndex);

		// 用 MyriadBatchPlanner 规划插入（纯模拟），plan 失败时不 apply 不扣输入
		MyriadBatchPlanner.Plan plan = MyriadBatchPlanner.plan(reusableOutputSlots, baseItem, allocation, currentTick);
		if (!plan.isSuccess()) {
			logger.logThrottledWarnGlobal(logger.globalFullLogThrottle, "{}万象创世产物无法完全插入，暂停：进程{}", logPrefix, processIndex);
			return 0;
		}

		// 执行计划 + 插入流体 + 扣除输入，全部在 begin/endOutputBatch 之内
		context.productivebeesgenesis$beginOutputBatch();
		try {
			// The fluid output is part of the atomic Myriad transaction. A full local
			// tank must pause the cycle (or use direct AE output), never discard honey
			// while still consuming the input and committing combs.
			MyriadFluidOutputHandler.InsertResult fluidResult =
					fluidOutputHandler.insertFluidOutput(input, modifier, processIndex);
			if (!fluidResult.committed()) {
				// v9-P2 修复：回收成功的 plan 防止对象池泄漏
				MyriadBatchPlanner.recyclePlan(plan);
				return 0;
			}
			MyriadBatchPlanner.apply(plan, reusableOutputSlots,
					context::productivebeesgenesis$expectOutputSlotChange,
					(slotIndex, slot) -> context.productivebeesgenesis$updateSlotOnly(
							processIndex, reusableSlotIdxMap[slotIndex], slot));
			// 修复：每次操作只消耗1个输入，productivityModifier只影响输出数量不影响输入消耗
			context.inputSlot(processIndex).shrinkStack(1, Action.EXECUTE);
			// SubTask 5.7: 记录实际产出供 WeightedTypeSelector 更新 EMA 权重表
			WeightedTypeSelector.getInstance().recordOutputs(allocation);
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
		}
		// Task 3 性能优化：insertFluidOutput 改变了流体槽内容，更新满载缓存避免循环内使用 stale 值
		fluidOutputHandler.refreshFluidTankFullCache();
		return 1;
	}

	/**
	 * 批量完成万象创世蜜脾/蜜脾块处理 — 使用 MyriadBatchPlanner 计算最大可行 batch size，仅在所有产物成功插入后才扣除输入
	 * <br/>
	 * 返回实际完成的操作数（0 表示失败，>0 表示成功完成的批次大小 currentBatch）。
	 * 调用方根据返回值扣除能量：0 不扣能量（保留进度重试），N 扣 N * cachedEnergyPerTick。
	 * 修复：原实现返回 boolean 且调用方按 effectiveOps 扣能量，导致 currentBatch < effectiveOps 时能量过度扣除。
	 */
	private int completeMyriadCreationsBatch(ItemStack input, int processIndex, int batchSize) {
		if (batchSize <= 0) return 0;

		boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		int multiplier = isCombBlock ? 4 : 1;
		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();

		// 构建输出槽列表（processIndex 在方法内不变，构建一次即可）
		buildOutputSlots(processIndex);

		Level level = context.level();
		if (level == null) return 0;

		// 流体是配方产物的一部分。满载时在随机选型和物品规划之前快速暂停，
		// 避免 256x 加速下每 tick 重做权重分配，同时防止静默丢弃蜂蜜。
		int productivityMod = Math.max(1, context.productivityModifier());
		int maxFluidBatch = fluidOutputHandler.getMaxBatchForFluid(input, productivityMod);
		if (maxFluidBatch <= 0) {
			logger.logThrottledWarnGlobal(logger.globalFullLogThrottle,
					"{}万象创世流体槽已满，暂停处理：进程{} batchSize={}", logPrefix, processIndex, batchSize);
			return 0;
		}
		int effectiveBatchSize = Math.min(batchSize, maxFluidBatch);

		// 一次性拍摄容量快照：同一 tick 内同一进程的输出槽 limit 不变，避免 plan 反复调用 getLimit
		MyriadBatchPlanner.SlotCapacitySnapshot snapshot =
				MyriadBatchPlanner.takeSnapshot(reusableOutputSlots, baseItem, level.getGameTime());

		// 候选蜜蜂类型选择 — SubTask 5.3 + 5.10 工厂级共享选型
		// 高 STACK（batchSize ≥ 1024）走 WeightedTypeSelector.selectForProcess：
		//   一次 selectWeighted(processCount × 3, ...) 后切片分发，19 进程独立 3 类型（覆盖 EM CREATIVE 最高等级）
		// 低 STACK（batchSize < 1024）走原版 selectDistinctBeeTypesCached + MyriadProductPool 委托
		List<ResourceLocation> selectedTypes;
		if (batchSize >= WEIGHTED_SELECTOR_THRESHOLD) {
			List<ResourceLocation> allBeeTypes = MyriadBeeTypeCache.cachedBeeTypes();
			if (allBeeTypes.isEmpty()) {
				logger.logEmptyCacheAndPreserve(processIndex);
				return 0;
			}
			selectedTypes = WeightedTypeSelector.getInstance().selectForProcess(
					processIndex, context.processes(), level, allBeeTypes, context);
		} else {
			selectedTypes = MyriadCreationsEventHandler.selectDistinctBeeTypesCached(
					Math.min(OUTPUT_SLOT_COUNT, 9), level);
			if (selectedTypes.isEmpty()) {
				logger.logEmptyCacheAndPreserve(processIndex);
				return 0;
			}
			selectedTypes = myriadProductPools[processIndex].getOrRefresh(
					selectedTypes, level.getGameTime(), batchSize, level);
		}
		if (selectedTypes.isEmpty()) {
			// 防御性兜底（selectForProcess 返回空列表的极端场景）
			logger.logEmptyCacheAndPreserve(processIndex);
			return 0;
		}

		// Task 2 修复：batchSize 限制为流体槽可容纳的最大操作数（原实现仅考虑物品槽导致 STACK 升级下流体失败）
		// Task 4 根因修复：万象创世的主要产出是随机蜜脾物品，流体（蜂蜜）是副产物。
		// 流体槽满载时不应阻塞物品产出，跳过流体输出继续处理物品。
		// 根据输出槽剩余总容量与产物倍率直接计算最大可行 batch size，避免从 operationsPerTick 逐级减半
		int outputPerOperation = SaturatingMath.saturatingToInt(
				SaturatingMath.saturatingMultiply(multiplier, productivityMod));
		int maxBatch = MyriadBatchPlanner.planOrFindMaxBatch(snapshot, baseItem, outputPerOperation, selectedTypes,
			effectiveBatchSize);
		if (maxBatch <= 0) {
			logger.logThrottledWarnGlobal(logger.globalFullLogThrottle, "{}万象创世产物无法完全插入，暂停：进程{} batchSize={}",
					logPrefix, processIndex,
				batchSize);
			return 0;
		}

		int currentBatch = maxBatch;
		// 修复：产量需要乘以productivityModifier，原实现遗漏导致产量升级在批量模式下无效
		// productivityMod 已在上方 Task 2 修复中提前计算（用于流体空间约束）
		int totalCount = SaturatingMath.saturatingToInt(
				SaturatingMath.saturatingMultiply(currentBatch, multiplier, productivityMod));
		int typesToUse = Math.min(selectedTypes.size(), Math.max(1, Math.min(totalCount, OUTPUT_SLOT_COUNT)));
		// SubTask 5.5: 按权重比例分配 totalCount（替代 allocateEvenly）
		List<ResourceLocation> activeTypes = selectedTypes.subList(0, typesToUse);
		double[] activeWeights = WeightedTypeSelector.getInstance().getWeightsFor(activeTypes);
		Map<ResourceLocation, Integer> allocation = WeightedAllocation.allocateByWeight(totalCount, activeTypes,
			activeWeights);

		MyriadBatchPlanner.Plan plan = MyriadBatchPlanner.plan(snapshot, baseItem, allocation);
		// v9-L3 修复：逐步降级重试（MAX_DEGRADATION_ATTEMPTS 次，每次减半），替代原先仅尝试一次的保守降级
		int degradationAttempts = 0;
		while (!plan.isSuccess() && currentBatch > 1 && degradationAttempts < MAX_DEGRADATION_ATTEMPTS) {
			currentBatch = Math.max(1, currentBatch / 2);
			totalCount = SaturatingMath.saturatingToInt(
					SaturatingMath.saturatingMultiply(currentBatch, multiplier, productivityMod));
			typesToUse = Math.min(selectedTypes.size(), Math.max(1, Math.min(totalCount, OUTPUT_SLOT_COUNT)));
			// SubTask 5.6: 降级重试同样用 allocateByWeight
			activeTypes = selectedTypes.subList(0, typesToUse);
			activeWeights = WeightedTypeSelector.getInstance().getWeightsFor(activeTypes);
			allocation = WeightedAllocation.allocateByWeight(totalCount, activeTypes, activeWeights);
			plan = MyriadBatchPlanner.plan(snapshot, baseItem, allocation);
			degradationAttempts++;
		}
		if (!plan.isSuccess()) {
			logger.logThrottledWarnGlobal(logger.globalFullLogThrottle, "{}万象创世产物无法完全插入，暂停：进程{} batchSize={}",
					logPrefix, processIndex,
				batchSize);
			return 0;
		}

		// 修复：流体插入和输入扣除在批次内执行，与正常 PB 路径保持一致
		context.productivebeesgenesis$beginOutputBatch();
		try {
			// Task 4 根因修复：流体是万象创世的副产物，满载时跳过，不阻塞物品产出
			// v9-M2 修复：先插入流体（含空间检查），失败时不插入物品、不扣输入
			long fluidAmount = SaturatingMath.saturatingMultiply(currentBatch, productivityMod);
			MyriadFluidOutputHandler.InsertResult fluidResult =
					fluidOutputHandler.insertFluidOutput(input, fluidAmount, processIndex);
			if (!fluidResult.committed()) {
				MyriadBatchPlanner.recyclePlan(plan);
				return 0;
			}
			MyriadBatchPlanner.apply(plan, reusableOutputSlots,
					context::productivebeesgenesis$expectOutputSlotChange,
					(slotIndex, slot) -> context.productivebeesgenesis$updateSlotOnly(
							processIndex, reusableSlotIdxMap[slotIndex], slot));
			context.inputSlot(processIndex).shrinkStack(currentBatch, Action.EXECUTE);
			// SubTask 5.7: 记录实际产出供 WeightedTypeSelector 更新 EMA 权重表
			WeightedTypeSelector.getInstance().recordOutputs(allocation);
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
		}
		// Task 3 性能优化：insertFluidOutput 改变了流体槽内容，更新满载缓存避免循环内使用 stale 值
		fluidOutputHandler.refreshFluidTankFullCache();
		return currentBatch;
	}

	/** 构建指定进程的输出槽列表（主+副1+副2，跳过 null 槽位） */
	private void buildOutputSlots(int processIndex) {
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		reusableSlotIdxMap[0] = 0;
		int reusableSlotCount = 1;
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
			reusableSlotIdxMap[reusableSlotCount++] = 1;
		}
		IInventorySlot tertiary = context.tertiaryOutputSlot(processIndex);
		if (tertiary != null) {
			reusableOutputSlots.add(tertiary);
			reusableSlotIdxMap[reusableSlotCount] = 2;
		}
	}

	/** 配方重载时失效 cachedTicksForBase（由 PbRecipeProcessor.checkRecipeVersion 调用） — 委托至缓存管理器 */
	public void clearCachedTicksForBase() {
		cache.clearCachedTicksForBase();
	}

	public void saveAdditional(CompoundTag nbt) {
		fluidOutputHandler.saveAdditional(nbt);
	}

	public void loadAdditional(CompoundTag nbt) {
		fluidOutputHandler.loadAdditional(nbt);
	}
}
