package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.RecipeCacheLookupMonitorAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.config.MekanismConfig;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.IProxiedSlotInfo;
import mekanism.common.tile.interfaces.ISideConfiguration;
import mekanism.common.util.InventoryUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.TriPredicate;

/**
 * MEK 离心机工厂公共逻辑辅助工具类 — 抽取三工厂公共方法,采用静态工具类模式。
 * 设计原则:SRP(仅工厂公共逻辑)、DIP(函数式接口)、OCP(新增工厂不修改 Helper)。
 */
public final class MekCentrifugeFactoryHelper {

	private MekCentrifugeFactoryHelper() {
	}

	/** Forces Mekanism to resolve the active recipe again after a per-tile recipe mode change. */
	public static void invalidateRecipeMonitor(
			mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor<?> monitor) {
		if (monitor == null) return;
		((RecipeCacheLookupMonitorAccessor) monitor).productivebeesgenesis$setCachedRecipe(null);
		monitor.onChange();
	}

	/** Cached global smelting-compat master switch (refreshed on config load/reload, volatile read per probe). */
	private static volatile boolean cachedSmeltingCompatGlobalEnabled = true;

	/** Refresh the cached global smelting-compat master switch (called from ModConfigEvent listeners). */
	public static void refreshSmeltingCompatConfig() {
		cachedSmeltingCompatGlobalEnabled = ModConfig.SERVER != null
				&& ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled != null
				&& ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled.get();
	}

	/**
	 * 判断离心机当前是否允许处理电力熔炼炉（SMELTING）配方。
	 * <br/>
	 * 全局总开关 {@code mekCentrifugeSmeltingCompatEnabled} 与 per-tile 开关 AND 关系：
	 * 总开关关闭或该机器未开启时均不允许熔炉配方，只处理 PB 离心配方。
	 *
	 * @param host 离心机宿主（提供 per-tile 状态持有者）
	 * @return true 表示允许熔炉配方
	 */
	public static boolean isSmeltingCompatEnabled(IAe2OutputHostBase host) {
		if (!cachedSmeltingCompatGlobalEnabled) {
			return false;
		}
		var holder = host.productivebeesgenesis$getAe2StateHolder();
		return holder != null && holder.isSmeltingCompatEnabled();
	}

	// ===== 公共常量 =====

	/** 输出检查谓词 — 验证SMELTING配方输出与现有输出槽物品是否可堆叠 */
	public static final TriPredicate<ItemStackToItemStackRecipe, ItemStack, ItemStack> OUTPUT_CHECK =
			(recipe, input, output) -> InventoryUtils.areItemsStackable(recipe.getOutput(input), output);

	/** 工厂跟踪的配方错误类型（原版工厂构造函数使用） */
	public static final List<RecipeError> TRACKED_ERROR_TYPES = List.of(
			RecipeError.NOT_ENOUGH_ENERGY,
			RecipeError.NOT_ENOUGH_INPUT,
			RecipeError.NOT_ENOUGH_OUTPUT_SPACE,
			RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT
	);

	/** 全局配方错误类型（原版工厂构造函数使用） */
	public static final Set<RecipeError> GLOBAL_ERROR_TYPES = Set.of(RecipeError.NOT_ENOUGH_ENERGY);

	// ===== 配方类型常量 =====

	/** 返回SMELTING配方类型（Mekanism原版熔炼配方） */
	@NotNull
	public static IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getSmeltingRecipeType() {
		return MekanismRecipeType.SMELTING;
	}

	/** 返回SMELTING JEI配方查看器类型 */
	@NotNull
	public static IRecipeViewerRecipeType<ItemStackToItemStackRecipe> getSmeltingRecipeViewerType() {
		return RecipeViewerRecipeType.SMELTING;
	}

	// ===== 配方相关纯函数 =====

	/** 获取配方所需输入数量 */
	public static int getNeededInput(@NotNull ItemStackToItemStackRecipe recipe, @NotNull ItemStack inputStack) {
		return MathUtils.clampToInt(recipe.getInput().getNeededAmount(inputStack));
	}

	/** 检查缓存的配方是否对当前输入仍然有效 */
	public static boolean isCachedRecipeValid(@Nullable CachedRecipe<ItemStackToItemStackRecipe> cached,
			@NotNull ItemStack stack) {
		if (cached == null) return false;
		return cached.getRecipe().getInput().testType(stack);
	}

	/** 检查输入物品是否有SMELTING配方 */
	public static boolean containsSmeltingInput(
			@NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
					SingleItem<ItemStackToItemStackRecipe>> recipeType,
			@NotNull Level level, @NotNull ItemStack input) {
		return recipeType.getInputCache().containsInput(level, input);
	}

	/** 检查输入物品是否有效（同时查找SMELTING和PB配方） */
	public static boolean isValidInputItem(
			@NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
					SingleItem<ItemStackToItemStackRecipe>> recipeType,
			@NotNull Level level, @NotNull ItemStack stack,
			@NotNull PbRecipeProcessor pbProcessor) {
		if (containsSmeltingInput(recipeType, level, stack)) return true;
		return pbProcessor.findPbRecipe(stack) != null;
	}

	/**
	 * 获取输入校验完整结果(配方/蜜蜂类型/是否蜜脾块),扩展 isValidInputItem 缓存 ValidationResult。
	 * 有 SMELTING → valid=true,recipe=null;有 PB → valid=true,recipe=pbRecipe,beeType/isCombBlock;
	 * 无配方 → valid=false,recipe=null。
	 */
	@NotNull
	public static InputValidationCache.ValidationResult getInputValidationResult(
			@NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
					SingleItem<ItemStackToItemStackRecipe>> recipeType,
			@NotNull Level level, @NotNull ItemStack stack,
			@NotNull PbRecipeProcessor pbProcessor, boolean allowSmelting) {
		if (allowSmelting && containsSmeltingInput(recipeType, level, stack)) {
			return new InputValidationCache.ValidationResult(true, null, null, false);
		}
		RecipeHolder<CentrifugeRecipe> recipe = pbProcessor.findPbRecipe(stack);
		if (recipe == null) {
			return InputValidationCache.ValidationResult.INVALID;
		}
		ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
		boolean isCombBlock = stack.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get();
		return new InputValidationCache.ValidationResult(true, recipe, beeType, isCombBlock);
	}

	/** 查找SMELTING配方（PB配方不走Mekanism管线，由tryProcessPbRecipe独立处理） */
	@Nullable
	public static ItemStackToItemStackRecipe findSmeltingRecipe(
			@NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
					SingleItem<ItemStackToItemStackRecipe>> recipeType,
			@NotNull Level level, @NotNull ItemStack fallbackInput,
			@NotNull IInventorySlot outputSlot) {
		return recipeType.getInputCache().findTypeBasedRecipe(
				level, fallbackInput, outputSlot.getStack(), OUTPUT_CHECK);
	}

	/** PB 配方输出兼容性回退检查 — SMELTING 检查失败后调用,验证 PB 配方输出与现有输出槽兼容 */
	public static boolean checkPbOutputFallback(@NotNull PbRecipeProcessor pbProcessor,
												@NotNull ItemStack fallbackInput,
												@NotNull IInventorySlot outputSlot,
												@Nullable IInventorySlot secondaryOutputSlot) {
		RecipeHolder<CentrifugeRecipe> pbRecipe = pbProcessor.findPbRecipe(fallbackInput);
		if (pbRecipe == null) {
			return false;
		}
		return PbRecipeOutputChecker.isPbOutputCompatible(pbRecipe.value(), outputSlot, secondaryOutputSlot);
	}

	// ===== onUpdateServer 公共逻辑 =====

	/**
	 * 处理 PB 配方并更新整体激活状态 — 抽取自三工厂 onUpdateServer。Task 11: 计数器 O(1) 判断激活状态。
	 * 基于实际能量差计算总消耗,不依赖 getLastUsage。
	 * @param sendUpdatePacket super 返回值;@param energyBeforeSuper super 调用前能量;@param energyContainer 能量容器
	 * @param processes 进程数;@param inputSlots 输入槽;@param pbProcessor PB 处理器;@param context PB 上下文
	 * @param currentActive super 后激活状态;@param setActive 设置激活;@param setLastUsage 设置最近能耗
	 * @return sendUpdatePacket(原样返回)
	 */
	public static boolean processPbRecipesAndUpdate(
			boolean sendUpdatePacket,
			long energyBeforeSuper,
			@NotNull MachineEnergyContainer<?> energyContainer,
			int processes,
			@NotNull List<IInventorySlot> inputSlots,
			@NotNull PbRecipeProcessor pbProcessor,
			@NotNull PbRecipeContext context,
			boolean currentActive,
			@NotNull Consumer<Boolean> setActive,
			@NotNull LongConsumer setLastUsage) {

		// 入口刷新缓存 — 替代原 tryProcessPbRecipe 内部的每次调用刷新
		pbProcessor.refreshFluidTankFullCacheForTick(context);
		pbProcessor.refreshEnergyAndOpsCache(context);
		// 先为本 tick 的不同流体类型各预留一个槽，避免先处理的高产量流体占满所有空槽。
		pbProcessor.reserveActiveFluidOutputTypes(inputSlots);
		// 工厂的 tickMultiplier 属于整台机器而非单个进程。
		// 必须在循环外消费一次，再为每个进程注入同一倍率；否则第一个输入槽
		// 在 PbRecipeProcessor 内部重置共享字段，后续输入槽只能按 1x 处理。
		int batchMultiplier = pbProcessor.consumeTickMultiplier();
		// PB配方独立处理 — 只处理非SMELTING配方且输入不为空的进程
		int processStart = pbProcessor.getAndAdvanceProcessStart(processes);
		for (int processOffset = 0; processOffset < processes; processOffset++) {
			int i = (processStart + processOffset) % processes;
			ItemStack input = inputSlots.get(i).getStack();
			if (input.isEmpty()) {
				// 空输入：重置缓存并跳过
				pbProcessor.resetSmeltingCache(i);
				// 修复：空输入时必须重置 PB 状态（pbOperatingTicks/pbProcessing/cachedPbRecipes）
				// 否则进度条残留、配方缓存残留导致切换异常（与基础机器 MekCentrifugeTickHandler 对齐）
				pbProcessor.resetPbState(i);
				// Task 11: 空输入确保 PB 进程失活（状态守卫防重复，super 已重置 activeStates）
				context.productivebeesgenesis$onProcessDeactivated(i);
				continue;
			}
			// PB配方短路 — 万象创世蜜脾/蜜脾块或有PB离心配方的物品跳过 SMELTING 检查
			// 原因：modularbees 为 c:honeycombs tag 注册了熔炼配方，导致所有 PB 蜜脾被 hasSmeltingRecipe 误判
			if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
					|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)
					|| pbProcessor.findPbRecipe(input) != null) {
				pbProcessor.setTickMultiplier(batchMultiplier);
				if (pbProcessor.tryProcessPbRecipe(i)) {
					context.productivebeesgenesis$onProcessActivated(i);
					context.setPbActiveState(true, i);
				} else {
					context.productivebeesgenesis$onProcessDeactivated(i);
				}
				continue;
			}
			// 缓存SMELTING配方检查结果，输入变更时才重新查询
			if (pbProcessor.hasSmeltingRecipe(i, input)) {
				// SMELTING配方由super处理，跳过PB路径
				// 修复：SMELTING 命中时必须重置 PB 状态，否则进度条显示旧 PB 进度而非 SMELTING 进度
				// （与基础机器 MekCentrifugeTickHandler 对齐）
				pbProcessor.resetPbState(i);
				// Task 11: SMELTING 配方占用，PB 进程失活
				context.productivebeesgenesis$onProcessDeactivated(i);
				continue;
			}
			pbProcessor.setTickMultiplier(batchMultiplier);
			if (pbProcessor.tryProcessPbRecipe(i)) {
				// Task 11: PB 进程激活（onProcessActivated 递增计数器；setPbActiveState 内部状态守卫防重复 + setActiveState）
				context.productivebeesgenesis$onProcessActivated(i);
				context.setPbActiveState(true, i);
			} else {
				// Task 11: PB 处理失败，确保失活（pbProcessor 内部已 setPbActiveState(false)→onProcessDeactivated；此处状态守卫防重复）
				context.productivebeesgenesis$onProcessDeactivated(i);
			}
		}

		// Task 11: 整体激活 = SMELTING 激活（super 后 currentActive）|| PB 激活（计数器 O(1)，替代 O(processes) 遍历）
		boolean isActive = currentActive || context.productivebeesgenesis$hasActiveProcess();
		if (isActive != currentActive) {
			setActive.accept(isActive);
		}

		// 计算总能量消耗（SMELTING + PB），基于实际能量差，不依赖getLastUsage
		// super.onUpdateServer() 可能从能量槽回填能量（EnergyInventorySlot.fillOrConvert），
		// 导致当前能量高于 energyBeforeSuper，差值为负。使用 Math.max(0, ...) 保护，
		// 避免负值传递给 setLastUsage（lastUsage 用于 GUI 显示能耗，负值无意义）
		long totalUsage = Math.max(0, energyBeforeSuper - energyContainer.getEnergy());
		if (totalUsage > 0) {
			setLastUsage.accept(totalUsage);
		}

		// Task 23: 进度同步节流 — 高进程时每 5 tick 同步一次，降低网络包频率 80%
		pbProcessor.tickProgressSync();

		return sendUpdatePacket;
	}

	// ===== 进度获取 =====

	/**
	 * 获取缩放进度 — PB 处理时返回 PB 进度,否则返回 super 进度。
	 * @param i 缩放因子;@param process 进程;@param pbProcessor PB 处理器;@param superProgress super 供应商
	 */
	public static double getScaledProgress(int i, int process,
			@NotNull PbRecipeProcessor pbProcessor,
			@NotNull DoubleSupplier superProgress) {
		if (pbProcessor.isPbProcessing(process)) {
			return pbProcessor.getPbScaledProgress(i, process);
		}
		return superProgress.getAsDouble();
	}

	// ===== 流体输出槽创建 =====

	/**
	 * 创建流体输出槽持有者 — 根据 mekCentrifugeMultiFluidTank 和 isClient 选择模式。
	 * <p>
	 * <b>Task 2/4 根因修复：</b>客户端始终创建 MULTI holder（无视 ModConfig.SERVER），
	 * 避免客户端 SINGLE(1 tank) 与服务端 MULTI(N tanks) 的 DataSlot 数量差异导致 out of bounds。
	 * 通过同步值 {@code isMultiFluidModeSynced} 控制 Tab 是否显示（而非 holder 类型）。
	 * <p>
	 * <b>设计原则（Task 5 修正）：</b>MULTI 模式遵循"一个输入并行一个流体槽"，
	 * 即 maxTanks = {@code processes}。每子槽容量 = {@link Integer#MAX_VALUE}，
	 * 256× 加速下单进程每 tick 约 100 万 mB，单槽可容纳约 2140 tick 产出，避免流体推送瓶颈。
	 *
	 * @param factory 工厂实例
	 * @param listener 监听器
	 * @param processes 进程数(tier.processes)
	 * @param fluidTankMultiplier 容量倍率(MULTI 模式下忽略,每子槽固定 Integer.MAX_VALUE)
	 * @param isClient 是否为客户端 — true 时始终创建 MULTI holder(根因修复)
	 * @param tankSetter SINGLE 下赋值给 fluidOutputTank;MULTI 设置主槽引用
	 * @param tankCountSetter 接收初始槽位数(MULTI=maxTanks,SINGLE=1),构造时写入实例字段避免 Tab 窗口过窄
	 * @return IFluidTankHolder
	 */
	@NotNull
	public static IFluidTankHolder createFluidOutputHolder(
			@NotNull ISideConfiguration factory,
			@NotNull IContentsListener listener,
			int processes,
			@NotNull IntSupplier fluidTankMultiplier,
			boolean isClient,
			@NotNull Consumer<IExtendedFluidTank> tankSetter,
			@NotNull Consumer<Integer> tankCountSetter) {
		// Task 12: ModConfig.SERVER 未加载时(客户端构造期间)默认创建 MULTI,匹配服务端可能的 MULTI
		boolean configAvailable = false;
		boolean multiFluidEnabled = false;
		int maxTanksPerFluidConfig = 0; // v2.1.0: 默认自动计算
		try {
			multiFluidEnabled = ModConfig.SERVER.mekCentrifugeMultiFluidTank.get();
			maxTanksPerFluidConfig = ModConfig.SERVER.mekCentrifugeMaxTanksPerFluid.get();
			configAvailable = true;
		} catch (NullPointerException e) {
			DevLog.warn("fluid_tank", "createFluidOutputHolder 调用时 ModConfig.SERVER 未加载(潜在问题 9)");
		}
		// Task 2/4: 客户端始终创建 MULTI holder,服务端根据配置决定
		// !configAvailable(NPE)也创建 MULTI — 客户端构造期间 ModConfig.SERVER 未加载,需匹配服务端可能的 MULTI
		boolean createMulti = isClient || multiFluidEnabled || !configAvailable;
		if (createMulti) {
			// 设计：一个输入并行一个流体槽（maxTanks = processes）
			// 每子槽容量 = Integer.MAX_VALUE，256× 加速下单进程每 tick 约 100 万 mB，可容纳 2140 tick 产出
			int maxTanks = processes;
			int tankCapacity = Integer.MAX_VALUE;
			// v2.1.0: 传入 maxTanksPerFluidConfig（0=自动计算 maxTanks/2），由 Holder 构造时解析
			MultiFluidTankHolder multiHolder = new MultiFluidTankHolder(maxTanks, tankCapacity, listener, maxTanksPerFluidConfig);
			// Task 2: 调用 tankSetter 设置主槽引用,修复 fluidOutputTank 字段为 null 的核心 bug
			// Task 5: 构造时已预分配全部槽位,getTanks().get(0) 返回预分配的第 0 个槽
			tankSetter.accept(multiHolder.getTanks().get(0));
			// Task 1: 构造时即将 fluidOutputTankCount 设为 maxTanks,避免客户端 Tab 窗口基于默认值 1 计算过窄
			tankCountSetter.accept(maxTanks);
			return multiHolder;
		}
		// SINGLE 模式:保持原逻辑(单槽共享,容量随进程数和 tier 倍率缩放)
		FluidTankHelper helper = FluidTankHelper.forSideWithConfig(factory);
		long baseCapacity = readFluidTankCapacitySafely();
		long multiplier = fluidTankMultiplier.getAsInt();
		int capacity = (int) Math.min(baseCapacity * processes * multiplier, Integer.MAX_VALUE);
		IExtendedFluidTank tank = BasicFluidTank.output(capacity, listener);
		tankSetter.accept(tank);
		helper.addTank(tank);
		// Task 1: SINGLE 模式槽位数固定为 1,构造时即写入实例字段
		tankCountSetter.accept(1);
		return helper.build();
	}

	/**
	 * 安全读取流体槽容量配置 — ModConfig.SERVER 未加载时返回默认值 256000
	 * <br/>
	 * 客户端 Container 构造期间 ModConfig.SERVER 可能未加载,直接读取会抛 NPE。
	 * 使用默认值不影响功能:客户端容量仅用于显示,实际流体数据通过 NBT 同步恢复。
	 */
	private static long readFluidTankCapacitySafely() {
		try {
			return ModConfig.SERVER.mekCentrifugeFluidTankCapacity.get();
		} catch (NullPointerException e) {
			return 256000L;
		}
	}

	// ===== 构造函数公共逻辑 =====

	/**
	 * 注册副输出槽2并配置工厂 IO 与弹出器 — 抽取自三工厂构造函数。
	 * 任务:1. tertiaryOutputSlots 加入 outputSlots;2. setupItemIOConfig 注册 OUTPUT;
	 * 3. 配置流体输出侧面(右侧);4. 重写 ejectorComponent 添加 FLUID 弹出。
	 * Task 6 多槽动态弹出:MULTI_PER_FLUID 通过 IProxiedSlotInfo.FluidProxy 包装 MultiFluidTankHolder,
	 * 让 Ejector 通过 IFluidTankHolder.getTanks(side) 动态获取槽列表(包括后创建的槽),而非静态 List 副本。
	 * SINGLE 传 primaryFluidOutputTank,行为与原版一致。
	 * @param factory 工厂实例;@param configComponent 配置组件;@param inputSlots 输入槽;@param outputSlots 输出槽(会被追加)
	 * @param tertiaryOutputSlots 副输出槽2;@param processes 进程数;@param energySlot 能量槽;@param energyContainer 能量容器
	 * @param fluidOutputHolder 流体持有者;@param primaryFluidOutputTank 主流体槽(SINGLE 用);@param fluidEjectRate 弹出速率
	 * @return TileComponentEjector
	 */
	@NotNull
	public static TileComponentEjector setupTertiarySlotsAndIO(
			@NotNull TileEntityMekanism factory,
			@NotNull TileComponentConfig configComponent,
			@NotNull List<IInventorySlot> inputSlots,
			@NotNull List<IInventorySlot> outputSlots,
			@NotNull IInventorySlot[] tertiaryOutputSlots,
			int processes,
			@NotNull EnergyInventorySlot energySlot,
			@NotNull MachineEnergyContainer<?> energyContainer,
			@Nullable IFluidTankHolder fluidOutputHolder,
			@Nullable IExtendedFluidTank primaryFluidOutputTank,
			@NotNull IntSupplier fluidEjectRate) {
		// 将副输出槽2加入outputSlots列表，使其参与侧面配置和弹出器
		for (int i = 0; i < processes; i++) {
			outputSlots.add(tertiaryOutputSlots[i]);
		}
		// 重新调用setupItemIOConfig，将tertiaryOutputSlots注册到OUTPUT DataType
		configComponent.setupItemIOConfig(inputSlots, outputSlots, energySlot, false);
		configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
		// Task 6: 配置流体输出侧面（右侧）— 区分 MULTI_PER_FLUID 和 SINGLE 模式
		// MULTI_PER_FLUID 模式：使用 IProxiedSlotInfo.FluidProxy 包装 MultiFluidTankHolder,
		//   Ejector 通过 IProxiedSlotInfo.FluidProxy.getTanks() -> multiHolder.getTanks() 动态获取槽列表
		//   语义等价于 "Ejector 通过 IFluidTankHolder.getTanks(side) 动态获取槽列表"
		//   直接调用 config.addSlotInfo 绕过 createInfo 的 List 强转(因为 MultiFluidTankHolder 不是 List)
		// SINGLE 模式：传入单个槽,通过 setupOutputConfig 走原版路径
		setupFluidOutputConfig(configComponent, fluidOutputHolder, primaryFluidOutputTank);
		// 重写ejectorComponent添加FLUID弹出（父类TileEntityFactory只配置了ITEM）
		// 使用自定义流体弹出速率，并把物品弹出 tickDelay 设为 1 tick
		// 注：chemicalAutoEjectRate 在此作为物品弹出速率参数，与 Mekanism 原版 TileEntityFactory 一致
		TileComponentEjector ejector = new TileComponentEjector(factory, MekanismConfig.general.chemicalAutoEjectRate, fluidEjectRate);
		((TileEntityEjectorAccessor) ejector).productivebeesgenesis$setTickDelay(1);
		ejector.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
		return ejector;
	}

	/**
	 * Task 6: 配置流体输出侧面 — MULTI_PER_FLUID 用 IProxiedSlotInfo.FluidProxy,SINGLE 用 setupOutputConfig
	 * <br/>
	 * <b>Ejector 弹出原理：</b>Ejector 通过 {@code FluidSlotInfo.getTanks()} 获取槽列表遍历弹出。
	 * <ul>
	 *   <li>MULTI_PER_FLUID 模式：使用 {@link IProxiedSlotInfo.FluidProxy} 包装 multiHolder::getTanks,
	 *       Ejector 每次弹出调用 FluidProxy.getTanks() 动态获取当前所有槽(包括后创建的槽),
	 *       而非静态 List 副本(原方案问题:后创建的槽不会出现在 Ejector 弹出列表中)。</li>
	 *   <li>SINGLE 模式：通过 setupOutputConfig 走原版路径,行为不变。</li>
	 * </ul>
	 * <p>
	 * <b>绕过 createInfo 的原因：</b>TileComponentConfig.createInfo 对 FLUID 类型强转 List&lt;IExtendedFluidTank&gt;,
	 * 直接传入 MultiFluidTankHolder 实例会 ClassCastException。FluidProxy 继承 FluidSlotInfo,
	 * 可直接通过 config.addSlotInfo 注册,无需 createInfo 中介。
	 * SRP 抽出避免 setupTertiarySlotsAndIO 过长。
	 */
	private static void setupFluidOutputConfig(
			@NotNull TileComponentConfig configComponent,
			@Nullable IFluidTankHolder fluidOutputHolder,
			@Nullable IExtendedFluidTank primaryFluidOutputTank) {
		if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
			// MULTI_PER_FLUID 模式:用 IProxiedSlotInfo.FluidProxy 包装,动态获取槽列表
			// canInput=false, canOutput=true(输出槽语义)
			// supplier 调用 multiHolder.getTanks() 返回当前所有槽的副本(防御性)
			IProxiedSlotInfo fluidProxy = new IProxiedSlotInfo.FluidProxy(false, true, multiHolder::getTanks);
			ConfigInfo fluidConfig = configComponent.getConfig(TransmissionType.FLUID);
			if (fluidConfig != null) {
				fluidConfig.addSlotInfo(DataType.OUTPUT, fluidProxy);
			}
			return;
		}
		// SINGLE 模式:走原版 setupOutputConfig 路径(单个槽包装成 List)
		// primaryFluidOutputTank 可能为 null(构造初期 fallback),由 setupOutputConfig 内部处理
		configComponent.setupOutputConfig(TransmissionType.FLUID, primaryFluidOutputTank, RelativeSide.RIGHT);
	}

	// ===== 输出槽状态标志位管理 =====

	/**
	 * 重新计算输出槽状态标志位 — 遍历所有进程 3 槽,更新 hasOutputItems/outputSlotsFull/perProcess。
	 * 通过 PbRecipeContext 接口访问槽位,不依赖具体 TileEntity。
	 * @param context PB 上下文;@param hasOutputItemsSetter 设 hasOutputItems;@param outputSlotsFullSetter 设全局 full
	 * @param perProcessFullSetter 设每进程 full 数组
	 */
	public static void updateOutputSlotFlags(
			@NotNull PbRecipeContext context,
			@NotNull Consumer<Boolean> hasOutputItemsSetter,
			@NotNull Consumer<Boolean> outputSlotsFullSetter,
			@NotNull Consumer<boolean[]> perProcessFullSetter) {
		boolean hasItems = false;
		boolean globalFull = false;
		int processes = context.processes();
		boolean[] perProcessFull = new boolean[processes];
		for (int i = 0; i < processes; i++) {
			IInventorySlot primary = context.primaryOutputSlot(i);
			IInventorySlot secondary = context.secondaryOutputSlot(i);
			IInventorySlot tertiary = context.tertiaryOutputSlot(i);
			if (!primary.getStack().isEmpty()
					|| (secondary != null && !secondary.getStack().isEmpty())
					|| !tertiary.getStack().isEmpty()) {
				hasItems = true;
			}
			boolean processFull = isSlotFull(primary)
					&& (secondary == null || isSlotFull(secondary))
					&& isSlotFull(tertiary);
			perProcessFull[i] = processFull;
			if (processFull) {
				globalFull = true;
			}
		}
		hasOutputItemsSetter.accept(hasItems);
		outputSlotsFullSetter.accept(globalFull);
		perProcessFullSetter.accept(perProcessFull);
	}

	/** 检查单个输出槽是否已满 — 槽位有物品且数量达到上限时返回 true */
	public static boolean isSlotFull(@NotNull IInventorySlot slot) {
		ItemStack stack = slot.getStack();
		return !stack.isEmpty() && stack.getCount() >= slot.getLimit(stack);
	}

	// ===== 激活状态计数器管理 =====
	// 已拆分至 {@link FactoryProcessStateGuard}，遵循单一职责原则
}
