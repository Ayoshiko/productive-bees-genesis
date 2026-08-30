package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.MekanismContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
	 * PB配方处理器 — 主协调器，委托子组件处理配方查找/输出聚合/万象创世/能量缓存/输出检查。
	 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
	 */
public class PbRecipeProcessor {
	private static final String NBT_COMMITTED_PENDING = "productivebeesgenesis_pb_committed_pending";

	/** PB配方处理上下文 — 由Factory TileEntity提供 */
	private final PbRecipeContext context;

	/** 日志前缀（区分原版/ME/EME工厂） */
	private final String logPrefix;

	/** PB配方处理进度（tick） — 每进程独立，真实进度 */
	private final int[] pbOperatingTicks;

	/** Task 23: 同步缓冲数组（trackArray 监控此数组而非 pbOperatingTicks，实现节流） */
	private final int[] syncedOperatingTicks;

	/** PB配方是否正在处理 — 每进程独立 */
	private final boolean[] pbProcessing;

	/** PB配方处理总时间（tick） — 每进程独立，同步到客户端用于进度条显示 */
	private final int[] pbProcessingTime;

	/** PB离心配方缓存 — 每进程独立 */
	@Nullable
	private final RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes;

	/** 多流体预留阶段的同 tick 输入快照；只用于复用已完成的配方查找。 */
	private final ItemStack[] reservedPbInputs;
	/** 多流体预留阶段的配方结果，null 表示该输入已确认没有 PB 配方。 */
	@Nullable
	private final RecipeHolder<CentrifugeRecipe>[] reservedPbRecipes;

	/** 配方查找器（双层缓存：inputRecipeCache 指纹TTL + pbRecipeCache LRU） */
	private final PbRecipeFinder recipeFinder;

	/** 输出聚合器数组（每进程独立，批量插入减少 listener 触发次数） */
	private final PbRecipeCompleter[] recipeCompleters;

	/** 万象创世处理器（持有共享数组引用） */
	private final MyriadCreationsHandler myriadHandler;

	/** 能量与 ticks 缓存 — 时间窗口内 getTicksForBase 结果缓存 */
	private final PbRecipeEnergyCache energyCache;

	/** SMELTING配方缓存（封装每进程的输入到SMELTING配方存在性映射） */
	private final SmeltingRecipeCache smeltingCache;

	/** 上次缓存时的配方版本号 — 用于检测配方重载，volatile 保证可见性 */
	private volatile long lastRecipeVersion = -1L;

	/** 上次检查配方版本时的 gameTick — 每 gameTick 仅查询一次 RECIPE_VERSION，避免 256x 下高频 volatile 读 */
	private long lastCheckedGameTick = -1L;

	/** 缓存的每tick能量消耗（每次进入处理方法时刷新，避免循环内重复调用可能涉及Math.pow的计算） */
	private long cachedEnergyPerTick;

	/** 缓存的每tick操作数（每次进入处理方法时刷新，升级变更会在下次进入方法时自动反映） */
	private int cachedOperationsPerTick;

	/** Task 3 性能优化：每 tick 缓存的流体槽满载状态，volatile 保证可见性 */
	private volatile boolean cachedFluidTankFull = false;

	/** maxOpsPerTick 配置缓存刷新间隔（tick）— 参考 BeeSlotTickProcessor.CONFIG_REFRESH_INTERVAL */
	private static final int MAX_OPS_REFRESH_INTERVAL = 100;

	/** 缓存的 maxOpsPerTick 配置值（每 100 tick 刷新，避免每 tick 反射式 NeoForge 配置查询） */
	private volatile int cachedMaxOpsPerTick = 0;

	/** 上次刷新 maxOpsPerTick 配置缓存的游戏刻 — volatile 保证跨线程可见性 */
	private volatile long lastMaxOpsRefreshTick = 0L;


	/** Task 23: 进度同步计数器（每 tick 递增，% 5 == 0 时同步） */
	private int progressSyncCounter = 0;

	/** 进程异常日志冷却器（每处理器实例独立，tick 模式） */
	private final LogThrottle pbErrorThrottle = new LogThrottle();

	/** 当前 tick 的批量倍率（Tick 加速检测器设置,默认 1 表示无加速） — 由调用方在 tick 入口设置,使用后自动重置 */
	private int tickMultiplier = 1;

	/** Rotating first process so shared output/energy constraints cannot permanently favor slot zero. */
	private int processStartCursor = 0;
	/** Prevents repeated factory-wide fluid scans during accelerated sub-ticks. */
	private long lastFluidFullCacheTick = Long.MIN_VALUE;
	/** Prevents repeated recipe scans used only to reserve multi-fluid lanes. */
	private long lastFluidReservationTick = Long.MIN_VALUE;
	/** 预留查找缓存的有效 tick/配方版本，任一变化都会使缓存失效。 */
	private long reservedPbRecipeCacheTick = Long.MIN_VALUE;
	private long reservedPbRecipeCacheVersion = Long.MIN_VALUE;

	/** @param context PB配方处理上下文 @param logPrefix 日志前缀 */
	@SuppressWarnings("unchecked")
	public PbRecipeProcessor(PbRecipeContext context, String logPrefix) {
		this.context = context;
		this.logPrefix = logPrefix;
		int processes = context.processes();
		this.pbOperatingTicks = new int[processes];
		this.syncedOperatingTicks = new int[processes];
		this.pbProcessing = new boolean[processes];
		this.pbProcessingTime = new int[processes];
		this.smeltingCache = new SmeltingRecipeCache(processes);
		this.cachedPbRecipes = new RecipeHolder[processes];
		this.reservedPbInputs = new ItemStack[processes];
		this.reservedPbRecipes = new RecipeHolder[processes];
		this.energyCache = new PbRecipeEnergyCache(context);
		// 子组件：查找器自拥有缓存；输出聚合器自拥有 pending 缓冲；
		// 万象处理器持有共享数组引用（Java 数组为引用语义，本类对其的变更对万象处理器可见，反之亦然）
		this.recipeFinder = new PbRecipeFinder(context);
		// 每进程独立的输出聚合器（避免多进程在同一 tick 内处理不同蜜脾时配方切换强制 flush 其他进程的 pending 输出）
		this.recipeCompleters = new PbRecipeCompleter[processes];
		for (int i = 0; i < processes; i++) {
			this.recipeCompleters[i] = new PbRecipeCompleter(context);
		}
		this.myriadHandler = new MyriadCreationsHandler(context, logPrefix,
				pbOperatingTicks, pbProcessing, pbProcessingTime, cachedPbRecipes, recipeFinder);
	}

	/** 设置本 tick 的批量倍率（加速模组场景下 N 倍产出跳过 N-1 次重复 tick，使用后自动重置） */
	public void setTickMultiplier(int multiplier) {
		this.tickMultiplier = Math.max(1, multiplier);
	}

	/**
	 * 取出当前批量倍率并重置为 1。
	 * <p>
	 * 工厂包含多个独立进程，倍率必须由工厂循环在入口处只消费一次，
	 * 然后为每个进程分别设置；不能让第一个进程在内部消费掉共享字段。
	 */
	public int consumeTickMultiplier() {
		int multiplier = tickMultiplier;
		tickMultiplier = 1;
		return multiplier;
	}

	public int getAndAdvanceProcessStart(int processCount) {
		if (processCount <= 1) return 0;
		int start = Math.floorMod(processStartCursor, processCount);
		processStartCursor = (start + 1) % processCount;
		return start;
	}

	// ===== 入口缓存刷新（由 processPbRecipesAndUpdate 调用）=====
	/** 刷新流体槽满载状态缓存（入口调用一次，替代原 tryProcessPbRecipe 内的入口刷新；flush 后的刷新保留，finally 块刷新已移除以精简高频路径） */
	public void refreshFluidTankFullCache(PbRecipeContext context) {
		cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		Level level = context.level();
		lastFluidFullCacheTick = level == null ? Long.MIN_VALUE : level.getGameTime();
	}

	/** Refreshes the full-tank snapshot once per real game tick. */
	public void refreshFluidTankFullCacheForTick(PbRecipeContext context) {
		Level level = context.level();
		long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
		if (tick == lastFluidFullCacheTick) return;
		refreshFluidTankFullCache(context);
	}

	/** 刷新能量和操作数缓存（入口调用一次，替代原 tryProcessPbRecipe 内的每次刷新；升级变更下次入口自动失效） */
	public void refreshEnergyAndOpsCache(PbRecipeContext context) {
		cachedEnergyPerTick = MekExtrasUpgradeSemantics.energyPerTick(
				context.hasCreativeUpgrade(), context.energyContainer().getEnergyPerTick());
		cachedOperationsPerTick = context.operationsPerTick();
	}

	/**
	 * Reserves active multi-fluid output types once per real game tick (implementation moved to
	 * {@link PbRecipeProcessorStateHelper#reserveActiveFluidOutputTypes}).
	 */
	public void reserveActiveFluidOutputTypes(List<IInventorySlot> inputSlots) {
		Level level = context.level();
		long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
		if (tick == lastFluidReservationTick) return;
		lastFluidReservationTick = tick;
		clearReservedPbRecipeCache();
		if (level == null || context.fluidOutputTankCount() <= 1) return;
		// 预留阶段本身会查配方，先处理热重载，避免把旧版本结果写入同 tick 缓存。
		checkRecipeVersion();
		reservedPbRecipeCacheTick = tick;
		reservedPbRecipeCacheVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		PbRecipeProcessorStateHelper.reserveActiveFluidOutputTypes(context, inputSlots, recipeFinder,
				reservedPbInputs, reservedPbRecipes);
	}

	// ===== SMELTING配方缓存检查 =====

	/** 检查配方版本号是否变更，变更则清空所有缓存（每 gameTick 仅查询一次 RECIPE_VERSION，避免 256x 下高频 volatile 读） */
	private void checkRecipeVersion() {
		// 先读取版本号再做 gameTick 短路。热重载可能在同一个 gameTick 内完成，
		// 若先按 tick 返回，会让旧的 SMELTING/PB/能量缓存多保留一 tick。
		long currentVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		Level level = context.level();
		if (level != null) {
			long currentGameTime = level.getGameTime();
			if (currentGameTime == lastCheckedGameTick && lastRecipeVersion == currentVersion) {
				return; // 本 gameTick 已检查过
			}
			lastCheckedGameTick = currentGameTime;
		}
		if (lastRecipeVersion != currentVersion) {
			clearSmeltingCacheAll();
			recipeFinder.clearCaches();
			// 失效 ticksForBaseCache（配方重载可能伴随升级配置变化，强制下次重新计算）
			energyCache.clear();
			// 失效万象处理器的 getTicksForBase 缓存
			myriadHandler.clearCachedTicksForBase();
			// v2.0.9 修复产物锁定 bug：配方版本变更时重置所有 completer
			// 防止配方重载后 completer 持有旧配方的 pendingRecipeOutputs 引用
			for (int i = 0; i < recipeCompleters.length; i++) {
				if (!recipeCompleters[i].hasCommittedPendingOutputs()) {
					recipeCompleters[i].resetPendingRecipe();
				}
			}
			lastRecipeVersion = currentVersion;
		}
	}

	/** 清空所有进程的 SMELTING 配方缓存和 PB 配方引用（配方重载时调用） */
	public void clearSmeltingCacheAll() {
		smeltingCache.clearAll();
		Arrays.fill(cachedPbRecipes, null);
		clearReservedPbRecipeCache();
	}

	/** 判断输入是否命中本 tick 多流体预留阶段的配方快照。 */
	boolean hasReservedPbRecipe(int processIndex, ItemStack input) {
		Level level = context.level();
		long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
		return processIndex >= 0 && processIndex < reservedPbInputs.length
				&& reservedPbRecipeCacheTick == tick
				&& reservedPbRecipeCacheVersion == ProductiveBeesGenesis.RECIPE_VERSION.get()
				&& reservedPbInputs[processIndex] == input;
	}

	/** 读取已验证命中的本 tick PB 配方结果；调用方应先检查 {@link #hasReservedPbRecipe}。 */
	@Nullable
	RecipeHolder<CentrifugeRecipe> getReservedPbRecipe(int processIndex) {
		return processIndex < 0 || processIndex >= reservedPbRecipes.length
				? null : reservedPbRecipes[processIndex];
	}

	/** 清除预留阶段的输入/配方快照，避免跨 tick 或跨配方版本复用。 */
	private void clearReservedPbRecipeCache() {
		Arrays.fill(reservedPbInputs, null);
		Arrays.fill(reservedPbRecipes, null);
		reservedPbRecipeCacheTick = Long.MIN_VALUE;
		reservedPbRecipeCacheVersion = Long.MIN_VALUE;
	}

	/** 检查指定进程的输入是否有SMELTING配方（委托 SmeltingRecipeCache，带缓存优化） */
	public boolean hasSmeltingRecipe(int process, ItemStack input) {
		checkRecipeVersion();
		return smeltingCache.hasSmeltingRecipe(process, input, context::containsSmeltingInput);
	}

	/** 重置指定进程的SMELTING配方缓存（输入为空时调用） */
	public void resetSmeltingCache(int process) {
		smeltingCache.resetSmeltingCache(process);
	}

	// ===== PB配方处理主流程 =====

	/** 尝试PB离心配方处理（单进程，万象创世走特殊路径） */
	public boolean tryProcessPbRecipe(int processIndex) {
		return tryProcessPbRecipe(processIndex, null);
	}

	/** 尝试PB离心配方处理（单进程，接受外部预查找的 PB 配方） */
	public boolean tryProcessPbRecipe(int processIndex, RecipeHolder<CentrifugeRecipe> preFoundRecipe) {
		return tryProcessPbRecipe(processIndex, preFoundRecipe, Long.MAX_VALUE);
	}

	/** Processes one lane without allowing it to consume another lane's reserved energy. */
	public boolean tryProcessPbRecipe(int processIndex, RecipeHolder<CentrifugeRecipe> preFoundRecipe,
			long energyBudget) {
		try {
			return tryProcessPbRecipeInternal(processIndex, preFoundRecipe, energyBudget);
		} catch (Exception e) {
			// 捕获异常防止tick崩溃，记录错误日志并重置PB状态（节流避免刷屏，Task 15 ms 时间源）
			final Exception cause = e;
			pbErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
				ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 进程{}异常，重置PB状态"
						+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), processIndex, cause);
			});
			clearPbState(processIndex);
			return false;
		}
	}

	private boolean tryProcessPbRecipeInternal(int processIndex, RecipeHolder<CentrifugeRecipe> preFoundRecipe,
			long energyBudget) {
		try {
			Level level = context.level();
			if (level == null || level.isClientSide) return false;
			if (!context.canFunction()) return false;

			// 配方重载检测：版本号变更时清空 SMELTING 和 PB 配方缓存
			checkRecipeVersion();
			PbRecipeCompleter completer = recipeCompleters[processIndex];
			if (completer.hasCommittedPendingOutputs()
					&& !completer.flushPendingPbOutputs(processIndex)) {
				pbProcessing[processIndex] = false;
				return false;
			}

			// 能量/操作数/流体槽满载缓存由入口统一刷新（Task 1.3 CREATIVE 兜底 + Task 4 暂停语义）
			long currentGameTime = level.getGameTime();
			long availableEnergy = Math.min(context.energyContainer().getEnergy(), Math.max(0L, energyBudget));

			ItemStack input = context.inputSlot(processIndex).getStack();
			if (input.isEmpty()) {
				// 输入为空：清空PB状态并关闭激活位，避免进度箭头残留
				clearPbState(processIndex);
				context.setPbActiveState(false, processIndex);
				return false;
			}

		int virtualTicks = Math.max(1, tickMultiplier);
		tickMultiplier = 1;

		// 万象创世蜜脾/蜜脾块 — 委托给万象处理器（走特殊路径，不走PB CentrifugeRecipe）
		if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
				|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
			// Task 5: 批量倍率（加速模组 N 倍产出跳过 N-1 次重复 tick）
			// virtualTicks 一并传入万象处理器，使进度按虚拟 tick 推进（对齐标准 PB 路径的
			// 虚拟加速进度显示）。cachedOperationsPerTick 为每周期操作数（不再按倍率放大，
			// 由万象处理器内部 PbVirtualTickPlan 按虚拟 tick 完成多个周期，与 PB 原版一致）。
			return myriadHandler.tryProcessMyriadCreations(processIndex, input,
					cachedEnergyPerTick, cachedOperationsPerTick, virtualTicks, availableEnergy);
		}

			// SMELTING配方检查已在调用方完成（缓存优化），此处直接查找PB配方
		// 优先使用调用方预查找的配方（基础离心机 MekCentrifugeTickHandler 已查找过），
		// 避免重复查找并减少 inputRecipeCache 单条目缓存的竞争压力
		RecipeHolder<CentrifugeRecipe> pbRecipe = preFoundRecipe != null ? preFoundRecipe : recipeFinder.findPbRecipe(input);
		if (pbRecipe == null) {
			clearPbState(processIndex);
			context.setPbActiveState(false, processIndex);
			return false;
		}

			// 配方变更时重置进度
			if (cachedPbRecipes[processIndex] != pbRecipe) {
				cachedPbRecipes[processIndex] = pbRecipe;
				pbOperatingTicks[processIndex] = 0;
				// v2.0.9 修复产物锁定 bug：配方变更时重置 completer 的 pendingRecipeOutputs
				// 防止上一个配方的 outputs 残留，导致新蜜脾沿用旧配方产出
				recipeCompleters[processIndex].resetPendingRecipe();
			}

			CentrifugeRecipe recipeValue = pbRecipe.value();
			// 计算并存储PB配方处理时间（同步到客户端用于进度条显示）
			int processingTime = energyCache.getPbProcessingTime(recipeValue);
			pbProcessingTime[processIndex] = processingTime;
			boolean hasItemOutputs = !recipeValue.getRecipeOutputs().isEmpty();
			boolean hasFluidOutputs = PbRecipeOutputChecker.hasFluidOutput(context, recipeValue);

			// 计算每tick并行操作数（STACK升级：2^stackUpgrades，受maxOpsPerTick配置限制）
			int operationsPerTick = cachedOperationsPerTick;
			// maxOpsPerTick 配置 100-tick 缓存（避免每 tick 反射式查询，reload 期间守卫保留上次值）
			if (currentGameTime - lastMaxOpsRefreshTick >= MAX_OPS_REFRESH_INTERVAL
					&& ModConfig.SERVER != null) {
				cachedMaxOpsPerTick = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
				lastMaxOpsRefreshTick = currentGameTime;
			}
			int maxOpsPerTick = cachedMaxOpsPerTick;
			int baseOps = (maxOpsPerTick > 0 && operationsPerTick > 1)
					? Math.min(operationsPerTick, maxOpsPerTick)
					: operationsPerTick;
			// PB 原版产量升级同时提供并行：4/8/16/32，单次最多并行 64 个输入。
			// 现有 productivityModifier 仍保留产量倍率，两者语义独立。
			int productivityParallel = Math.max(1, context.productivityParallelModifier());
			// MEK/STACK 与 PB 原版并行在同一个处理周期内聚合。JDTE 的倍率代表
			// 虚拟 tick 数，由 PbVirtualTickPlan 推进进度和完成周期，不能直接乘到
			// 单次完成的并行数，否则少量输入会失去加速效果。
			int effectiveOps = SaturatingMath.saturatingToInt(
					SaturatingMath.saturatingMultiply(baseOps, productivityParallel));

			int modifier = context.productivityModifier();

		// 先尝试flush上一tick遗留的pending（输出槽可能有空间了）
		// 修复pendingInputShrink累积死锁：flush失败时清空pending（pending产物未实际插入槽位，清空不丢失物品）
		if (recipeCompleters[processIndex].hasPendingOutputs()
				|| recipeCompleters[processIndex].pendingInputShrink() > 0) {
			if (!recipeCompleters[processIndex].flushPendingPbOutputs(processIndex)) {
				if (recipeCompleters[processIndex].hasCommittedPendingOutputs()) {
					// 直输 AE 已经提交输入；保留剩余产物并阻塞本进程，直到后续成功输出。
					pbProcessing[processIndex] = false;
					pbOperatingTicks[processIndex] = processingTime;
					return false;
				}
				recipeCompleters[processIndex].resetPendingRecipe();
			}
			cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		}

		// flush可能扣除了输入，重新读取当前输入数量
		int inputCount = context.inputSlot(processIndex).getStack().getCount();

		// 输入不足时降低操作数（每次并行操作消耗1个输入，modifier只影响输出数量）
		int remainingInput = inputCount - recipeCompleters[processIndex].pendingInputShrink();
		if (remainingInput <= 0) {
			// 输入不足以完成1次操作，保留进度但不激活
			pbProcessing[processIndex] = false;
			return false;
		}
		int inputLimitedOps = remainingInput;
		effectiveOps = Math.min(effectiveOps, inputLimitedOps);
		if (effectiveOps <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}

			// 输出已经受阻时最多推进到本周期完成边界，不能把后续虚拟 tick
			// 也计入能耗或多个完成周期。零 tick CREATIVE 在此直接等待输出空间。
			boolean outputBlocked = PbRecipeOutputChecker.isOutputBlocked(context, processIndex,
					recipeValue, hasItemOutputs, hasFluidOutputs, cachedFluidTankFull);
			if (outputBlocked) {
				if (pbOperatingTicks[processIndex] >= processingTime) {
					pbOperatingTicks[processIndex] = processingTime;
					return true;
				}
				virtualTicks = Math.min(virtualTicks,
						Math.max(1, processingTime - pbOperatingTicks[processIndex]));
			}

			PbVirtualTickPlan tickPlan = PbVirtualTickPlan.create(
					pbOperatingTicks[processIndex], virtualTicks, processingTime, effectiveOps,
					remainingInput, cachedEnergyPerTick, availableEnergy);
			if (tickPlan.executedTicks() <= 0) {
				pbProcessing[processIndex] = false;
				return false;
			}

			pbProcessing[processIndex] = true;
			pbOperatingTicks[processIndex] = tickPlan.remainingProgress();
			int actualOps = tickPlan.completedOperations();
			// Task 4 TPS 优化：批量 accumulate 替代逐操作循环（O(outputs) 而非 O(actualOps)）
			if (actualOps > 0) {
				recipeCompleters[processIndex].accumulatePbRecipeOutputsBatch(
						recipeValue, processIndex, modifier, actualOps);
				if (recipeCompleters[processIndex].flushPendingPbOutputs(processIndex)) {
				} else if (recipeCompleters[processIndex].hasCommittedPendingOutputs()) {
					// 输入已因部分直输 AE 而提交，本批操作已经完成；剩余产物下 tick 重试。
					pbOperatingTicks[processIndex] = processingTime;
				} else {
					// 批量 flush 失败（输出空间不足）— 分批减半回退
					recipeCompleters[processIndex].resetPendingRecipe();
					int successfulOps = PbRecipeFlushHelper.retryBatchedFlush(
							recipeCompleters[processIndex], recipeValue, processIndex, modifier, actualOps);
					if (successfulOps < actualOps) {
						// 中途输出空间不足 — 保留进度下个 tick 重试
						pbOperatingTicks[processIndex] = processingTime;
					}
				}
			}
			// flush后更新流体槽缓存
			// Task 4: 使用复合判断,与初始化保持一致
			cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
			if (tickPlan.energyUsed() > 0L) {
				context.energyContainer().extract(tickPlan.energyUsed(), Action.EXECUTE, AutomationType.INTERNAL);
			}

			return true;
		} finally {
			// 无论正常返回还是异常，都确保本 tick 已完成的 PB 产物写入槽位
			recipeCompleters[processIndex].flushPendingPbOutputs(processIndex);
		}
	}

	/** 查找匹配输入物品的PB离心配方 — 委托给 {@link PbRecipeFinder}，保留为公共方法供外部调用方使用 */
	@Nullable
	public RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
		return recipeFinder.findPbRecipe(input);
	}

	// ===== 辅助方法 =====

	/** 失效 ticksForBaseCache（委托给 {@link PbRecipeEnergyCache#clear}） */
	public void clearTicksForBaseCache() {
		energyCache.clear();
	}

	/** Clears all per-process PB state (implementation moved to {@link PbRecipeProcessorStateHelper#clearPbState}). */
	private void clearPbState(int processIndex) {
		PbRecipeProcessorStateHelper.clearPbState(processIndex, pbProcessing, pbOperatingTicks, pbProcessingTime,
				cachedPbRecipes, recipeCompleters[processIndex],
				context);
	}

	/**
	 * Resets per-process PB progress without touching the active-state flag (implementation moved to
	 * {@link PbRecipeProcessorStateHelper#resetPbState}).
	 */
	public void resetPbState(int processIndex) {
		PbRecipeProcessorStateHelper.resetPbState(processIndex, pbProcessing, pbOperatingTicks, pbProcessingTime,
				cachedPbRecipes,
				recipeCompleters[processIndex]);
	}

	// ===== 客户端同步和持久化 =====

	/**
	 * Returns whether the given process is currently PB-processing (implementation moved to
	 * {@link PbRecipeProcessorStateHelper#isPbProcessing}).
	 */
	public boolean isPbProcessing(int process) {
		return PbRecipeProcessorStateHelper.isPbProcessing(pbProcessing, process);
	}

	/** PB progress 0.0~1.0 (implementation moved to {@link PbRecipeProcessorStateHelper#getPbScaledProgress}). */
	public double getPbScaledProgress(int i, int process) {
		return PbRecipeProcessorStateHelper.getPbScaledProgress(i, process, pbProcessingTime, syncedOperatingTicks,
			context.baseTicksRequired());
	}

	/**
	 * Syncs PB progress to the tracked arrays (implementation moved to
	 * {@link PbRecipeProcessorStateHelper#tickProgressSync}).
	 */
	public void tickProgressSync() {
		progressSyncCounter = PbRecipeProcessorStateHelper.tickProgressSync(progressSyncCounter, context.processes(),
				pbOperatingTicks,
				syncedOperatingTicks);
	}

	/**
	 * Adds PB progress DataSlot trackers (implementation moved to
	 * {@link PbRecipeProcessorStateHelper#addContainerTrackers}).
	 */
	public void addContainerTrackers(MekanismContainer container) {
		PbRecipeProcessorStateHelper.addContainerTrackers(container, context.processes(), syncedOperatingTicks, pbProcessing,
			pbProcessingTime);
	}

	/** Persists PB progress to NBT (implementation moved to {@link PbRecipeProcessorStateHelper#saveAdditional}). */
	public void saveAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
		PbRecipeProcessorStateHelper.saveAdditional(nbt, pbOperatingTicks, pbProcessing, pbProcessingTime);
		myriadHandler.saveAdditional(nbt);
		ListTag pendingList = new ListTag();
		for (int i = 0; i < recipeCompleters.length; i++) {
			CompoundTag pending = recipeCompleters[i].saveCommittedPending(provider);
			if (pending == null) continue;
			pending.putInt("process", i);
			pendingList.add(pending);
		}
		if (!pendingList.isEmpty()) nbt.put(NBT_COMMITTED_PENDING, pendingList);
		else nbt.remove(NBT_COMMITTED_PENDING);
	}

	/** Restores PB progress from NBT (implementation moved to {@link PbRecipeProcessorStateHelper#loadAdditional}). */
	public void loadAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
		PbRecipeProcessorStateHelper.loadAdditional(nbt, pbOperatingTicks, pbProcessing, pbProcessingTime);
		myriadHandler.loadAdditional(nbt);
		for (PbRecipeCompleter completer : recipeCompleters) completer.resetPendingRecipe();
		if (!nbt.contains(NBT_COMMITTED_PENDING, Tag.TAG_LIST)) return;
		ListTag pendingList = nbt.getList(NBT_COMMITTED_PENDING, Tag.TAG_COMPOUND);
		for (int i = 0; i < pendingList.size(); i++) {
			CompoundTag pending = pendingList.getCompound(i);
			int process = pending.getInt("process");
			if (process < 0 || process >= recipeCompleters.length) continue;
			recipeCompleters[process].loadCommittedPending(pending, provider);
		}
	}
}
