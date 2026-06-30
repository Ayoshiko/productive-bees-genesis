package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import com.ayoshiko.productivebeesgenesis.util.PerformanceMonitor;
import com.ayoshiko.productivebeesgenesis.util.RecipeCacheManager;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.util.InventoryUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * PB配方处理器 — 封装所有PB离心配方的处理逻辑
 * <br/>
 * 从三个Factory TileEntity中抽取的重复逻辑（约400行/文件），统一委托到此辅助类。
 * 遵循单一职责原则：此类只负责PB配方处理，不涉及槽位布局、侧面配置、SMELTING管线等。
 * <p>
 * 处理路径：
 * <ul>
 *   <li>PB CentrifugeRecipe：概率多物品输出+流体输出，独立于Mekanism CachedRecipe管线</li>
 *   <li>万象创世蜜脾/蜜脾块：转化为随机蜜脾/蜜脾块（特殊处理路径）</li>
 * </ul>
 * SMELTING配方优先于PB配方（同一输入若有SMELTING配方则走SMELTING路径，由调用方判断）。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁（参考{@link RecipeCacheManager}的设计）。
 */
public class PbRecipeProcessor {

	/** PB离心配方类型 */
	private static final RecipeType<CentrifugeRecipe> CENTRIFUGE_RECIPE_TYPE = ModRecipeTypes.CENTRIFUGE_TYPE.get();

	/** 配方缓存最大条目数 */
	private static final int MAX_RECIPE_CACHE_SIZE = 256;

	/** PB配方处理上下文 — 由Factory TileEntity提供 */
	private final PbRecipeContext context;

	/** 日志前缀（区分原版/ME/EME工厂） */
	private final String logPrefix;

	/** PB配方处理进度（tick） — 每进程独立 */
	private final int[] pbOperatingTicks;

	/** PB配方是否正在处理 — 每进程独立 */
	private final boolean[] pbProcessing;

	/** PB配方处理总时间（tick） — 每进程独立，同步到客户端用于进度条显示 */
	private final int[] pbProcessingTime;

	/** PB离心配方缓存 — 每进程独立 */
	@Nullable
	private final RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes;

	/** PB离心配方查找缓存（实例级LRU，避免每tick全量遍历） */
	private final RecipeCacheManager<RecipeHolder<CentrifugeRecipe>> pbRecipeCache;

	/**
	 * PB配方查找的短期缓存（TTL 20 tick + identity 短路）
	 * <br/>
	 * 作为 {@link #pbRecipeCache} 的上层缓存：tryProcessPbRecipe 每 tick 调用 findPbRecipe 时，
	 * 若输入引用未变（Mekanism 槽位缓存）则 identity 短路直接返回，跳过 pbRecipeCache 的
	 * {@link ItemStack#hashItemAndComponents} 计算。配方重载时由 {@link #checkRecipeVersion} 清空。
	 */
	private final InputValidationCache inputRecipeCache = new InputValidationCache();

	/** 每进程的上次检查输入物品（用于缓存SMELTING配方检查结果） */
	private final ItemStack[] lastCheckedInputs;

	/** 每进程的上次输入是否有SMELTING配方（缓存结果） */
	private final boolean[] lastHasSmeltingRecipes;

	/**
	 * 上次缓存时的配方版本号 — 用于检测配方重载（/reload）
	 * <br/>
	 * 与 {@link ProductiveBeesGenesis#recipeVersion} 比较，不一致时清空所有 SMELTING 和 PB 配方缓存。
	 * 使用 volatile 保证可见性：主线程（重载事件）写入 recipeVersion 后，方块实体线程能立即读到新值。
	 */
	private volatile long lastRecipeVersion = -1L;

	/** 可复用的输出槽列表（避免每次完成配方都创建新ArrayList） */
	private final List<IInventorySlot> reusableOutputSlots = new ArrayList<>(3);

	// ===== 输出聚合（减少高倍加速下 insertItem/onContentsChanged 调用次数） =====
	/** 本 tick 尚未插入的 PB 配方输出（按 ItemStack key 累加数量） */
	private final Map<ItemStack, Integer> pendingOutputs = new LinkedHashMap<>(4);
	/** 当前聚合输出对应的 PB 配方（用于 flush 时按原顺序插入） */
	@Nullable
	private CentrifugeRecipe pendingRecipe;
	/** 当前聚合输出对应的 PB 配方输出表（缓存避免每次重复创建 LinkedHashMap） */
	@Nullable
	private Map<ItemStack, ChancedOutput> pendingRecipeOutputs;
	/** 本 tick 尚未插入的流体输出模板（amount=0） */
	@Nullable
	private FluidStack pendingFluidTemplate;
	/** 本 tick 尚未插入的流体输出总量 */
	private int pendingFluidAmount;
	/** 本 tick 尚未扣除的输入数量（= 已完成配方数 × 生产力倍率） */
	private int pendingInputShrink;
	/** 本 tick 已聚合的物品总数量，用于触发提前 flush */
	private int pendingItemCount;
	/** 触发 flush 的物品数量阈值（约一个栈），防止输出槽溢出 */
	private static final int PENDING_FLUSH_THRESHOLD = 64;

	/** 缓存的每tick能量消耗（每次进入处理方法时刷新，避免循环内重复调用可能涉及Math.pow的计算） */
	private long cachedEnergyPerTick;

	/** 缓存的每tick操作数（每次进入处理方法时刷新，升级变更会在下次进入方法时自动反映） */
	private int cachedOperationsPerTick;

	/**
	 * 本 tick 内 getTicksForBase(baseTime) 的结果缓存 — 用于PB配方处理时间计算
	 * <br/>
	 * 不同配方的 baseTime 不同，但同一 tick 内升级组件不变，计算结果只与 baseTime 有关。
	 * 使用按 tick 清空的 Map 缓存，避免每进程每 tick 都重复执行升级遍历与 Math.pow。
	 */
	private final ConcurrentHashMap<Integer, Integer> ticksForBaseCache = new ConcurrentHashMap<>(8);

	/** 当前 ticksForBaseCache 对应的游戏刻，变化时清空缓存 */
	private long ticksForBaseCacheAt = -1L;

	/**
	 * 缓存的 getTicksForBase(baseTicksRequired) 结果 — 用于万象创世处理时间计算
	 * <br/>
	 * getTicksForBase 内部涉及升级组件遍历与 Math.pow 计算，在升级未变更时结果稳定，
	 * 通过时间窗口缓存避免每 tick 每进程重复计算。升级变更后最多 20 tick（1秒）内自动反映新值。
	 */
	private volatile int cachedTicksForBase = -1;

	/** 上次计算 cachedTicksForBase 时的游戏刻（-1 表示未计算） */
	private volatile long cachedTicksForBaseAt = -1L;

	/** getTicksForBase 缓存失效间隔（tick） — 升级变更后最多 1 秒内反映新值 */
	private static final int TICKS_CACHE_INTERVAL = 20;

	/** 万象创世日志冷却间隔（tick） — 避免输出阻塞时 WARN 刷屏 */
	private static final int MYRIAD_LOG_COOLDOWN = 100;

	/** 每进程上次打印"万象产物无法插入"日志的游戏刻 */
	private final long[] lastMyriadFullLogTick;

	/** 每进程上次打印"万象类型缓存为空"日志的游戏刻 */
	private final long[] lastMyriadEmptyCacheLogTick;

	/**
	 * @param context   PB配方处理上下文（由Factory TileEntity提供）
	 * @param logPrefix 日志前缀（如"工厂离心机"、"ME工厂离心机"、"EME工厂离心机"）
	 */
	@SuppressWarnings("unchecked")
	public PbRecipeProcessor(PbRecipeContext context, String logPrefix) {
		this.context = context;
		this.logPrefix = logPrefix;
		int processes = context.processes();
		this.pbOperatingTicks = new int[processes];
		this.pbProcessing = new boolean[processes];
		this.pbProcessingTime = new int[processes];
		this.lastCheckedInputs = new ItemStack[processes];
		this.lastHasSmeltingRecipes = new boolean[processes];
		Arrays.fill(lastCheckedInputs, ItemStack.EMPTY);
		this.cachedPbRecipes = new RecipeHolder[processes];
		this.pbRecipeCache = new RecipeCacheManager<>(MAX_RECIPE_CACHE_SIZE);
		this.lastMyriadFullLogTick = new long[processes];
		this.lastMyriadEmptyCacheLogTick = new long[processes];
		Arrays.fill(lastMyriadFullLogTick, -1L);
		Arrays.fill(lastMyriadEmptyCacheLogTick, -1L);
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

	// ===== SMELTING配方缓存检查 =====

	/**
	 * 检查配方版本号是否变更，变更则清空所有 SMELTING 和 PB 配方缓存
	 * <br/>
	 * 在每次进入 hasSmeltingRecipe 和 tryProcessPbRecipe 时调用，确保配方重载后
	 * （/reload、数据包变更）立即失效旧缓存，避免使用过期的配方检查结果。
	 * <p>
	 * 线程安全：recipeVersion 是 volatile，读取是原子操作；清空操作在方块实体线程执行，无需同步锁。
	 */
	private void checkRecipeVersion() {
		if (lastRecipeVersion != ProductiveBeesGenesis.recipeVersion) {
			clearSmeltingCacheAll();
			// 清空每进程的当前PB配方引用，防止使用过期配方（配方重载后旧引用可能已失效）
			Arrays.fill(cachedPbRecipes, null);
			pbRecipeCache.clear();
			// 清空 inputRecipeCache（上层短期缓存，配方重载后旧结果失效）
			inputRecipeCache.clear();
			// 失效 getTicksForBase 缓存（配方重载可能伴随升级配置变化，强制下次重新计算）
			cachedTicksForBase = -1;
			lastRecipeVersion = ProductiveBeesGenesis.recipeVersion;
		}
	}

	/**
	 * 清空所有进程的 SMELTING 配方缓存
	 * <br/>
	 * 在配方重载（/reload）时调用，确保下次 hasSmeltingRecipe 调用会重新查询配方。
	 * 同时清空 PB 配方缓存（pbRecipeCache）和每进程的当前PB配方引用（cachedPbRecipes），
	 * 因为 PB CentrifugeRecipe 也可能变更。
	 */
	public void clearSmeltingCacheAll() {
		Arrays.fill(lastCheckedInputs, ItemStack.EMPTY);
		Arrays.fill(lastHasSmeltingRecipes, false);
		// 同步清空每进程的当前PB配方引用，确保配方重载后不会使用过期配方
		Arrays.fill(cachedPbRecipes, null);
	}

	/**
	 * 检查指定进程的输入是否有SMELTING配方（带缓存优化）
	 * <br/>
	 * 输入变更时才重新查询，避免每tick每进程都调用containsInput。
	 * 配方重载时（recipeVersion变更）自动失效缓存。
	 *
	 * @param process 进程索引
	 * @param input   当前输入物品
	 * @return true 如果存在SMELTING配方
	 */
	public boolean hasSmeltingRecipe(int process, ItemStack input) {
		checkRecipeVersion();
		if (ItemStack.isSameItemSameComponents(input, lastCheckedInputs[process])) {
			return lastHasSmeltingRecipes[process];
		}
		boolean hasSmeltingRecipe = context.containsSmeltingInput(input);
		lastCheckedInputs[process] = input.copy();
		lastHasSmeltingRecipes[process] = hasSmeltingRecipe;
		return hasSmeltingRecipe;
	}

	/** 重置指定进程的SMELTING配方缓存（输入为空时调用） */
	public void resetSmeltingCache(int process) {
		lastCheckedInputs[process] = ItemStack.EMPTY;
	}

	// ===== PB配方处理主流程 =====

	/**
	 * 尝试PB离心配方处理（单进程）
	 * <br/>
	 * 如果输入匹配PB CentrifugeRecipe且无SMELTING配方，则独立处理。
	 * 万象创世蜜脾/蜜脾块走特殊处理路径（转化为随机蜜脾）。
	 *
	 * @param processIndex 进程索引
	 * @return true 如果正在处理PB配方
	 */
	public boolean tryProcessPbRecipe(int processIndex) {
		try {
			return tryProcessPbRecipeInternal(processIndex);
		} catch (Exception e) {
			// 捕获异常防止tick崩溃，记录错误日志并重置PB状态
			ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 进程{}异常，重置PB状态", processIndex, e);
			clearPbState(processIndex);
			return false;
		}
	}

	private boolean tryProcessPbRecipeInternal(int processIndex) {
		try {
			Level level = context.level();
			if (level == null || level.isClientSide) return false;
			if (!context.canFunction()) return false;

			// 配方重载检测：版本号变更时清空 SMELTING 和 PB 配方缓存
			checkRecipeVersion();

			// 缓存能量和操作数（避免循环内重复调用，getEnergyPerTick可能涉及Math.pow计算）
			cachedEnergyPerTick = context.energyContainer().getEnergyPerTick();
			cachedOperationsPerTick = context.operationsPerTick();
			long currentGameTime = level.getGameTime();
			long availableEnergy = context.energyContainer().getEnergy();

			ItemStack input = context.inputSlot(processIndex).getStack();
			if (input.isEmpty()) {
				// 输入为空：清空PB状态并关闭激活位，避免进度箭头残留
				clearPbState(processIndex);
				context.setPbActiveState(false, processIndex);
				return false;
			}

			// 万象创世蜜脾/蜜脾块 — 走特殊处理路径（不走PB CentrifugeRecipe）
			if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
					|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
				return tryProcessMyriadCreations(processIndex, input);
			}

			// SMELTING配方检查已在调用方完成（缓存优化），此处直接查找PB配方
			// 性能监控：记录查找耗时和缓存命中，仅启用时产生nanoTime开销
			boolean monitor = PerformanceMonitor.isEnabled();
			long lookupStart = monitor ? System.nanoTime() : 0L;
			RecipeHolder<CentrifugeRecipe> pbRecipe = findPbRecipe(input);
			if (monitor) {
				PerformanceMonitor.getInstance().recordRecipeLookup(
						System.nanoTime() - lookupStart, pbRecipeCache.wasLastGetHit());
			}
			if (pbRecipe == null) {
				// 找不到PB配方：清空PB状态并关闭激活位
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
			int processingTime = getPbProcessingTime(recipeValue, currentGameTime);
			pbProcessingTime[processIndex] = processingTime;
			// 是否有物品输出：每 tick 只计算一次，避免 completion 路径反复调用 getRecipeOutputs().isEmpty()
			boolean hasItemOutputs = !recipeValue.getRecipeOutputs().isEmpty();

			// 检查能量是否足够
			if (availableEnergy < cachedEnergyPerTick) {
				pbProcessing[processIndex] = true;
				return true;
			}

			// 累加进度并消耗能量
			pbProcessing[processIndex] = true;
			// MU扩展下每tick可处理多次（operationsPerTick>1），未加载MU时返回1
			int operationsPerTick = cachedOperationsPerTick;
			int modifier = context.productivityModifier();
			int inputCount = input.getCount();
			int opsRun = 0;
			for (int op = 0; op < operationsPerTick; op++) {
				if (availableEnergy < cachedEnergyPerTick) {
					break;
				}
				// 聚合输出未 flush 前输入栈未实际扣除，pendingInputShrink 用于判断剩余输入
				if (pendingInputShrink >= inputCount) {
					break;
				}
				pbOperatingTicks[processIndex]++;
				availableEnergy -= cachedEnergyPerTick;
				opsRun++;

				if (pbOperatingTicks[processIndex] >= processingTime) {
					// 输出槽满时暂停处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE一致）
					// 纯流体输出配方（如 oritech 石油蜜蜂的蜜脾）没有物品输出，跳过物品槽满检查
					if (hasItemOutputs && areOutputSlotsFull(processIndex)) {
						pbOperatingTicks[processIndex] = processingTime;
						break;
					}
					if (pendingInputShrink + modifier > inputCount) {
						break;
					}
					accumulatePbRecipeOutputs(recipeValue, processIndex, modifier);
					pbOperatingTicks[processIndex] = 0;
					// 达到 flush 阈值时立即写入，避免输出槽标志位 stale 导致过量累积
					if (pendingItemCount >= PENDING_FLUSH_THRESHOLD) {
						flushPendingPbOutputs(processIndex);
						inputCount = context.inputSlot(processIndex).getStack().getCount();
					}
				}
			}

			// Task 23: 批量扣除能量 — 将本 tick 所有操作的能量一次性提取，
			// 避免每次 operation 都触发 BasicEnergyContainer.onContentsChanged 造成 listener 连锁开销。
			if (opsRun > 0 && cachedEnergyPerTick > 0) {
				context.energyContainer().extract((long) opsRun * cachedEnergyPerTick, Action.EXECUTE, AutomationType.INTERNAL);
			}

			return true;
		} finally {
			// 无论正常返回还是异常，都确保本 tick 已完成的 PB 产物写入槽位
			flushPendingPbOutputs(processIndex);
		}
	}

	/**
	 * 聚合一次 PB 配方完成所产生的输出。
	 * <br/>
	 * 不再立即调用 insertItem，而是把物品/流体数量累加到 {@link #pendingOutputs} 中，
	 * 在 tick 结束或达到阈值后统一 flush，显著减少高倍加速下的槽位 listener 触发次数。
	 *
	 * @param recipe               PB离心配方
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率
	 */
	private void accumulatePbRecipeOutputs(CentrifugeRecipe recipe, int processIndex, int productivityModifier) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int modifier = Math.max(1, productivityModifier);

		if (pendingRecipe != null && pendingRecipe != recipe) {
			// 配方变更时先写入旧配方的聚合输出，再清空配方缓存
			flushPendingPbOutputs(processIndex);
			resetPendingRecipe();
		}
		pendingRecipe = recipe;
		if (pendingRecipeOutputs == null) {
			pendingRecipeOutputs = recipe.getRecipeOutputs();
		}

		for (Map.Entry<ItemStack, ChancedOutput> entry : pendingRecipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			if (random.nextFloat() >= chanced.chance()) {
				continue;
			}
			int count = chanced.min();
			if (chanced.max() > chanced.min()) {
				count += random.nextInt(chanced.max() - chanced.min() + 1);
			}
			count *= modifier;
			if (count <= 0) {
				continue;
			}
			pendingOutputs.merge(entry.getKey(), count, Integer::sum);
			pendingItemCount += count;
		}

		FluidStack fluidOutput = recipe.getFluidOutputs();
		if (!fluidOutput.isEmpty()) {
			if (pendingFluidTemplate == null) {
				pendingFluidTemplate = fluidOutput.copyWithAmount(0);
			}
			pendingFluidAmount += fluidOutput.getAmount() * modifier;
		}

		pendingInputShrink += modifier;
	}

	/**
	 * 将聚合的 PB 配方输出实际插入槽位并扣除输入。
	 * <br/>
	 * 使用 {@link PbRecipeContext#productivebeesgenesis$beginOutputBatch()} /
	 * {@link PbRecipeContext#productivebeesgenesis$endOutputBatch(int)} 包装，
	 * 使输出槽 listener 只在本批次结束时扫描一次标志位。
	 *
	 * @param processIndex 进程索引
	 * @return true（与原有 completePbRecipe 语义一致，便于后续扩展）
	 */
	private boolean flushPendingPbOutputs(int processIndex) {
		// 修复：纯流体输出配方（如 oritech 石油蜜蜂的蜜脾）没有物品输出，但仍有流体和输入扣除待处理
		if (pendingRecipe == null
				|| (pendingOutputs.isEmpty() && pendingFluidAmount <= 0 && pendingInputShrink <= 0)) {
			clearPendingOutputs();
			return true;
		}

		context.productivebeesgenesis$beginOutputBatch();
		try {
			reusableOutputSlots.clear();
			reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
			IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
			if (secondary != null) {
				reusableOutputSlots.add(secondary);
			}
			reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));

			int slotIndex = 0;
		Map<ItemStack, ChancedOutput> recipeOutputs = pendingRecipeOutputs != null
				? pendingRecipeOutputs
				: pendingRecipe.getRecipeOutputs();
		for (Map.Entry<ItemStack, ChancedOutput> entry : recipeOutputs.entrySet()) {
			Integer count = pendingOutputs.get(entry.getKey());
				if (count == null || count <= 0) {
					continue;
				}
				ItemStack output = entry.getKey().copyWithCount(count);

				if (slotIndex < reusableOutputSlots.size()) {
					ItemStack remainder = reusableOutputSlots.get(slotIndex)
							.insertItem(output, Action.EXECUTE, AutomationType.INTERNAL);
					if (!remainder.isEmpty()) {
						for (int i = slotIndex + 1; i < reusableOutputSlots.size(); i++) {
							remainder = reusableOutputSlots.get(i)
									.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
							if (remainder.isEmpty()) {
								break;
							}
						}
						// 输出槽满时静默丢弃（与原版行为一致，避免日志刷屏）
					}
				}
				slotIndex++;
			}

			if (pendingFluidTemplate != null && pendingFluidAmount > 0) {
				FluidStack scaledFluid = pendingFluidTemplate.copyWithAmount(pendingFluidAmount);
				IExtendedFluidTank tank = context.fluidOutputTank();
				if (tank != null) {
					tank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
				}
			}

			if (pendingInputShrink > 0) {
				context.inputSlot(processIndex).shrinkStack(pendingInputShrink, Action.EXECUTE);
			}
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
			clearPendingOutputs();
		}
		return true;
	}

	/**
	 * 清空聚合输出缓存（保留当前配方引用与模板，便于同 tick 内继续累加同一配方）。
	 * <br/>
	 * 注意：不清空 {@link #pendingFluidTemplate}，因为同一配方的流体模板可以复用，
	 * 避免每次 flush 后重新调用 {@link CentrifugeRecipe#getFluidOutputs()}。
	 */
	private void clearPendingOutputs() {
		pendingOutputs.clear();
		pendingFluidAmount = 0;
		pendingInputShrink = 0;
		pendingItemCount = 0;
	}

	/** 配方变更或输入清空时重置聚合配方引用 */
	private void resetPendingRecipe() {
		pendingRecipe = null;
		pendingRecipeOutputs = null;
		pendingFluidTemplate = null;
		clearPendingOutputs();
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
	 */
	private boolean tryProcessMyriadCreations(int processIndex, ItemStack input) {
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
		int operationsPerTick = cachedOperationsPerTick;
		for (int op = 0; op < operationsPerTick; op++) {
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
				if (operationsPerTick <= 1) {
					success = completeMyriadCreations(input, processIndex, context.productivityModifier());
				} else {
					int inputCount = context.inputSlot(processIndex).getStack().getCount();
					int batchSize = Math.min(operationsPerTick, inputCount);
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

	// ===== 配方查找 =====

	/**
	 * 查找匹配输入物品的PB离心配方（双层缓存：inputRecipeCache + pbRecipeCache）
	 * <br/>
	 * 上层 {@link #inputRecipeCache}（TTL 20 tick + identity 短路）减少每 tick 重复查找；
	 * 下层 {@link #pbRecipeCache}（LRU，配方重载时清空）提供长期缓存。
	 * 普通蜜脾路径优先用 {@link CentrifugeRecipeIndex} O(1) 查找，未命中再回退到全量遍历（防御性）。
	 * 蜜脾块路径优先用 {@link CentrifugeRecipeIndex#getCombBlock} O(1) 查找静态预生成配方，
	 * 未命中再回退到全量遍历（防御性，仅索引构建遗漏时触发）。
	 *
	 * @param input 输入物品
	 * @return 匹配的配方Holder，无匹配返回null
	 */
	@Nullable
	public RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
		Level level = context.level();
		if (level == null) return null;

		// 上层短期缓存（TTL + identity 短路），减少 pbRecipeCache 的 hashItemAndComponents 开销
		InputValidationCache.ValidationResult cached = inputRecipeCache.getResult(level, input,
				() -> {
					RecipeHolder<CentrifugeRecipe> recipe = findPbRecipeUncached(input);
					return new InputValidationCache.ValidationResult(recipe != null, recipe, null, false);
				});
		return cached.recipe();
	}

	/**
	 * 查找PB配方的底层实现（仅查 pbRecipeCache LRU + 全量遍历，不经 inputRecipeCache）
	 * <br/>
	 * 由 {@link #findPbRecipe} 的 inputRecipeCache 未命中时通过 validator 调用。
	 * 查找结果会写入 pbRecipeCache 供后续长期复用。
	 */
	@Nullable
	private RecipeHolder<CentrifugeRecipe> findPbRecipeUncached(ItemStack input) {
		Level level = context.level();
		if (level == null) return null;

		// 查询 LRU 缓存（支持缓存"无配方"结果，避免重复全量遍历）
		Optional<RecipeHolder<CentrifugeRecipe>> cached = pbRecipeCache.get(input);
		if (cached != null) {
			return cached.orElse(null);
		}

		// 蜜脾块 — 优先从静态索引查找（O(1)），未命中回退到全量遍历（防御性）
		if (input.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
			ResourceLocation beeType = input.get(ModDataComponents.BEE_TYPE.get());
			if (beeType != null) {
				RecipeHolder<CentrifugeRecipe> blockRecipe = CentrifugeRecipeIndex.getCombBlock(beeType);
				if (blockRecipe != null) {
					pbRecipeCache.put(input, blockRecipe);
					return blockRecipe;
				}
			}
			// 索引未命中（bee_type 为 null 或索引遗漏）— 全量遍历回退（防御性）
			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
				if (holder.value().ingredient.test(input)) {
					pbRecipeCache.put(input, holder);
					return holder;
				}
			}
			pbRecipeCache.put(input, null);
			return null;
		}

		// 普通蜜脾 — 优先从索引查找（O(1)），未命中再全量遍历（防御性回退）
		ResourceLocation beeType = input.get(ModDataComponents.BEE_TYPE.get());
		if (beeType != null) {
			RecipeHolder<CentrifugeRecipe> indexed = CentrifugeRecipeIndex.get(beeType);
			if (indexed != null && indexed.value().ingredient.test(input)) {
				pbRecipeCache.put(input, indexed);
				return indexed;
			}
		}

		// 索引未命中（无 bee_type 或索引为空或索引遗漏）— 全量遍历回退
		for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
				.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
			if (holder.value().ingredient.test(input)) {
				pbRecipeCache.put(input, holder);
				return holder;
			}
		}

		// 缓存"无配方"结果，避免下次重复全量遍历
		pbRecipeCache.put(input, null);
		return null;
	}

	// ===== 配方完成 =====

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
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
		}
		reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));

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
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
		}
		reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));

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

	// ===== 辅助方法 =====

	/** 获取PB配方处理时间（考虑速度升级） */
	private int getPbProcessingTime(CentrifugeRecipe recipe, long currentGameTime) {
		int baseTime = recipe.getProcessingTime();
		if (baseTime <= 0) baseTime = context.baseTicksRequired();
		return getCachedTicksForBase(baseTime, currentGameTime);
	}

	/**
	 * 本 tick 内 getTicksForBase(baseTime) 的结果缓存 — 用于PB配方处理时间计算
	 * <br/>
	 * 同一 tick 内升级组件不变，对相同 baseTime 的结果必然相同。
	 * 缓存避免高倍加速下每进程每 tick 重复执行升级遍历与 Math.pow。
	 *
	 * @param baseTime 基础处理时间
	 * @param currentGameTime 当前游戏刻（由调用方统一获取）
	 * @return 受速度升级影响的实际处理时间
	 */
	private int getCachedTicksForBase(int baseTime, long currentGameTime) {
		if (currentGameTime != ticksForBaseCacheAt) {
			ticksForBaseCache.clear();
			ticksForBaseCacheAt = currentGameTime;
		}
		Integer cached = ticksForBaseCache.get(baseTime);
		if (cached == null) {
			cached = context.getTicksForBase(baseTime);
			ticksForBaseCache.put(baseTime, cached);
		}
		return cached;
	}

	/**
	 * 获取缓存的 getTicksForBase(baseTicksRequired) 结果（时间窗口缓存）
	 * <br/>
	 * 升级组件哈希计算开销较大且升级变更不频繁，采用"每 N tick 重新计算一次"策略，
	 * 与 TileEntityMekCentrifuge.getCachedTicks 模式一致。
	 * 升级变更后最多 {@link #TICKS_CACHE_INTERVAL} tick（1秒）内自动反映新值，可接受。
	 * <p>
	 * 线程安全：cachedTicksForBase 和 cachedTicksForBaseAt 为 volatile，读写原子；
	 * 方块实体在服务端单线程执行，多进程共享同一缓存（升级组件为工厂级共享）。
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

	/**
	 * 检查指定进程的所有物品输出槽是否已满
	 * <br/>
	 * 满时暂停PB配方处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE行为一致）。
	 * 仅检查物品槽，流体槽满时不暂停（流体溢出量通常较小）。
	 * <p>
	 * Task 5 优化：通过 {@link PbRecipeContext} 接口读取工厂维护的 outputSlotsFull 标志位，
	 * 避免每次调用都遍历输出槽。标志位由工厂的 IContentsListener 触发 updateOutputSlotFlags() 更新。
	 * <p>
	 * Task 23: 使用按进程判断的版本，避免单个进程输出槽满导致所有进程暂停。
	 */
	private boolean areOutputSlotsFull(int process) {
		return context.productivebeesgenesis$outputSlotsFull(process);
	}

	/** 清除指定进程的PB处理状态（同时关闭该进程的激活位，避免进度箭头残留） */
	private void clearPbState(int processIndex) {
		if (pbProcessing[processIndex]) {
			pbProcessing[processIndex] = false;
			pbOperatingTicks[processIndex] = 0;
			pbProcessingTime[processIndex] = 0;
			cachedPbRecipes[processIndex] = null;
		}
		// 无论pbProcessing状态如何，都关闭该进程的激活位
		// 防止输入耗尽/配方变更后激活位仍为true导致进度箭头残留
		context.setPbActiveState(false, processIndex);
	}

	/**
	 * 检查PB配方输出与现有输出槽内容是否兼容
	 * <br/>
	 * 遍历PB配方的可能输出，检查主输出槽和副输出槽1中的现有物品是否可堆叠。
	 * 只要有一个输出不兼容就返回false（排序不应将物品分配到输出不兼容的进程）。
	 */
	public boolean isPbOutputCompatible(CentrifugeRecipe recipe,
									@NotNull IInventorySlot outputSlot,
									@Nullable IInventorySlot secondaryOutputSlot) {
		Map<ItemStack, ChancedOutput> outputs = recipe.getRecipeOutputs();
		if (outputs.isEmpty()) {
			return true;
		}
		// 检查主输出槽
		ItemStack existingOutput = outputSlot.getStack();
		if (!existingOutput.isEmpty()) {
			ItemStack recipeOutput = outputs.entrySet().iterator().next().getKey();
			if (!InventoryUtils.areItemsStackable(recipeOutput, existingOutput)) {
				return false;
			}
		}
		// 检查副输出槽1
		if (secondaryOutputSlot != null) {
			ItemStack existingSecondary = secondaryOutputSlot.getStack();
			if (!existingSecondary.isEmpty() && outputs.size() > 1) {
				var iter = outputs.entrySet().iterator();
				iter.next(); // 跳过主输出
				ItemStack recipeSecondary = iter.next().getKey();
				if (!InventoryUtils.areItemsStackable(recipeSecondary, existingSecondary)) {
					return false;
				}
			}
		}
		return true;
	}

	// ===== 客户端同步和持久化 =====

	/** 检查指定进程是否正在处理PB配方 */
	public boolean isPbProcessing(int process) {
		return pbProcessing[process];
	}

	/**
	 * 获取PB处理的缩放进度（0.0~1.0）
	 * <br/>
	 * 使用同步的pbProcessingTime避免客户端重新计算（客户端无法访问升级组件）。
	 *
	 * @param i       进度缩放因子
	 * @param process 进程索引
	 * @return 进度比例
	 */
	public double getPbScaledProgress(int i, int process) {
		int processingTime = pbProcessingTime[process] > 0 ? pbProcessingTime[process] : context.baseTicksRequired();
		return Math.min(1.0, (double) pbOperatingTicks[process] * i / processingTime);
	}

	/**
	 * 同步PB进度到客户端
	 * <br/>
	 * 每进程的pbOperatingTicks、pbProcessing和pbProcessingTime需要同步给客户端用于GUI显示。
	 */
	public void addContainerTrackers(MekanismContainer container) {
		container.trackArray(pbOperatingTicks);
		container.trackArray(pbProcessing);
		container.trackArray(pbProcessingTime);
	}

	/** 持久化PB配方处理进度（防止重启后PB处理进度丢失） */
	public void saveAdditional(CompoundTag nbt) {
		nbt.putIntArray("PbProgress", pbOperatingTicks);
	}

	/** 加载PB配方处理进度 */
	public void loadAdditional(CompoundTag nbt) {
		if (nbt.contains("PbProgress", Tag.TAG_INT_ARRAY)) {
			int[] saved = nbt.getIntArray("PbProgress");
			for (int i = 0; i < pbOperatingTicks.length && i < saved.length; i++) {
				pbOperatingTicks[i] = saved[i];
			}
		}
	}
}
