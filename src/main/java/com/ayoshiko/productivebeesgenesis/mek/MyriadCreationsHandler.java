package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * 万象创世处理器 — 封装万象创世蜜脾/蜜脾块的特殊处理路径
 * <br/>
 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责万象创世产物向随机
 * 蜜脾/蜜脾块的转化与插入，不涉及普通 PB CentrifugeRecipe 的处理流程。
 * <p>
 * 共享状态：每进程的 {@code pbOperatingTicks}、{@code pbProcessing}、
 * {@code pbProcessingTime}、{@code cachedPbRecipes} 数组由 {@link PbRecipeProcessor} 持有，
 * 本类通过构造时传入的数组引用直接读写（Java 数组为引用语义，变更对协调器可见）。
 * 每 tick 缓存的能量/操作数由调用方作为方法参数传入，避免本类持有易变的每 tick 状态。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
public class MyriadCreationsHandler {

	/** 万象创世日志冷却间隔（tick） — 避免输出阻塞时 WARN 刷屏 */
	private static final int MYRIAD_LOG_COOLDOWN = 100;

	/** getTicksForBase 缓存失效间隔（tick） — 升级变更后最多 1 秒内反映新值 */
	private static final int TICKS_CACHE_INTERVAL = 20;

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

	/** 每进程上次打印"万象产物无法插入"日志的游戏刻 */
	private final long[] lastMyriadFullLogTick;

	/** 每进程上次打印"万象类型缓存为空"日志的游戏刻 */
	private final long[] lastMyriadEmptyCacheLogTick;

	/** 可复用的输出槽列表（避免每次完成配方都创建新ArrayList） */
	private final List<IInventorySlot> reusableOutputSlots = new ArrayList<>(3);

	/**
	 * 缓存的 getTicksForBase(baseTicksRequired) 结果 — 用于万象创世处理时间计算
	 * <br/>
	 * getTicksForBase 内部涉及升级组件遍历与 Math.pow 计算，在升级未变更时结果稳定，
	 * 通过时间窗口缓存避免每 tick 每进程重复计算。升级变更后最多 20 tick（1秒）内自动反映新值。
	 * <p>
	 * 线程安全：cachedTicksForBase 和 cachedTicksForBaseAt 为 volatile，读写原子；
	 * 方块实体在服务端单线程执行，多进程共享同一缓存（升级组件为工厂级共享）。
	 */
	private volatile int cachedTicksForBase = -1;

	/** 上次计算 cachedTicksForBase 时的游戏刻（-1 表示未计算） */
	private volatile long cachedTicksForBaseAt = -1L;

	public MyriadCreationsHandler(PbRecipeContext context, String logPrefix,
								  int[] pbOperatingTicks, boolean[] pbProcessing,
								  int[] pbProcessingTime, RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes) {
		this.context = context;
		this.logPrefix = logPrefix;
		this.pbOperatingTicks = pbOperatingTicks;
		this.pbProcessing = pbProcessing;
		this.pbProcessingTime = pbProcessingTime;
		this.cachedPbRecipes = cachedPbRecipes;
		int processes = context.processes();
		this.lastMyriadFullLogTick = new long[processes];
		this.lastMyriadEmptyCacheLogTick = new long[processes];
		Arrays.fill(lastMyriadFullLogTick, -1L);
		Arrays.fill(lastMyriadEmptyCacheLogTick, -1L);
	}

	/**
	 * 尝试处理万象创世蜜脾/蜜脾块（单进程）
	 * <br/>
	 * 万象创世蜜脾转化为随机蜜脾（最多3种，总数=生产力倍率）。
	 * 万象创世蜜脾块转化为随机蜜脾块（最多3种，总数=生产力倍率*4）。
	 * 使用PB原版离心机的标准处理时间。
	 * <p>
	 * 能量和操作数使用调用方（tryProcessPbRecipeInternal）已缓存的 cachedEnergyPerTick 和 cachedOperationsPerTick，
	 * 避免在此方法中重复调用 getEnergyPerTick/operationsPerTick（可能涉及 Math.pow 计算）。
	 *
	 * @param processIndex            进程索引
	 * @param input                   万象创世蜜脾或蜜脾块
	 * @param cachedEnergyPerTick     本 tick 缓存的每 tick 能量消耗
	 * @param cachedOperationsPerTick 本 tick 缓存的每 tick 操作数
	 * @return true 正在处理万象创世配方
	 */
	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
											 long cachedEnergyPerTick, int cachedOperationsPerTick) {
		// 万象创世使用固定的处理时间（参考PB原版离心机）
		int processingTime = getCachedTicksForBase();
		pbProcessingTime[processIndex] = processingTime;

		// 配方变更时重置进度
		if (cachedPbRecipes[processIndex] != null) {
			cachedPbRecipes[processIndex] = null;
			pbOperatingTicks[processIndex] = 0;
		}

		// 检查能量是否足够
		if (context.energyContainer().getEnergy() < cachedEnergyPerTick) {
			pbProcessing[processIndex] = true;
			return true;
		}

		// 累加进度并消耗能量
		pbProcessing[processIndex] = true;
		// MU扩展下每tick可处理多次（operationsPerTick>1），未加载MU时返回1
		for (int op = 0; op < cachedOperationsPerTick; op++) {
			if (context.energyContainer().getEnergy() < cachedEnergyPerTick) {
				break;
			}
			pbOperatingTicks[processIndex]++;
			context.energyContainer().extract(cachedEnergyPerTick, Action.EXECUTE, AutomationType.INTERNAL);

			if (pbOperatingTicks[processIndex] >= processingTime) {
				// 输出槽物理满时暂停处理，避免产物丢失；万象创世不再做类型数量预检
				if (areOutputSlotsFull(processIndex)) {
					pbOperatingTicks[processIndex] = processingTime;
					break;
				}

				boolean success;
				boolean usedBatchPath = false;
				// 未安装 MU 速度升级或本批次仅 1 个输入时，回退到原单件处理路径
				if (cachedOperationsPerTick <= 1) {
					success = completeMyriadCreations(input, processIndex, context.productivityModifier());
				} else {
					int inputCount = context.inputSlot(processIndex).getStack().getCount();
					int batchSize = Math.min(cachedOperationsPerTick, inputCount);
					if (batchSize <= 1) {
						success = completeMyriadCreations(input, processIndex, context.productivityModifier());
					} else {
						success = completeMyriadCreationsBatch(input, processIndex, batchSize);
						usedBatchPath = success;
					}
				}

				if (!success) {
					pbOperatingTicks[processIndex] = processingTime;
					break;
				}
				pbOperatingTicks[processIndex] = 0;
				if (context.inputSlot(processIndex).getStack().isEmpty()) {
					context.setPbActiveState(false, processIndex);
					break;
				}
				// 批量路径一次性消耗了本 tick 全部 operationsPerTick 配额，直接结束本轮循环
				if (usedBatchPath) {
					break;
				}
			}
		}

		return true;
	}

	/**
	 * 完成万象创世蜜脾/蜜脾块处理 — 转化为随机蜜脾/蜜脾块
	 * <br/>
	 * 万象创世蜜脾转化为随机蜜脾（最多3种，总数=生产力倍率）。
	 * 万象创世蜜脾块转化为随机蜜脾块（最多3种，总数=生产力倍率*4）。
	 * 使用MyriadCreationsEventHandler的随机类型选择和均匀分配算法。
	 * <p>
	 * 关键修复：
	 * <ul>
	 *   <li>按 bee_type 聚合产物后统一插入，同类型优先堆叠到同一槽</li>
	 *   <li>不再预检输出槽类型数量，只以物理上能否完整插入作为暂停依据</li>
	 *   <li>无法完全插入时返回 false，由调用方暂停；输入在全部产物插入成功后才会扣除</li>
	 * </ul>
	 *
	 * @param input                万象创世蜜脾或蜜脾块
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率
	 * @return true 处理成功，false 应暂停等待输出槽空间
	 */
	private boolean completeMyriadCreations(ItemStack input, int processIndex, int productivityModifier) {
		boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		int modifier = Math.max(1, productivityModifier);

		// 万象创世蜜脾块 = 4个蜜脾，输出总数乘以4
		int totalCount = isCombBlock ? modifier * 4 : modifier;

		// 限制种类数不超过3（输出槽数）和总数量
		int maxTypes = Math.min(3, totalCount);
		// Task 23: 使用带缓存的类型选择，降低 256x 加速下每 tick 多次随机采样的开销
		List<ResourceLocation> selectedTypes = MyriadCreationsEventHandler.selectDistinctBeeTypesCached(maxTypes, context.level());
		if (selectedTypes.isEmpty()) {
			// 缓存为空：不消耗输入，等待缓存重建后重试；按冷却期打印避免刷屏
			if (canLogMyriad(processIndex, lastMyriadEmptyCacheLogTick)) {
				ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世类型缓存为空，跳过本次处理（不消耗输入）", logPrefix, processIndex);
			}
			return true;
		}

		// 均匀分配totalCount到selectedTypes，已按 bee_type 聚合
		Map<ResourceLocation, Integer> allocation = MyriadCreationsEventHandler.allocateEvenly(totalCount, selectedTypes);

		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();

		// 构建输出槽列表
		buildOutputSlots(processIndex);

		// 用 MyriadBatchPlanner 规划插入（纯模拟，不复制 ItemStack、不触发 listener）
		// 修复原实现"部分插入后失败导致产物丢失"的 bug：plan 失败时不 apply，不扣输入
		MyriadBatchPlanner.Plan plan = MyriadBatchPlanner.plan(reusableOutputSlots, baseItem, allocation);
		if (!plan.isSuccess()) {
			if (canLogMyriad(processIndex, lastMyriadFullLogTick)) {
				ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世产物无法完全插入，暂停", logPrefix, processIndex);
			}
			return false;
		}

		// 执行计划：空槽 setStack、同类型槽 grow（零拷贝），由 endOutputBatch 统一触发标志位更新
		context.productivebeesgenesis$beginOutputBatch();
		try {
			MyriadBatchPlanner.apply(plan, reusableOutputSlots);
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
		}

		// 全部产物成功插入后才消耗输入（乘以生产力倍率）
		context.inputSlot(processIndex).shrinkStack(modifier, Action.EXECUTE);
		return true;
	}

	/**
	 * 批量完成万象创世蜜脾/蜜脾块处理
	 * <br/>
	 * 在 Mekanism Unleashed 速度升级下，本 tick 已到达处理时间时一次性处理 batchSize 个输入，
	 * 避免原循环每次只消耗 1 个输入导致的随机采样与插入开销。
	 * 输出总数 = batchSize × 倍率（蜜脾块为 4，蜜脾为 1），均匀分配到最多 3 种蜜蜂类型上，
	 * 使同类型产物更易堆叠，提高高倍加速下的吞吐。
	 * <p>
	 * 关键修复：不再从 {@code operationsPerTick} 开始逐级减半，而是先用
	 * {@link MyriadBatchPlanner#planOrFindMaxBatch} 计算输出槽剩余容量能容纳的最大输入数，
	 * 直接尝试该 batch size；若因类型分布导致 plan 失败，再按剩余容量比例保守降级。
	 * 仅在所有产物成功插入后才扣除输入，batchSize 本身已体现速度升级，不再额外乘以生产力倍率。
	 *
	 * @param input        万象创世蜜脾或蜜脾块
	 * @param processIndex 进程索引
	 * @param batchSize    本批次期望处理的输入数量
	 * @return true 处理成功，false 应暂停等待输出槽空间
	 */
	private boolean completeMyriadCreationsBatch(ItemStack input, int processIndex, int batchSize) {
		if (batchSize <= 0) return true;

		boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		int multiplier = isCombBlock ? 4 : 1;
		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();

		// 构建输出槽列表（processIndex 在方法内不变，构建一次即可）
		buildOutputSlots(processIndex);

		Level level = context.level();
		if (level == null) return false;

		// 一次性拍摄容量快照：同一 tick 内同一进程的输出槽 limit 不变，避免 plan 反复调用 getLimit
		MyriadBatchPlanner.SlotCapacitySnapshot snapshot =
				MyriadBatchPlanner.takeSnapshot(reusableOutputSlots, baseItem, level.getGameTime());

		// 候选蜜蜂类型在 tick 内缓存，减少批量路径下每轮都随机采样的开销
		List<ResourceLocation> selectedTypes = MyriadCreationsEventHandler.selectDistinctBeeTypesCached(3, level);
		if (selectedTypes.isEmpty()) {
			// 缓存为空时不消耗输入，等待缓存重建后重试
			if (canLogMyriad(processIndex, lastMyriadEmptyCacheLogTick)) {
				ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世类型缓存为空，跳过本次批量处理（不消耗输入）", logPrefix, processIndex);
			}
			return true;
		}

		// 根据输出槽剩余总容量与产物倍率直接计算最大可行 batch size，避免从 operationsPerTick 逐级减半
		int maxBatch = MyriadBatchPlanner.planOrFindMaxBatch(snapshot, baseItem, multiplier, selectedTypes, batchSize);
		if (maxBatch <= 0) {
			if (canLogMyriad(processIndex, lastMyriadFullLogTick)) {
				ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世批量产物无法完全插入，暂停：batchSize={}", logPrefix, processIndex, batchSize);
			}
			return false;
		}

		int currentBatch = maxBatch;
		int totalCount = currentBatch * multiplier;
		int typesToUse = Math.min(selectedTypes.size(), Math.max(1, Math.min(totalCount, 3)));
		Map<ResourceLocation, Integer> allocation = MyriadCreationsEventHandler.allocateEvenly(
				totalCount, selectedTypes.subList(0, typesToUse));

		MyriadBatchPlanner.Plan plan = MyriadBatchPlanner.plan(snapshot, baseItem, allocation);
		if (!plan.isSuccess()) {
			// planOrFindMaxBatch 已按均匀分配保证成功；若因实现差异仍失败，按剩余容量比例保守降级
			long remainingCapacity = snapshot.totalRemainingCapacity;
			int fallbackBatch = totalCount > 0
					? (int) Math.max(1, currentBatch * remainingCapacity / (long) totalCount)
					: 1;
			if (fallbackBatch >= currentBatch) {
				fallbackBatch = currentBatch - 1;
			}
			if (fallbackBatch <= 0) {
				if (canLogMyriad(processIndex, lastMyriadFullLogTick)) {
					ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世批量产物无法完全插入，暂停：batchSize={}", logPrefix, processIndex, batchSize);
				}
				return false;
			}
			currentBatch = fallbackBatch;
			totalCount = currentBatch * multiplier;
			typesToUse = Math.min(selectedTypes.size(), Math.max(1, Math.min(totalCount, 3)));
			allocation = MyriadCreationsEventHandler.allocateEvenly(totalCount, selectedTypes.subList(0, typesToUse));
			plan = MyriadBatchPlanner.plan(snapshot, baseItem, allocation);
			if (!plan.isSuccess()) {
				if (canLogMyriad(processIndex, lastMyriadFullLogTick)) {
					ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世批量产物无法完全插入，暂停：batchSize={}", logPrefix, processIndex, batchSize);
				}
				return false;
			}
		}

		context.productivebeesgenesis$beginOutputBatch();
		try {
			MyriadBatchPlanner.apply(plan, reusableOutputSlots);
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
		}
		context.inputSlot(processIndex).shrinkStack(currentBatch, Action.EXECUTE);
		return true;
	}

	/** 构建指定进程的输出槽列表（主+副1+副2） */
	private void buildOutputSlots(int processIndex) {
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
		}
		reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));
	}

	/**
	 * 检查指定进程的万象创世日志是否已超过冷却间隔
	 * <br/>
	 * 输出阻塞时同一条 WARN 每 tick 打印会严重拖慢 TPS（Spark 显示 Log4jLogger.warn 占 78%），
	 * 通过 100 tick（5秒）冷却期抑制高频重复日志，同时保留问题诊断能力。
	 *
	 * @param processIndex 进程索引
	 * @param lastLogTicks 各进程上次打印日志的游戏刻数组
	 * @return true 如果当前可以打印日志
	 */
	private boolean canLogMyriad(int processIndex, long[] lastLogTicks) {
		Level level = context.level();
		if (level == null) return false;
		long now = level.getGameTime();
		long last = lastLogTicks[processIndex];
		if (last < 0 || now - last >= MYRIAD_LOG_COOLDOWN) {
			lastLogTicks[processIndex] = now;
			return true;
		}
		return false;
	}

	/**
	 * 获取缓存的 getTicksForBase(baseTicksRequired) 结果（时间窗口缓存）
	 * <br/>
	 * 升级组件哈希计算开销较大且升级变更不频繁，采用"每 N tick 重新计算一次"策略，
	 * 与 TileEntityMekCentrifuge.getCachedTicks 模式一致。
	 * 升级变更后最多 {@link #TICKS_CACHE_INTERVAL} tick（1秒）内自动反映新值，可接受。
	 *
	 * @return 受速度升级影响的 baseTicksRequired 处理时间
	 */
	private int getCachedTicksForBase() {
		Level level = context.level();
		long currentTick = level != null ? level.getGameTime() : 0L;
		if (cachedTicksForBase < 0 || (currentTick - cachedTicksForBaseAt) >= TICKS_CACHE_INTERVAL) {
			cachedTicksForBase = context.getTicksForBase(context.baseTicksRequired());
			cachedTicksForBaseAt = currentTick;
		}
		return cachedTicksForBase;
	}

	/** 配方重载时失效 cachedTicksForBase（由 PbRecipeProcessor.checkRecipeVersion 调用） */
	public void clearCachedTicksForBase() {
		cachedTicksForBase = -1;
	}

	/**
	 * 检查指定进程的所有物品输出槽是否已满
	 * <br/>
	 * 满时暂停处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE行为一致）。
	 * 仅检查物品槽，流体槽满时不暂停。
	 */
	private boolean areOutputSlotsFull(int process) {
		return context.productivebeesgenesis$outputSlotsFull(process);
	}
}
