package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.PerformanceMonitor;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.util.InventoryUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * PB配方处理器 — 主协调器，委托配方查找/输出聚合/万象创世处理给专门的子组件
 * <br/>
 * 处理路径：
 * <ul>
 *   <li>PB CentrifugeRecipe：概率多物品输出+流体输出，独立于Mekanism CachedRecipe管线</li>
 *   <li>万象创世蜜脾/蜜脾块：转化为随机蜜脾/蜜脾块（特殊处理路径）</li>
 * </ul>
 * SMELTING配方优先于PB配方（同一输入若有SMELTING配方则走SMELTING路径，由调用方判断）。
 * <p>
 * 职责拆分（Task 17）：
 * <ul>
 *   <li>{@link PbRecipeFinder}：双层缓存的配方查找</li>
 *   <li>{@link PbRecipeCompleter}：输出聚合与批量插入</li>
 *   <li>{@link MyriadCreationsHandler}：万象创世特殊路径</li>
 * </ul>
 * 本类持有每进程共享数组（pbOperatingTicks/pbProcessing/pbProcessingTime/cachedPbRecipes），
 * 并通过数组引用与 {@link MyriadCreationsHandler} 共享；每 tick 缓存的能量/操作数作为参数传入。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
public class PbRecipeProcessor {

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

	/** 配方查找器（双层缓存：inputRecipeCache + pbRecipeCache） */
	private final PbRecipeFinder recipeFinder;

	/** 输出聚合器（批量插入，减少 listener 触发次数） */
	private final PbRecipeCompleter recipeCompleter;

	/** 万象创世处理器（持有共享数组引用） */
	private final MyriadCreationsHandler myriadHandler;

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
	// 单线程上下文，使用 HashMap 即可（方块实体在服务端单线程执行，无需同步锁，避免不必要的 segment 锁开销）
	private final HashMap<Integer, Integer> ticksForBaseCache = new HashMap<>(8);

	/** 当前 ticksForBaseCache 对应的游戏刻，变化时清空缓存 */
	private long ticksForBaseCacheAt = -1L;

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
		// 子组件：查找器自拥有缓存；输出聚合器自拥有 pending 缓冲；
		// 万象处理器持有共享数组引用（Java 数组为引用语义，本类对其的变更对万象处理器可见，反之亦然）
		this.recipeFinder = new PbRecipeFinder(context);
		this.recipeCompleter = new PbRecipeCompleter(context);
		this.myriadHandler = new MyriadCreationsHandler(context, logPrefix,
				pbOperatingTicks, pbProcessing, pbProcessingTime, cachedPbRecipes);
	}

	// ===== SMELTING配方缓存检查 =====

	/**
	 * 检查配方版本号是否变更，变更则清空所有 SMELTING 和 PB 配方缓存
	 * <br/>
	 * 在每次进入 hasSmeltingRecipe 和 tryProcessPbRecipe 时调用，确保配方重载后
	 * （/reload、数据包变更）立即失效旧缓存，避免使用过期的配方检查结果。
	 */
	private void checkRecipeVersion() {
		if (lastRecipeVersion != ProductiveBeesGenesis.recipeVersion) {
			clearSmeltingCacheAll();
			// 清空每进程的当前PB配方引用，防止使用过期配方（配方重载后旧引用可能已失效）
			Arrays.fill(cachedPbRecipes, null);
			recipeFinder.clearCaches();
			// 失效万象处理器的 getTicksForBase 缓存（配方重载可能伴随升级配置变化，强制下次重新计算）
			myriadHandler.clearCachedTicksForBase();
			lastRecipeVersion = ProductiveBeesGenesis.recipeVersion;
		}
	}

	/**
	 * 清空所有进程的 SMELTING 配方缓存
	 * <br/>
	 * 在配方重载（/reload）时调用，确保下次 hasSmeltingRecipe 调用会重新查询配方。
	 * 同时清空每进程的当前PB配方引用（cachedPbRecipes），因为 PB CentrifugeRecipe 也可能变更。
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

			// 万象创世蜜脾/蜜脾块 — 委托给万象处理器（走特殊处理路径，不走PB CentrifugeRecipe）
			if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
					|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
				return myriadHandler.tryProcessMyriadCreations(processIndex, input,
						cachedEnergyPerTick, cachedOperationsPerTick);
			}

			// SMELTING配方检查已在调用方完成（缓存优化），此处直接查找PB配方
			// 性能监控：记录查找耗时和缓存命中，仅启用时产生nanoTime开销
			boolean monitor = PerformanceMonitor.isEnabled();
			long lookupStart = monitor ? System.nanoTime() : 0L;
			RecipeHolder<CentrifugeRecipe> pbRecipe = recipeFinder.findPbRecipe(input);
			if (monitor) {
				PerformanceMonitor.getInstance().recordRecipeLookup(
						System.nanoTime() - lookupStart, recipeFinder.wasLastGetHit());
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
				if (recipeCompleter.pendingInputShrink() >= inputCount) {
					break;
				}
				// Task 8 修复：输出槽满且进度已满时，不消耗能量、不累加 opsRun，直接退出循环
				// 防止产物受阻时每个 tick 仍空耗 1 倍 cachedEnergyPerTick（违反设计约束：输出槽满不应空耗能量）
				// 注意：需放在 pbOperatingTicks++ 与 availableEnergy-= 前，否则本 op 的能量已扣除
				if (hasItemOutputs && areOutputSlotsFull(processIndex)
							&& pbOperatingTicks[processIndex] >= processingTime) {
					pbOperatingTicks[processIndex] = processingTime; // 保持满进度
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
					if (recipeCompleter.pendingInputShrink() + modifier > inputCount) {
						break;
					}
					recipeCompleter.accumulatePbRecipeOutputs(recipeValue, processIndex, modifier);
					pbOperatingTicks[processIndex] = 0;
					// 达到 flush 阈值时立即写入，避免输出槽标志位 stale 导致过量累积
					if (recipeCompleter.pendingItemCount() >= PbRecipeCompleter.PENDING_FLUSH_THRESHOLD) {
						recipeCompleter.flushPendingPbOutputs(processIndex);
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
			// 万象路径下聚合器无 pending，flush 为 no-op，安全
			recipeCompleter.flushPendingPbOutputs(processIndex);
		}
	}

	/**
	 * 查找匹配输入物品的PB离心配方（委托给 {@link PbRecipeFinder}）
	 * <br/>
	 * 保留为公共方法供外部调用方（Factory Helper、基础离心机）使用。
	 *
	 * @param input 输入物品
	 * @return 匹配的配方Holder，无匹配返回null
	 */
	@Nullable
	public RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
		return recipeFinder.findPbRecipe(input);
	}

	// ===== 辅助方法 =====

	/** 获取PB配方处理时间（考虑速度升级） */
	private int getPbProcessingTime(CentrifugeRecipe recipe, long currentGameTime) {
		int baseTime = recipe.getProcessingTime();
		if (baseTime <= 0) baseTime = context.baseTicksRequired();
		return getCachedTicksForBase(baseTime, currentGameTime);
	}

	/** 本 tick 内 getTicksForBase(baseTime) 结果缓存（同 tick 内升级不变，避免重复 Math.pow） */
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
	 * 检查指定进程的所有物品输出槽是否已满
	 * <br/>
	 * 满时暂停PB配方处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE一致）。
	 * 通过 {@link PbRecipeContext} 读取工厂维护的标志位（Task 5），按进程判断（Task 23）。
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
	 * 强制重置指定进程的 PB 处理状态（不调用 setPbActiveState）
	 * <br/>
	 * 供基础机器 {@link TileEntityMekCentrifuge} 在 SMELTING 检查命中时调用：
	 * 基础机器的 active 由 onUpdateServer 的 pbWasProcessing 逻辑管理，setPbActiveState 为 no-op，
	 * 因此需要不触发激活位变更的重置方法，避免与 SMELTING 的 setActive 冲突。
	 * 与 {@link #clearPbState} 的区别：无条件重置且不调用 setPbActiveState。
	 */
	public void resetPbState(int processIndex) {
		pbProcessing[processIndex] = false;
		pbOperatingTicks[processIndex] = 0;
		pbProcessingTime[processIndex] = 0;
		cachedPbRecipes[processIndex] = null;
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
