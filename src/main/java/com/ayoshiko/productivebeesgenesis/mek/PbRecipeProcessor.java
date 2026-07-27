package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.container.MekanismContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * PB配方处理器 — 主协调器，委托子组件处理配方查找/输出聚合/万象创世/能量缓存/输出检查。
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
public class PbRecipeProcessor {

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

	/** 配方查找器（双层缓存：inputRecipeCache + pbRecipeCache） */
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

	/** Task 23: 进度同步节流间隔（tick） — 高进程时每 5 tick 同步一次降低网络包频率 80% */
	private static final int PROGRESS_SYNC_INTERVAL = 5;

	/** Task 23: 进度同步计数器（每 tick 递增，% 5 == 0 时同步） */
	private int progressSyncCounter = 0;

	/** 进程异常日志冷却器（每处理器实例独立，tick 模式） */
	private final LogThrottle pbErrorThrottle = new LogThrottle();

	/** 当前 tick 的批量倍率（Tick 加速检测器设置,默认 1 表示无加速） — 由调用方在 tick 入口设置,使用后自动重置 */
	private int tickMultiplier = 1;

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

	// ===== 入口缓存刷新（由 processPbRecipesAndUpdate 调用）=====
	/** 刷新流体槽满载状态缓存（入口调用一次，替代原 tryProcessPbRecipe 内的入口刷新；flush 后的刷新保留，finally 块刷新已移除以精简高频路径） */
	public void refreshFluidTankFullCache(PbRecipeContext context) {
		cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
	}
	/** 刷新能量和操作数缓存（入口调用一次，替代原 tryProcessPbRecipe 内的每次刷新；升级变更下次入口自动失效） */
	public void refreshEnergyAndOpsCache(PbRecipeContext context) {
		cachedEnergyPerTick = context.hasCreativeUpgrade() ? 0L : context.energyContainer().getEnergyPerTick();
		cachedOperationsPerTick = context.operationsPerTick();
	}

	// ===== SMELTING配方缓存检查 =====

	/** 检查配方版本号是否变更，变更则清空所有缓存（每 gameTick 仅查询一次 RECIPE_VERSION，避免 256x 下高频 volatile 读） */
	private void checkRecipeVersion() {
		Level level = context.level();
		if (level != null) {
			long currentGameTime = level.getGameTime();
			if (currentGameTime == lastCheckedGameTick) {
				return; // 本 gameTick 已检查过
			}
			lastCheckedGameTick = currentGameTime;
		}
		long currentVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (lastRecipeVersion != currentVersion) {
			clearSmeltingCacheAll();
			recipeFinder.clearCaches();
			// 失效 ticksForBaseCache（配方重载可能伴随升级配置变化，强制下次重新计算）
			energyCache.clear();
			// 失效万象处理器的 getTicksForBase 缓存
			myriadHandler.clearCachedTicksForBase();
			lastRecipeVersion = currentVersion;
		}
	}

	/** 清空所有进程的 SMELTING 配方缓存和 PB 配方引用（配方重载时调用） */
	public void clearSmeltingCacheAll() {
		smeltingCache.clearAll();
		Arrays.fill(cachedPbRecipes, null);
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
		try {
			return tryProcessPbRecipeInternal(processIndex, preFoundRecipe);
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

	private boolean tryProcessPbRecipeInternal(int processIndex, RecipeHolder<CentrifugeRecipe> preFoundRecipe) {
		try {
			Level level = context.level();
			if (level == null || level.isClientSide) return false;
			if (!context.canFunction()) return false;

			// 配方重载检测：版本号变更时清空 SMELTING 和 PB 配方缓存
			checkRecipeVersion();

			// 能量/操作数/流体槽满载缓存由入口统一刷新（Task 1.3 CREATIVE 兜底 + Task 4 暂停语义）
			long currentGameTime = level.getGameTime();
			long availableEnergy = context.energyContainer().getEnergy();

			ItemStack input = context.inputSlot(processIndex).getStack();
			if (input.isEmpty()) {
				// 输入为空：清空PB状态并关闭激活位，避免进度箭头残留
				clearPbState(processIndex);
				context.setPbActiveState(false, processIndex);
				return false;
			}

		// 万象创世蜜脾/蜜脾块 — 委托给万象处理器（走特殊路径，不走PB CentrifugeRecipe）
		if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
				|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
			// Task 5: 批量倍率（加速模组 N 倍产出跳过 N-1 次重复 tick）
			int myriadOps = cachedOperationsPerTick * tickMultiplier;
			tickMultiplier = 1; // 重置
			return myriadHandler.tryProcessMyriadCreations(processIndex, input,
					cachedEnergyPerTick, myriadOps);
		}

			// SMELTING配方检查已在调用方完成（缓存优化），此处直接查找PB配方
			RecipeHolder<CentrifugeRecipe> pbRecipe = recipeFinder.findPbRecipe(input);
			if (pbRecipe == null) {
				clearPbState(processIndex);
				context.setPbActiveState(false, processIndex);
				return false;
			}

			// 配方变更时重置进度
			if (cachedPbRecipes[processIndex] != pbRecipe) {
				cachedPbRecipes[processIndex] = pbRecipe;
				pbOperatingTicks[processIndex] = 0;
			}

			CentrifugeRecipe recipeValue = pbRecipe.value();
			// 计算并存储PB配方处理时间（同步到客户端用于进度条显示）
			int processingTime = energyCache.getPbProcessingTime(recipeValue);
			pbProcessingTime[processIndex] = processingTime;
			boolean hasItemOutputs = !recipeValue.getRecipeOutputs().isEmpty();
			boolean hasFluidOutputs = PbRecipeOutputChecker.hasFluidOutput(recipeValue);

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
			// Task 4: 批量倍率（baseOps 已受 maxOpsPerTick 限制，effectiveOps 还受能量/输入/输出约束）
			int effectiveOps = baseOps * tickMultiplier;
			tickMultiplier = 1; // 重置

			int modifier = context.productivityModifier();

		// 先尝试flush上一tick遗留的pending（输出槽可能有空间了）
		// 修复pendingInputShrink累积死锁：flush失败时清空pending（pending产物未实际插入槽位，清空不丢失物品）
		if (recipeCompleters[processIndex].pendingItemCount() > 0 || recipeCompleters[processIndex].pendingInputShrink() > 0) {
			if (!recipeCompleters[processIndex].flushPendingPbOutputs(processIndex)) {
				recipeCompleters[processIndex].resetPendingRecipe();
			}
			cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		}

		// flush可能扣除了输入，重新读取当前输入数量
		int inputCount = context.inputSlot(processIndex).getStack().getCount();

		// 能量不足时降低操作数（参考MEK原版calculateOperationsThisTick）
		if (cachedEnergyPerTick > 0) {
			int energyLimitedOps = (int) Math.min(effectiveOps, availableEnergy / cachedEnergyPerTick);
			if (energyLimitedOps <= 0) {
				pbProcessing[processIndex] = false; // 能量不足，保留进度但不激活
				return false;
			}
			effectiveOps = energyLimitedOps;
		}

		// 输入不足时降低操作数（每次操作只消耗1个输入，modifier只影响输出数量）
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

			// 输出受阻且进度已满时不消耗能量（参考MEK原版NOT_ENOUGH_OUTPUT_SPACE）
			if (PbRecipeOutputChecker.isOutputBlocked(context, processIndex, recipeValue, hasItemOutputs, hasFluidOutputs, cachedFluidTankFull)
					&& pbOperatingTicks[processIndex] >= processingTime) {
				pbOperatingTicks[processIndex] = processingTime;
				return true;
			}

			// 激活处理
			pbProcessing[processIndex] = true;

			// 并行处理：每tick进度只+1，完成时逐次操作+逐次flush
			pbOperatingTicks[processIndex]++;
			int opsRun = 0;

			if (pbOperatingTicks[processIndex] >= processingTime) {
			// 进度满，完成effectiveOps次操作
			// Task 4 TPS 优化：批量 accumulate 替代 65536 次循环（O(outputs) 而非 O(actualOps)）
			pbOperatingTicks[processIndex] = 0;
			// 先确定实际可完成的 ops 数（受输入限制）
			int currentInputCount = context.inputSlot(processIndex).getStack().getCount();
			int currentPending = recipeCompleters[processIndex].pendingInputShrink();
			int actualOps = Math.min(effectiveOps, Math.max(0, currentInputCount - currentPending));
		if (actualOps > 0) {
				recipeCompleters[processIndex].accumulatePbRecipeOutputsBatch(
						recipeValue, processIndex, modifier, actualOps);
				if (recipeCompleters[processIndex].flushPendingPbOutputs(processIndex)) {
					opsRun += actualOps;
				} else {
					// 批量 flush 失败（输出空间不足）— 分批减半回退
					recipeCompleters[processIndex].resetPendingRecipe();
					int successfulOps = retryBatchedFlush(recipeValue, processIndex, modifier, actualOps);
					opsRun += successfulOps;
					if (successfulOps < actualOps) {
						// 中途输出空间不足 — 保留进度下个 tick 重试
						pbOperatingTicks[processIndex] = processingTime;
					}
				}
			}
			// flush后更新流体槽缓存
			// Task 4: 使用复合判断,与初始化保持一致
			cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		} else {
			// 进度未满，本tick不完成操作，但仍消耗能量（保持并行语义）
			opsRun = effectiveOps;
		}

			// 批量扣除能量 — 每tick消耗opsRun次操作的能量
			if (opsRun > 0 && cachedEnergyPerTick > 0) {
				context.energyContainer().extract((long) opsRun * cachedEnergyPerTick, Action.EXECUTE, AutomationType.INTERNAL);
			}

			return true;
		} finally {
			// 无论正常返回还是异常，都确保本 tick 已完成的 PB 产物写入槽位
			recipeCompleters[processIndex].flushPendingPbOutputs(processIndex);
		}
	}

	/**
	 * 分批减半回退 — O(log N) 次减半尝试 + 批量执行（替代 N 次逐次重试）。成功：batchSize 不变；失败：batchSize /= 2；batchSize=1 失败时跳出。
	 */
	private int retryBatchedFlush(CentrifugeRecipe recipe, int processIndex, int modifier, int totalOps) {
		int opsSuccessfullyRun = 0;
		int remaining = totalOps;
		int batchSize = totalOps;
		while (remaining > 0) {
			if (batchSize <= 0) batchSize = 1;
			int trySize = Math.min(batchSize, remaining);
			recipeCompleters[processIndex].resetPendingRecipe();
			if (trySize == 1) {
				recipeCompleters[processIndex].accumulatePbRecipeOutputs(recipe, processIndex, modifier);
			} else {
				recipeCompleters[processIndex].accumulatePbRecipeOutputsBatch(recipe, processIndex, modifier, trySize);
			}
			if (recipeCompleters[processIndex].flushPendingPbOutputs(processIndex)) {
				opsSuccessfullyRun += trySize;
				remaining -= trySize;
			} else {
				recipeCompleters[processIndex].resetPendingRecipe();
				if (batchSize <= 1) break; // 连单个 ops 都无法 flush — 输出槽完全满
				batchSize /= 2;
			}
		}
		return opsSuccessfullyRun;
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

	/** 清除指定进程的PB处理状态（同时关闭该进程的激活位，避免进度箭头残留） */
	private void clearPbState(int processIndex) {
		if (pbProcessing[processIndex]) {
			pbProcessing[processIndex] = false;
			pbOperatingTicks[processIndex] = 0;
			pbProcessingTime[processIndex] = 0;
			cachedPbRecipes[processIndex] = null;
		}
		// 无论 pbProcessing 状态如何，都关闭该进程的激活位，防止进度箭头残留
		context.setPbActiveState(false, processIndex);
	}

	/** 强制重置指定进程的 PB 处理状态（不调用 setPbActiveState，供基础机器 SMELTING 检查命中时使用） */
	public void resetPbState(int processIndex) {
		pbProcessing[processIndex] = false;
		pbOperatingTicks[processIndex] = 0;
		pbProcessingTime[processIndex] = 0;
		cachedPbRecipes[processIndex] = null;
	}

	// ===== 客户端同步和持久化 =====

	/** 检查指定进程是否正在处理PB配方 */
	public boolean isPbProcessing(int process) {
		return pbProcessing[process];
	}

	/**
	 * 获取PB处理的缩放进度（0.0~1.0） — 读 syncedOperatingTicks（与 trackArray 监控一致）。
	 * 修复 #9：processingTime <= 0 守卫，避免除零（CREATIVE 升级下 baseTicksRequired 可能为 0）。
	 */
	public double getPbScaledProgress(int i, int process) {
		int processingTime = pbProcessingTime[process] > 0 ? pbProcessingTime[process] : context.baseTicksRequired();
		if (processingTime <= 0) return 0.0;
		return Math.min(1.0, (double) syncedOperatingTicks[process] * i / processingTime);
	}

	/**
	 * Task 23: 每 tick 调用，高进程时节流进度同步。
	 * 高进程（≥9）时每 5 tick 将 pbOperatingTicks 复制到 syncedOperatingTicks，
	 * trackArray 检测到变化才发网络包，频率降低 80%。低进程（<9）时每 tick 同步。
	 */
	public void tickProgressSync() {
		if (context.processes() < 9 || ++progressSyncCounter % PROGRESS_SYNC_INTERVAL == 0) {
			System.arraycopy(pbOperatingTicks, 0, syncedOperatingTicks, 0, pbOperatingTicks.length);
		}
	}

	/**
	 * 同步PB进度到客户端。syncedOperatingTicks/pbProcessing/pbProcessingTime 同步给客户端用于GUI显示。
	 */
	public void addContainerTrackers(MekanismContainer container) {
		// DataSlot 越界守卫：数组长度与 processes 不一致时跳过注册（防御性检查）
		if (syncedOperatingTicks.length != context.processes()) {
			return;
		}
		container.trackArray(syncedOperatingTicks);
		container.trackArray(pbProcessing);
		container.trackArray(pbProcessingTime);
	}

	/**
	 * 持久化PB配方处理状态（修复 #10：pbProcessing/pbProcessingTime 同步持久化避免重启后 GUI 状态不一致）。
	 * pbProcessing 以 byte 数组持久化（boolean 数组 NBT 支持不一致）。
	 */
	public void saveAdditional(CompoundTag nbt) {
		nbt.putIntArray("productivebeesgenesis_pb_progress", pbOperatingTicks);
		byte[] processingBytes = new byte[pbProcessing.length];
		for (int i = 0; i < pbProcessing.length; i++) processingBytes[i] = (byte) (pbProcessing[i] ? 1 : 0);
		nbt.putByteArray("productivebeesgenesis_pb_processing", processingBytes);
		nbt.putIntArray("productivebeesgenesis_pb_processing_time", pbProcessingTime);
	}

	/** 加载PB配方处理状态（兼容旧存档仅含 pb_progress 的情形） */
	public void loadAdditional(CompoundTag nbt) {
		if (nbt.contains("productivebeesgenesis_pb_progress", Tag.TAG_INT_ARRAY)) {
			int[] saved = nbt.getIntArray("productivebeesgenesis_pb_progress");
			System.arraycopy(saved, 0, pbOperatingTicks, 0, Math.min(pbOperatingTicks.length, saved.length));
		}
		if (nbt.contains("productivebeesgenesis_pb_processing", Tag.TAG_BYTE_ARRAY)) {
			byte[] saved = nbt.getByteArray("productivebeesgenesis_pb_processing");
			int len = Math.min(pbProcessing.length, saved.length);
			for (int i = 0; i < len; i++) pbProcessing[i] = saved[i] != 0;
		}
		if (nbt.contains("productivebeesgenesis_pb_processing_time", Tag.TAG_INT_ARRAY)) {
			int[] saved = nbt.getIntArray("productivebeesgenesis_pb_processing_time");
			System.arraycopy(saved, 0, pbProcessingTime, 0, Math.min(pbProcessingTime.length, saved.length));
		}
	}
}
