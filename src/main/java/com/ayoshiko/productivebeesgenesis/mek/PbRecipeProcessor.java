package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
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
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

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

	/** 缓存的每tick能量消耗（每次进入处理方法时刷新，避免循环内重复调用可能涉及Math.pow的计算） */
	private long cachedEnergyPerTick;

	/** 缓存的每tick操作数（每次进入处理方法时刷新，升级变更会在下次进入方法时自动反映） */
	private int cachedOperationsPerTick;

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

	/**
	 * @param context   PB配方处理上下文（由Factory TileEntity实现）
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
		Level level = context.level();
		if (level == null || level.isClientSide) return false;
		if (!context.canFunction()) return false;

		// 配方重载检测：版本号变更时清空 SMELTING 和 PB 配方缓存
		checkRecipeVersion();

		// 缓存能量和操作数（避免循环内重复调用，getEnergyPerTick可能涉及Math.pow计算）
		cachedEnergyPerTick = context.energyContainer().getEnergyPerTick();
		cachedOperationsPerTick = context.operationsPerTick();

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

		// 计算并存储PB配方处理时间（同步到客户端用于进度条显示）
		int processingTime = getPbProcessingTime(pbRecipe.value());
		pbProcessingTime[processIndex] = processingTime;

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
				// 输出槽满时暂停处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE一致）
				if (areOutputSlotsFull(processIndex)) {
					pbOperatingTicks[processIndex] = processingTime;
					break;
				}
				completePbRecipe(pbRecipe.value(), input, processIndex, context.productivityModifier());
				pbOperatingTicks[processIndex] = 0;
				if (context.inputSlot(processIndex).getStack().isEmpty()) {
					context.setPbActiveState(false, processIndex);
					break;
				}
			}
		}

		return true;
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
				// 输出槽物理满或万象创世逻辑满时暂停处理，避免产物丢失
				if (areOutputSlotsFull(processIndex) || areMyriadOutputSlotsFull(processIndex)) {
					pbOperatingTicks[processIndex] = processingTime;
					break;
				}
				if (!completeMyriadCreations(input, processIndex, context.productivityModifier())) {
					pbOperatingTicks[processIndex] = processingTime;
					break;
				}
				pbOperatingTicks[processIndex] = 0;
				if (context.inputSlot(processIndex).getStack().isEmpty()) {
					context.setPbActiveState(false, processIndex);
					break;
				}
			}
		}

		return true;
	}

	// ===== 配方查找 =====

	/**
	 * 查找匹配输入物品的PB离心配方（带实例级LRU缓存）
	 * <br/>
	 * 缓存命中时直接返回，避免每tick全量遍历配方列表。
	 * 普通蜜脾路径优先用 {@link CentrifugeRecipeIndex} O(1) 查找，未命中再回退到全量遍历（防御性）。
	 * 蜜脾块输入会动态生成对应的蜜脾块离心配方（min/max和流体乘以倍率）。
	 *
	 * @param input 输入物品
	 * @return 匹配的配方Holder，无匹配返回null
	 */
	@Nullable
	public RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
		Level level = context.level();
		if (level == null) return null;

		// 查询缓存（支持缓存"无配方"结果，避免重复全量遍历）
		Optional<RecipeHolder<CentrifugeRecipe>> cached = pbRecipeCache.get(input);
		if (cached != null) {
			return cached.orElse(null);
		}

		// 蜜脾块 — 动态生成蜜脾块离心配方（参考PB JEI插件逻辑）
		if (input.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
			RecipeHolder<CentrifugeRecipe> blockRecipe = createCombBlockRecipe(input);
			if (blockRecipe != null) {
				pbRecipeCache.put(input, blockRecipe);
				return blockRecipe;
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

	/**
	 * 动态生成蜜脾块离心配方
	 * <br/>
	 * 蜜脾块 = 4个蜜脾，所以输出min/max和流体都乘以配置倍率。
	 * 参考PB JEI插件（ProductiveBeesJeiPlugin）的蜜脾块配方生成逻辑。
	 * 通过bee_type组件查找对应的蜜脾离心配方，然后构建蜜脾块版本。
	 * 优先使用 {@link CentrifugeRecipeIndex} O(1) 查找，未命中再全量遍历（防御性回退）。
	 */
	@Nullable
	private RecipeHolder<CentrifugeRecipe> createCombBlockRecipe(ItemStack combBlockInput) {
		ResourceLocation beeType = combBlockInput.get(ModDataComponents.BEE_TYPE.get());
		if (beeType == null) return null;

		// 优先从索引查找蜜脾配方（O(1)）
		RecipeHolder<CentrifugeRecipe> honeycombRecipe = CentrifugeRecipeIndex.get(beeType);

		// 索引未命中 — 回退到全量遍历（防御性）
		if (honeycombRecipe == null) {
			ItemStack honeycomb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			honeycomb.set(ModDataComponents.BEE_TYPE.get(), beeType);
			Level level = context.level();
			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
				if (holder.value().ingredient.test(honeycomb)) {
					honeycombRecipe = holder;
					break;
				}
			}
		}

		if (honeycombRecipe == null) return null;

		// 动态生成蜜脾块配方：min/max和流体按配置倍率缩放
		int multiplier = ModConfig.COMMON.mekCentrifugeCombBlockMultiplier.get();
		CentrifugeRecipe original = honeycombRecipe.value();
		List<ChancedOutput> blockOutputs = new ArrayList<>();
		for (ChancedOutput chanced : original.itemOutput) {
			blockOutputs.add(new ChancedOutput(chanced.ingredient(), chanced.min() * multiplier, chanced.max() * multiplier, chanced.chance()));
		}
		SizedFluidIngredient blockFluid = new SizedFluidIngredient(original.fluidOutput.ingredient(), original.fluidOutput.amount() * multiplier);
		CentrifugeRecipe blockRecipe = new CentrifugeRecipe(original.ingredient, blockOutputs, blockFluid, original.getProcessingTime());

		return new RecipeHolder<>(honeycombRecipe.id().withSuffix("_block"), blockRecipe);
	}

	// ===== 配方完成 =====

	/**
	 * 完成PB离心配方处理 — 概率多物品输出 + 流体输出
	 * <br/>
	 * PB的ChancedOutput包含概率(chance)、最小数量(min)、最大数量(max)。
	 * 对每个输出：如果随机值 < chance，则产出 min~max 个物品。
	 * 多个物品输出分别放入主输出槽、副输出槽1、副输出槽2。
	 * 流体输出直接写入共享FluidTank。
	 * 生产力倍率影响输出数量和消耗输入数量。
	 * <p>
	 * 性能优化：复用reusableOutputSlots列表避免每次创建新ArrayList，
	 * 使用ThreadLocalRandom替代level.getRandom()减少随机数生成开销。
	 *
	 * @param recipe               PB离心配方
	 * @param input                输入物品
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率（1=正常，>1=多倍输出）
	 */
	private void completePbRecipe(CentrifugeRecipe recipe, ItemStack input, int processIndex, int productivityModifier) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int modifier = Math.max(1, productivityModifier);
		Map<ItemStack, ChancedOutput> outputs = recipe.getRecipeOutputs();

		// 复用输出槽列表，避免每次完成配方都创建新ArrayList
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
		}
		reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));

		int slotIndex = 0;
		for (var entry : outputs.entrySet()) {
			var chanced = entry.getValue();
			if (random.nextFloat() < chanced.chance()) {
				int count = chanced.min();
				if (chanced.max() > chanced.min()) {
					count += random.nextInt(chanced.max() - chanced.min() + 1);
				}
				// 生产力倍率影响输出数量
				count *= modifier;
				ItemStack outputStack = entry.getKey().copy();
				outputStack.setCount(count);

				// 尝试放入对应的输出槽，放不下则尝试后续槽
				if (slotIndex < reusableOutputSlots.size()) {
					ItemStack remainder = reusableOutputSlots.get(slotIndex)
							.insertItem(outputStack, Action.EXECUTE, AutomationType.INTERNAL);
					if (!remainder.isEmpty()) {
						for (int i = slotIndex + 1; i < reusableOutputSlots.size(); i++) {
							remainder = reusableOutputSlots.get(i)
									.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
							if (remainder.isEmpty()) break;
						}
						if (!remainder.isEmpty()) {
							ProductiveBeesGenesis.LOGGER.info("{}进程{}输出槽已满，丢弃: {}", logPrefix, processIndex, remainder);
						}
					}
				}
			}
			slotIndex++;
		}

		// 处理流体输出（乘以生产力倍率）
		// 流体槽满时静默丢弃（属正常状态，避免日志刷屏影响性能）
		FluidStack fluidOutput = recipe.getFluidOutputs();
		IExtendedFluidTank tank = context.fluidOutputTank();
		if (tank != null && !fluidOutput.isEmpty()) {
			FluidStack scaledFluid = fluidOutput.copy();
			scaledFluid.setAmount(scaledFluid.getAmount() * modifier);
			tank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
		}

		// 消耗输入（乘以生产力倍率）
		context.inputSlot(processIndex).shrinkStack(modifier, Action.EXECUTE);
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
	 *   <li>插入前检查 occupiedTypes + 新增类型数，超过3种时暂停处理</li>
	 *   <li>无法完全插入时返回 false，由调用方暂停而不是丢弃产物</li>
	 * </ul>
	 *
	 * @param input                万象创世蜜脾或蜜脾块
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率
	 * @return true 处理成功，false 应暂停等待输出槽空间
	 */
	private boolean completeMyriadCreations(ItemStack input, int processIndex, int productivityModifier) {
		RandomSource random = context.level().getRandom();
		boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		int modifier = Math.max(1, productivityModifier);

		// 万象创世蜜脾块 = 4个蜜脾，输出总数乘以4
		int totalCount = isCombBlock ? modifier * 4 : modifier;

		// 限制种类数不超过3（用户要求）和输出槽数
		int maxTypes = Math.min(3, totalCount);
		List<ResourceLocation> selectedTypes = MyriadCreationsEventHandler.selectDistinctBeeTypes(maxTypes, random);
		if (selectedTypes.isEmpty()) {
			// 缓存为空：不消耗输入，记录WARN日志（避免卡死和物品丢失，等待缓存重建后重试）
			ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世类型缓存为空，跳过本次处理（不消耗输入）", logPrefix, processIndex);
			return true;
		}

		// 均匀分配totalCount到selectedTypes，已按 bee_type 聚合
		Map<ResourceLocation, Integer> allocation = MyriadCreationsEventHandler.allocateEvenly(totalCount, selectedTypes);

		// 构建输出槽列表
		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
		}
		reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));

		// 检查当前输出槽已占用类型数，仅统计本次新增的不同类型
		Set<ResourceLocation> occupiedTypes = MyriadCreationsEventHandler.getOutputBeeTypes(reusableOutputSlots);
		long newDistinctTypes = allocation.keySet().stream().filter(type -> !occupiedTypes.contains(type)).count();
		if (occupiedTypes.size() + newDistinctTypes > 3) {
			// 输出槽类型空间不足：不记录日志，避免高频暂停时刷屏
			return false;
		}

		// 按 bee_type 聚合插入，同类型优先堆叠
		for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
			ItemStack output = new ItemStack(baseItem, entry.getValue());
			output.set(ModDataComponents.BEE_TYPE.get(), entry.getKey());

			ItemStack remainder = output;
			// 第一优先级：已有同类型槽位
			for (IInventorySlot slot : reusableOutputSlots) {
				if (slot == null) continue;
				if (MyriadCreationsEventHandler.isSameBeeType(slot.getStack(), output)) {
					remainder = slot.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
					if (remainder.isEmpty()) break;
				}
			}
			// 第二优先级：空槽
			if (!remainder.isEmpty()) {
				for (IInventorySlot slot : reusableOutputSlots) {
					if (slot == null) continue;
					if (slot.getStack().isEmpty()) {
						remainder = slot.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
						if (remainder.isEmpty()) break;
					}
				}
			}
			// 仍有剩余说明无法放下，暂停处理而非丢弃
			if (!remainder.isEmpty()) {
				ProductiveBeesGenesis.LOGGER.warn("{}进程{}万象创世产物无法完全插入，暂停：{}", logPrefix, processIndex, remainder);
				return false;
			}
		}

		// 消耗输入（乘以生产力倍率）
		context.inputSlot(processIndex).shrinkStack(modifier, Action.EXECUTE);
		return true;
	}

	// ===== 辅助方法 =====

	/** 获取PB配方处理时间（考虑速度升级） */
	private int getPbProcessingTime(CentrifugeRecipe recipe) {
		int baseTime = recipe.getProcessingTime();
		if (baseTime <= 0) baseTime = context.baseTicksRequired();
		return context.getTicksForBase(baseTime);
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
	 */
	private boolean areOutputSlotsFull(int process) {
		return context.productivebeesgenesis$outputSlotsFull();
	}

	/**
	 * 检查指定进程的万象创世输出槽是否“逻辑已满”
	 * <br/>
	 * 当3个槽均非空且各自包含不同的 bee_type 时视为逻辑已满，
	 * 新的随机类型无法堆叠，必须暂停处理等待弹出器清理。
	 */
	private boolean areMyriadOutputSlotsFull(int processIndex) {
		reusableOutputSlots.clear();
		reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
		IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
		if (secondary != null) {
			reusableOutputSlots.add(secondary);
		}
		reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));
		return MyriadCreationsEventHandler.areOutputSlotsFullForMyriadCreations(reusableOutputSlots);
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
