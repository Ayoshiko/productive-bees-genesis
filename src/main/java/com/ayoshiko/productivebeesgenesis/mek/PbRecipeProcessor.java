package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.PbVirtualTickPlan;

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
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
	 * PB配方处理器 — 主协调器，委托子组件处理配方查找/输出聚合/万象创世/能量缓存/输出检查。
	 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
	 */
public class PbRecipeProcessor {
	private static final String NBT_COMMITTED_PENDING = "productivebeesgenesis_pb_committed_pending";
	private static final String NBT_PAID_WORK = "productivebeesgenesis_pb_paid_work";

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
	/** 多流体预留阶段复用的唯一流体类型缓冲，避免同流体按进程重复查映射。 */
	private final FluidStack[] reservedFluidTypes;

	/** 配方查找器（双层缓存：inputRecipeCache 指纹TTL + pbRecipeCache LRU） */
	private final PbRecipeFinder recipeFinder;

	/** 输出聚合器数组（每进程独立，批量插入减少 listener 触发次数） */
	private final PbRecipeCompleter[] recipeCompleters;
	private final boolean[] emptyFactoryProcesses;
	/** 无法可靠定位到进程的已付产物保留整个外层原文，不能让后续条目覆盖它。 */
	private Tag invalidCommittedPending;
	private Tag invalidPaidWork;
	private final PbRecipeProcessorStateHelper.PaidWork[] paidWork;
	private final boolean[] restoredLegacyProgress;
	private final ItemStack[] legacyInputs;
	private final PbPausedWorkBuffer pausedWork;
	private final boolean[] resumePaidCycle;

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
		this.emptyFactoryProcesses = new boolean[processes];
		this.pbProcessingTime = new int[processes];
		this.paidWork = new PbRecipeProcessorStateHelper.PaidWork[processes];
		this.restoredLegacyProgress = new boolean[processes];
		this.legacyInputs = new ItemStack[processes];
		this.pausedWork = new PbPausedWorkBuffer(processes);
		this.resumePaidCycle = new boolean[processes];
		Arrays.fill(legacyInputs, ItemStack.EMPTY);
		this.smeltingCache = new SmeltingRecipeCache(processes);
		this.cachedPbRecipes = new RecipeHolder[processes];
		this.reservedPbInputs = new ItemStack[processes];
		this.reservedPbRecipes = new RecipeHolder[processes];
		this.reservedFluidTypes = new FluidStack[processes];
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
				pbOperatingTicks, pbProcessing, pbProcessingTime, cachedPbRecipes, recipeFinder, recipeCompleters);
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
				reservedPbInputs, reservedPbRecipes, reservedFluidTypes);
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
				if (!recipeCompleters[i].hasPaidPendingOutputs()) {
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

	/** 扩展资源与已付费工作独立于 PB 物品配方；不伪造一个空产物配方。 */
	public boolean acceptsResourceInput(ItemStack input) { return MekResourceCentrifuge.accepts(context, input); }
	public boolean allowsNewResourceInput(ItemStack input) {
		return context instanceof net.minecraft.world.level.block.entity.BlockEntity host
				&& com.ayoshiko.productivebeesgenesis.resource.ResourceInputAdmission.allows(host, input);
	}
	public boolean hasResourceWork(int lane) {
		return invalidCommittedPending != null || invalidPaidWork != null || paidWork[lane] != null
				|| restoredLegacyProgress[lane] || recipeCompleters[lane].hasPaidPendingOutputs()
				|| pausedWork.hasInput(context.inputSlot(lane).getStack())
				|| MekResourceCentrifuge.hasWork(context, lane);
	}

	/** 已付旧配方允许完整原输入重新入槽；只提供恢复资格，不创建新生产配方。 */
	public boolean hasRetainedInput(ItemStack input) {
		if (input.isEmpty() || invalidPaidWork != null || invalidCommittedPending != null) return false;
		for (int lane = 0; lane < paidWork.length; lane++) {
			if (paidWork[lane] != null && paidWork[lane].matches(input)) return true;
			if (restoredLegacyProgress[lane]
					&& ItemStack.isSameItemSameComponents(legacyInputs[lane], input)) return true;
		}
		return pausedWork.hasInput(input);
	}

	/** 在父类加工和自动整理前释放已换料的普通车道。 */
	public void reconcileInputWork() {
		if (context.level() == null || context.level().isClientSide || context.hasUnresolvedInput()) return;
		for (int lane = 0; lane < paidWork.length; lane++) {
			reconcileInputWork(lane, context.inputSlot(lane).getStack());
		}
	}

	/** 普通换料保管原周期后释放车道；未知付款和冻结未扣输入仍由原缓冲阻断。 */
	private void reconcileInputWork(int lane, ItemStack input) {
		if (context.level() == null || context.level().isClientSide
				|| invalidPaidWork != null || invalidCommittedPending != null
				|| recipeCompleters[lane].hasPaidPendingOutputs()
				|| MekResourceCentrifuge.hasWork(context, lane)) return;
		var active = paidWork[lane];
		boolean legacy = restoredLegacyProgress[lane];
		boolean matches = active != null ? active.matches(input)
				: legacy && ItemStack.isSameItemSameComponents(legacyInputs[lane], input);
		if ((active != null || legacy) && !matches) {
			if (legacy && !legacyInputs[lane].isEmpty()) {
				pausedWork.restoreReservation(lane, null, legacyInputs[lane], context.level().registryAccess());
			}
			if (pbOperatingTicks[lane] > 0
					&& !pausedWork.park(lane, pbOperatingTicks[lane], pbProcessingTime[lane])) return;
			releasePaidWork(lane);
			restoredLegacyProgress[lane] = false;
			legacyInputs[lane] = ItemStack.EMPTY;
			PbRecipeProcessorStateHelper.resetPbState(lane, pbProcessing, pbOperatingTicks,
					pbProcessingTime, cachedPbRecipes, recipeCompleters[lane]);
			context.productivebeesgenesis$markForSave();
		}
		if (paidWork[lane] != null || restoredLegacyProgress[lane] || input.isEmpty()) return;
		var restored = pausedWork.take(lane, input);
		if (restored == null) return;
		paidWork[lane] = restored.work;
		restoredLegacyProgress[lane] = restored.work == null;
		legacyInputs[lane] = restored.work == null ? restored.input : ItemStack.EMPTY;
		pbOperatingTicks[lane] = restored.progress;
		pbProcessingTime[lane] = restored.processingTime;
		resumePaidCycle[lane] = true;
		context.productivebeesgenesis$markForSave();
	}

	private void releasePaidWork(int lane) {
		paidWork[lane] = null;
		resumePaidCycle[lane] = false;
		pausedWork.release(lane);
	}

	public boolean hasUnresolvedWork() {
		if (invalidCommittedPending != null || invalidPaidWork != null || myriadHandler.hasUnresolvedState()) return true;
		for (PbRecipeCompleter completer : recipeCompleters) {
			if (completer.invalidPending() || completer.paymentRequest() != null) return true;
		}
		return false;
	}

	/** 检查指定进程的输入是否有SMELTING配方（委托 SmeltingRecipeCache，带缓存优化） */
	public boolean hasSmeltingRecipe(int process, ItemStack input) {
		reconcileInputWork(process, input);
		if (acceptsResourceInput(input) || hasResourceWork(process)) return false;
		checkRecipeVersion();
		return smeltingCache.hasSmeltingRecipe(process, input, context::containsSmeltingInput);
	}

	/** 重置指定进程的SMELTING配方缓存（输入为空时调用） */
	public void resetSmeltingCache(int process) {
		smeltingCache.resetSmeltingCache(process);
	}

	/** 仅放置流程可用同一旧方块物品的库存组件补全身份，普通 tick 不认领后来放入的材料。 */
	void bindLegacyInputsFromPlacedItem(ItemStack source) {
		if (invalidPaidWork != null || invalidCommittedPending != null
				|| !(context instanceof TileEntityMekanism tile)) return;
		var data = source.get(DataComponents.BLOCK_ENTITY_DATA);
		if (data == null) return;
		CompoundTag saved = data.copyTag();
		if (saved.contains(NBT_PAID_WORK)
				|| !saved.contains("productivebeesgenesis_pb_progress", Tag.TAG_INT_ARRAY)) return;
		int[] progress = saved.getIntArray("productivebeesgenesis_pb_progress");
		var savedInventory = ContainerType.ITEM.getOrEmpty(source).containers();
		var restoredInventory = tile.getInventorySlots(null);
		if (savedInventory.size() != restoredInventory.size()) return;
		boolean changed = false;
		for (int process = 0; process < restoredLegacyProgress.length && process < progress.length; process++) {
			if (!restoredLegacyProgress[process] || !legacyInputs[process].isEmpty() || paidWork[process] != null
					|| progress[process] <= 0 || progress[process] != pbOperatingTicks[process]) continue;
			IInventorySlot input = context.inputSlot(process);
			int savedIndex = restoredInventory.indexOf(input);
			if (savedIndex < 0) continue;
			ItemStack savedInput = savedInventory.get(savedIndex);
			if (savedInput.isEmpty() || !ItemStack.matches(savedInput, input.getStack())) continue;
			legacyInputs[process] = savedInput.copyWithCount(1);
			changed = true;
		}
		if (changed) context.productivebeesgenesis$markForSave();
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
				ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 进程{}异常，暂停并保留已付工作"
						+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), processIndex, cause);
			});
			pbProcessing[processIndex] = false;
			context.setPbActiveState(false, processIndex);
			return false;
		}
	}

	private boolean tryProcessPbRecipeInternal(int processIndex, RecipeHolder<CentrifugeRecipe> preFoundRecipe,
			long energyBudget) {
		Level level = context.level();
		if (level == null || level.isClientSide) return false;
		if (!context.canFunction() || context.hasUnresolvedInput()
				|| invalidCommittedPending != null || invalidPaidWork != null) return false;
		checkRecipeVersion();
		PbRecipeCompleter completer = recipeCompleters[processIndex];
		if (MekResourceCentrifuge.suspended(context, processIndex)) return false;
		if (completer.hasPaidPendingOutputs()
				&& !PbRecipeFlushHelper.flushCompleted(completer, processIndex)) {
			pbProcessing[processIndex] = false;
			return false;
		}
		if (!myriadHandler.drainPendingOutputs(processIndex)) return false;

		ItemStack input = context.inputSlot(processIndex).getStack();
		reconcileInputWork(processIndex, input);
		if (input.isEmpty()) {
			clearPbState(processIndex);
			return false;
		}
		if (MekResourceCentrifuge.hasWork(context, processIndex)) restoredLegacyProgress[processIndex] = false;
		if (restoredLegacyProgress[processIndex]
				&& !ItemStack.isSameItemSameComponents(legacyInputs[processIndex], input)) return false;
		PbRecipeProcessorStateHelper.PaidWork work = paidWork[processIndex];
		if (work != null && !work.matches(input)) {
			pbProcessing[processIndex] = false;
			context.setPbActiveState(false, processIndex);
			return false;
		}
		int virtualTicks = Math.max(1, tickMultiplier);
		tickMultiplier = 1;
		long availableEnergy = Math.min(context.energyContainer().getEnergy(), Math.max(0L, energyBudget));
		if (work == null && !restoredLegacyProgress[processIndex]) {
			var resourceResult = MekResourceCentrifuge.tick(context, processIndex, virtualTicks, energyBudget,
					(paid, required, working) -> {
						pbOperatingTicks[processIndex] = paid;
						pbProcessingTime[processIndex] = required;
					}, completer, recipeFinder);
			if (resourceResult != com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.Result.UNMANAGED) {
				boolean working = resourceResult == com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.Result.WORKING;
				pbProcessing[processIndex] = working;
				context.setPbActiveState(working, processIndex);
				return working;
			}
		}
		boolean myriad = MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
				|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		if (myriad) {
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) return false;
			if (work == null) {
				work = PbRecipeProcessorStateHelper.PaidWork.myriad(input, context.baseTicksRequired());
				if (restoredLegacyProgress[processIndex]) {
					pausedWork.restoreReservation(processIndex, work, ItemStack.EMPTY, level.registryAccess());
				} else if (!pausedWork.reserve(processIndex, work, level.registryAccess())) return false;
				paidWork[processIndex] = work;
				restoredLegacyProgress[processIndex] = false;
			}
			boolean result = myriadHandler.tryProcessMyriadCreations(processIndex, input,
					cachedEnergyPerTick, cachedOperationsPerTick, virtualTicks, availableEnergy,
					resumePaidCycle[processIndex]);
			if (pbOperatingTicks[processIndex] == 0 && !completer.paymentPending()) releasePaidWork(processIndex);
			context.productivebeesgenesis$markForSave();
			return result;
		}
		RecipeHolder<CentrifugeRecipe> pbRecipe;
		if (work == null) {
			pbRecipe = preFoundRecipe != null ? preFoundRecipe : recipeFinder.findPbRecipe(input);
			if (pbRecipe == null) {
				pbProcessing[processIndex] = false;
				return false;
			}
			int baseTicks = pbRecipe.value().getProcessingTime();
			if (baseTicks <= 0) baseTicks = context.baseTicksRequired();
			work = PbRecipeProcessorStateHelper.PaidWork.create(input, pbRecipe, baseTicks, level.registryAccess());
			if (restoredLegacyProgress[processIndex]) {
				pausedWork.restoreReservation(processIndex, work, ItemStack.EMPTY, level.registryAccess());
				paidWork[processIndex] = work;
			}
			restoredLegacyProgress[processIndex] = false;
		}
		pbRecipe = work.recipe;
		cachedPbRecipes[processIndex] = pbRecipe;
		CentrifugeRecipe recipeValue = pbRecipe.value();
		int processingTime = energyCache.getCachedTicksForBase(work.baseTicks);
		pbProcessingTime[processIndex] = processingTime;

		long currentGameTime = level.getGameTime();
		if (currentGameTime - lastMaxOpsRefreshTick >= MAX_OPS_REFRESH_INTERVAL && ModConfig.SERVER != null) {
			cachedMaxOpsPerTick = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
			lastMaxOpsRefreshTick = currentGameTime;
		}
		int effectiveOps = CentrifugeFactoryCommonLogic.parallelOperations(cachedOperationsPerTick,
				Math.max(1, context.productivityParallelModifier()), cachedMaxOpsPerTick);
		int remainingInput = input.getCount();
		effectiveOps = Math.min(effectiveOps, remainingInput);
		if (effectiveOps <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}
		boolean outputBlocked = PbRecipeOutputChecker.isOutputBlocked(context, processIndex, recipeValue,
				PbRecipeOutputChecker.hasItemOutput(context, recipeValue),
				PbRecipeOutputChecker.hasFluidOutput(context, recipeValue), cachedFluidTankFull);
		if (outputBlocked || resumePaidCycle[processIndex]) {
			virtualTicks = Math.min(virtualTicks, Math.max(1, processingTime - pbOperatingTicks[processIndex]));
		}
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(pbOperatingTicks[processIndex], virtualTicks,
				processingTime, effectiveOps, remainingInput, cachedEnergyPerTick, availableEnergy);
		if (plan.executedTicks() <= 0 && plan.completedOperations() <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}
		if (!pausedWork.reserve(processIndex, work, level.registryAccess())) {
			pbProcessing[processIndex] = false;
			return false;
		}
		paidWork[processIndex] = work;
		int completed = plan.completedOperations();
		if (completed > 0) {
			completer.accumulatePbRecipeOutputsBatch(recipeValue, processIndex, context.productivityModifier(), completed);
			completer.freezeCompletedBatch(input);
		}
		if (!completer.payForPlan(plan, () -> pbOperatingTicks[processIndex] = plan.remainingProgress())) {
			pbProcessing[processIndex] = false;
			return false;
		}
		pbProcessing[processIndex] = true;
		if (pbOperatingTicks[processIndex] == 0) releasePaidWork(processIndex);
		context.productivebeesgenesis$markForSave();
		if (completed > 0) PbRecipeFlushHelper.flushCompleted(completer, processIndex);
		cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		return true;
	}

	/**
	 * 排空「已扣除输入但尚未写出」的产物。
	 * <br/>
	 * 输入槽为空时上层会跳过 {@link #tryProcessPbRecipe}，但延迟提交（产物种类多于输出槽）
	 * 或直输 AE 回退留下的 pending 必须继续排空，否则产物会滞留在不可见缓冲里。
	 *
	 * @param processIndex 进程索引
	 * @return true 表示 pending 已全部写出
	 */
	public boolean drainCommittedPendingOutputs(int processIndex) {
		if (invalidCommittedPending != null || invalidPaidWork != null) return false;
		if (processIndex < 0 || processIndex >= recipeCompleters.length) return false;
		PbRecipeCompleter completer = recipeCompleters[processIndex];
		if (MekResourceCentrifuge.suspended(context, processIndex)) return false;
		if (!myriadHandler.drainPendingOutputs(processIndex)) return false;
		if (!completer.hasPaidPendingOutputs()) return false;
		return PbRecipeFlushHelper.flushCompleted(completer, processIndex);
	}

	/** 空进程只在进入空闲时清理一次，已提交产物仍每刻重试排空。 */
	public void updateFactoryInputState(int process, boolean empty) {
		if (!empty) {
			emptyFactoryProcesses[process] = false;
			return;
		}
		drainCommittedPendingOutputs(process);
		if (!emptyFactoryProcesses[process]) {
			resetSmeltingCache(process);
			resetPbState(process);
			context.productivebeesgenesis$onProcessDeactivated(process);
			emptyFactoryProcesses[process] = true;
		}
	}

	/** 查找匹配输入物品的PB离心配方 — 委托给 {@link PbRecipeFinder}，保留为公共方法供外部调用方使用 */
	@Nullable
	public RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
		return recipeFinder.findPbRecipe(input);
	}

	/** Returns whether this processor's host has the byproduct destruction upgrade installed. */
	boolean suppressesUselessByproducts() {
		return context.suppressesUselessByproducts();
	}

	// ===== 辅助方法 =====

	/** 失效 ticksForBaseCache（委托给 {@link PbRecipeEnergyCache#clear}） */
	public void clearTicksForBaseCache() {
		energyCache.clear();
	}

	/** Clears all per-process PB state (implementation moved to {@link PbRecipeProcessorStateHelper#clearPbState}). */
	private void clearPbState(int processIndex) {
		reconcileInputWork(processIndex, context.inputSlot(processIndex).getStack());
		if (paidWork[processIndex] != null || restoredLegacyProgress[processIndex]) {
			pbProcessing[processIndex] = false;
			context.setPbActiveState(false, processIndex);
			return;
		}
		PbRecipeProcessorStateHelper.clearPbState(processIndex, pbProcessing, pbOperatingTicks, pbProcessingTime,
				cachedPbRecipes, recipeCompleters[processIndex],
				context);
	}

	/**
	 * Resets per-process PB progress without touching the active-state flag (implementation moved to
	 * {@link PbRecipeProcessorStateHelper#resetPbState}).
	 */
	public void resetPbState(int processIndex) {
		reconcileInputWork(processIndex, context.inputSlot(processIndex).getStack());
		if (paidWork[processIndex] != null || restoredLegacyProgress[processIndex]) {
			pbProcessing[processIndex] = false;
			return;
		}
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

	public record PaymentStatus(int lane, java.util.UUID request, long energy) {}

	public java.util.List<PaymentStatus> paymentStatus() {
		var result = new java.util.ArrayList<PaymentStatus>();
		for (int lane = 0; lane < recipeCompleters.length; lane++) {
			var completer = recipeCompleters[lane];
			if (completer.paymentRequest() != null) {
				result.add(new PaymentStatus(lane, completer.paymentRequest(), completer.paymentEnergy()));
			}
		}
		return java.util.List.copyOf(result);
	}

	public void confirmPaymentSettled(java.util.UUID request) {
		for (int lane = 0; lane < recipeCompleters.length; lane++) {
			var completer = recipeCompleters[lane];
			if (request.equals(completer.paymentRequest())) {
				pbOperatingTicks[lane] = completer.confirmPayment(request);
				if (pbOperatingTicks[lane] == 0) releasePaidWork(lane);
				context.productivebeesgenesis$markForSave();
				return;
			}
		}
		throw new IllegalStateException("Stale centrifuge payment request");
	}

	/** Persists PB progress to NBT (implementation moved to {@link PbRecipeProcessorStateHelper#saveAdditional}). */
	public void saveAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
		PbRecipeProcessorStateHelper.saveAdditional(nbt, pbOperatingTicks, pbProcessing, pbProcessingTime);
		myriadHandler.saveAdditional(nbt);
		savePaidWork(nbt, provider);
		if (invalidCommittedPending != null) {
			nbt.put(NBT_COMMITTED_PENDING, invalidCommittedPending.copy());
			return;
		}
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
	/** 旧独立周期没有完整费用快照，非零进度须在原机结清后才可迁入。 */
	public boolean hasOwnershipProgress() {
		for (int ticks : pbOperatingTicks) if (ticks != 0) return true;
		return false;
	}
	public boolean ownershipStateReadable() {
		return !hasUnresolvedWork();
	}

	/** 已生成余量可以封存；清空源时必须连同这些余量一并撤销。 */
	public boolean hasOwnershipWork() {
		if (!ownershipStateReadable()) return true;
		for (int i = 0; i < recipeCompleters.length; i++) {
			if (pbOperatingTicks[i] != 0 || pbProcessing[i] || recipeCompleters[i].hasCommittedPendingOutputs()) return true;
		}
		var residual = new CompoundTag(); myriadHandler.saveAdditional(residual);
		for (String key : residual.getAllKeys()) for (long amount : residual.getLongArray(key)) if (amount != 0) return true;
		return false;
	}

	/** Restores PB progress from NBT (implementation moved to {@link PbRecipeProcessorStateHelper#loadAdditional}). */
	public void loadAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
		java.util.Arrays.fill(emptyFactoryProcesses, false);
		PbRecipeProcessorStateHelper.loadAdditional(nbt, pbOperatingTicks, pbProcessing, pbProcessingTime);
		myriadHandler.loadAdditional(nbt);
		loadPaidWork(nbt, provider);
		for (PbRecipeCompleter completer : recipeCompleters) completer.loadCommittedPending(new CompoundTag(), provider);
		invalidCommittedPending = null;
		Tag raw = nbt.get(NBT_COMMITTED_PENDING);
		if (raw == null) return;
		try {
			if (!(raw instanceof ListTag pendingList)
					|| !pendingList.isEmpty() && pendingList.getElementType() != Tag.TAG_COMPOUND)
				throw new IllegalArgumentException("Invalid committed output list");
			var seen = new java.util.BitSet(recipeCompleters.length);
			for (Tag value : pendingList) {
				var pending = (CompoundTag) value;
				if (!pending.contains("process", Tag.TAG_INT)) throw new IllegalArgumentException("Invalid pending process type");
				int process = pending.getInt("process");
				if (process < 0 || process >= recipeCompleters.length || seen.get(process))
					throw new IllegalArgumentException("Invalid or duplicate pending process");
				seen.set(process);
			}
			for (Tag value : pendingList) {
				var pending = (CompoundTag) value;
				int process = pending.getInt("process");
				recipeCompleters[process].loadCommittedPending(pending, provider);
				if (!nbt.contains(NBT_PAID_WORK) && pbProcessingTime[process] > 0
						&& pbOperatingTicks[process] >= pbProcessingTime[process]) {
					pbOperatingTicks[process] = 0;
					restoredLegacyProgress[process] = false;
				}
			}
		} catch (RuntimeException invalid) {
			invalidCommittedPending = raw.copy();
		}
	}

	private void savePaidWork(CompoundTag nbt, HolderLookup.Provider provider) {
		if (invalidPaidWork != null) {
			nbt.put(NBT_PAID_WORK, invalidPaidWork.copy());
			return;
		}
		CompoundTag root = new CompoundTag();
		root.putInt("version", 2);
		ListTag entries = new ListTag();
		for (int process = 0; process < paidWork.length; process++) {
			CompoundTag entry;
			if (paidWork[process] != null) {
				entry = paidWork[process].save(provider);
			} else if (restoredLegacyProgress[process]) {
				entry = new CompoundTag();
				entry.putBoolean("legacy", true);
				if (!legacyInputs[process].isEmpty()) entry.put("input", legacyInputs[process].save(provider));
			} else continue;
			entry.putInt("process", process);
			if (resumePaidCycle[process]) entry.putBoolean("resume_cycle", true);
			entries.add(entry);
		}
		root.put("entries", entries);
		root.put("paused", pausedWork.save());
		nbt.put(NBT_PAID_WORK, root);
	}

	private void loadPaidWork(CompoundTag nbt, HolderLookup.Provider provider) {
		Arrays.fill(paidWork, null);
		Arrays.fill(restoredLegacyProgress, false);
		Arrays.fill(legacyInputs, ItemStack.EMPTY);
		Arrays.fill(resumePaidCycle, false);
		pausedWork.clear();
		invalidPaidWork = null;
		Tag raw = nbt.get(NBT_PAID_WORK);
		if (raw == null) {
			for (int process = 0; process < paidWork.length; process++) {
				if (pbOperatingTicks[process] <= 0) continue;
				restoredLegacyProgress[process] = true;
				ItemStack input = context.inputSlot(process).getStack();
				if (!input.isEmpty()) {
					legacyInputs[process] = input.copyWithCount(1);
					pausedWork.restoreReservation(process, null, legacyInputs[process], provider);
				}
			}
			return;
		}
		try {
			if (!(raw instanceof CompoundTag root)
					|| !java.util.Set.of("version", "entries", "paused").containsAll(root.getAllKeys())
					|| !root.contains("version", Tag.TAG_INT)
					|| root.getInt("version") != 1 && root.getInt("version") != 2
					|| root.getInt("version") == 1 && root.contains("paused")
					|| root.getInt("version") == 2 && !root.contains("paused", Tag.TAG_LIST)
					|| !(root.get("entries") instanceof ListTag entries)
					|| !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
				throw new IllegalArgumentException("Invalid paid work collection");
			pausedWork.load(root.get("paused"), provider);
			var seen = new java.util.BitSet(paidWork.length);
			for (Tag value : entries) {
				CompoundTag entry = ((CompoundTag) value).copy();
				if (!entry.contains("process", Tag.TAG_INT)) throw new IllegalArgumentException("Missing work process");
				int process = entry.getInt("process");
				if (process < 0 || process >= paidWork.length || seen.get(process))
					throw new IllegalArgumentException("Invalid or duplicate work process");
				seen.set(process);
				if (entry.contains("resume_cycle")) {
					if (root.getInt("version") != 2 || !entry.contains("resume_cycle", Tag.TAG_BYTE)
							|| !entry.getBoolean("resume_cycle")) {
						throw new IllegalArgumentException("Invalid resumed paid cycle");
					}
					resumePaidCycle[process] = true;
					entry.remove("resume_cycle");
				}
				if (entry.contains("legacy")) {
					if (!java.util.Set.of("legacy", "process", "input").containsAll(entry.getAllKeys())
							|| !entry.contains("legacy", Tag.TAG_BYTE) || !entry.getBoolean("legacy"))
						throw new IllegalArgumentException("Invalid legacy work");
					restoredLegacyProgress[process] = true;
					if (entry.contains("input")) {
						if (!entry.contains("input", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid legacy input");
						legacyInputs[process] = ItemStack.parse(provider, entry.getCompound("input")).orElseThrow();
						if (legacyInputs[process].getCount() != 1) throw new IllegalArgumentException("Invalid legacy input count");
					}
				} else paidWork[process] = PbRecipeProcessorStateHelper.PaidWork.load(entry, provider);
				pausedWork.restoreReservation(process, paidWork[process], legacyInputs[process], provider);
			}
		} catch (RuntimeException invalid) {
			invalidPaidWork = raw.copy();
		}
	}

}
