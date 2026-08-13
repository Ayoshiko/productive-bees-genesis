package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.RecipeCacheLookupMonitorAccessor;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.interfaces.ISideConfiguration;
import mekanism.common.util.InventoryUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.TriPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;

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
		int remainingInputLanes = 0;
		for (int i = 0; i < processes; i++) {
			if (!inputSlots.get(i).getStack().isEmpty()) remainingInputLanes++;
		}
		// Remaining PB energy budget for this tick; debit only actual consumption so
		// a shared buffer cannot be over-committed across parallel lanes.
		long remainingEnergy = Math.max(0L, energyContainer.getEnergy());
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
			long processEnergyBudget = PbProcessFairness.energyBudget(
					remainingEnergy, remainingInputLanes);
			remainingInputLanes--;
			boolean isMyriad = MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
					|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
			RecipeHolder<CentrifugeRecipe> preFoundRecipe = isMyriad ? null : pbProcessor.findPbRecipe(input);
			if (isMyriad || preFoundRecipe != null) {
				pbProcessor.setTickMultiplier(batchMultiplier);
				long energyBeforeProcess = energyContainer.getEnergy();
				if (pbProcessor.tryProcessPbRecipe(i, preFoundRecipe, processEnergyBudget)) {
					context.productivebeesgenesis$onProcessActivated(i);
					context.setPbActiveState(true, i);
				} else {
					context.productivebeesgenesis$onProcessDeactivated(i);
				}
				remainingEnergy = Math.max(0L,
						remainingEnergy - Math.max(0L, energyBeforeProcess - energyContainer.getEnergy()));
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
			long energyBeforeProcess = energyContainer.getEnergy();
			if (pbProcessor.tryProcessPbRecipe(i, null, processEnergyBudget)) {
				// Task 11: PB 进程激活（onProcessActivated 递增计数器；setPbActiveState 内部状态守卫防重复 + setActiveState）
				context.productivebeesgenesis$onProcessActivated(i);
				context.setPbActiveState(true, i);
			} else {
				// Task 11: PB 处理失败，确保失活（pbProcessor 内部已 setPbActiveState(false)→onProcessDeactivated；此处状态守卫防重复）
				context.productivebeesgenesis$onProcessDeactivated(i);
			}
			remainingEnergy = Math.max(0L,
					remainingEnergy - Math.max(0L, energyBeforeProcess - energyContainer.getEnergy()));
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

	// ===== fluid output holder creation (implementation moved to MekCentrifugeIoConfigHelper) =====

	/**
	 * Creates the fluid output holder; implementation moved to
	 * {@link MekCentrifugeIoConfigHelper#createFluidOutputHolder}.
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
		return MekCentrifugeIoConfigHelper.createFluidOutputHolder(
				factory, listener, processes, fluidTankMultiplier, isClient, tankSetter, tankCountSetter);
	}

	// ===== tertiary output slots & IO config (implementation moved to MekCentrifugeIoConfigHelper) =====

	/**
	 * Configures tertiary output slots and IO; implementation moved to
	 * {@link MekCentrifugeIoConfigHelper#setupTertiarySlotsAndIO}.
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
		return MekCentrifugeIoConfigHelper.setupTertiarySlotsAndIO(
				factory, configComponent, inputSlots, outputSlots, tertiaryOutputSlots, processes,
				energySlot, energyContainer, fluidOutputHolder, primaryFluidOutputTank, fluidEjectRate);
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
