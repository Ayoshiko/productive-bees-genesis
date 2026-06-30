package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.cache.OneInputCachedRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.ISingleRecipeLookupHandler.ItemRecipeLookupHandler;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.recipe.lookup.monitor.FactoryRecipeCacheLookupMonitor;
import mekanism.common.upgrade.MachineUpgradeData;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityExtraFactoryAccessor;
import com.ayoshiko.productivebeesgenesis.util.InputOutputCompatibilityCache;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import com.jerry.mekextras.common.inventory.slot.ExtraFactoryInputInventorySlot;
import com.jerry.mekextras.common.inventory.slot.ExtraFactoryOutputInventorySlot;
import com.jerry.mekextras.common.tile.factory.TileEntityExtraItemStackToItemStackFactory;
import com.jerry.mekextras.api.recipes.outputs.ExtraOutputHelper;

/**
 * ME扩展版MEK离心机工厂方块实体
 * <br/>
 * 继承Mekanism Extras的TileEntityExtraItemStackToItemStackFactory，
 * 复用ME的多进程并行处理、排序、升级体系（支持ABSOLUTE/SUPREME/COSMIC/INFINITE等级）。
 * <p>
 * 双配方处理路径（与原版工厂一致）：
 * <ul>
 *   <li>SMELTING配方：走Mekanism标准CachedRecipe管线，输出到主输出槽</li>
 *   <li>PB CentrifugeRecipe：独立处理（不走CachedRecipe管线），支持概率多物品输出+流体输出</li>
 * </ul>
 * 每进程有3个输出槽（主输出+副输出1+副输出2），共享1个流体输出槽（容量随tier.processes缩放）。
 * SMELTING配方优先于PB配方（同一输入若有SMELTING配方则走SMELTING路径）。
 * <p>
 * PB配方处理逻辑委托给 {@link PbRecipeProcessor}，通过实现 {@link PbRecipeContext} 提供依赖。
 */
public class TileEntityExtraMekCentrifugeFactory extends TileEntityExtraItemStackToItemStackFactory
		implements ItemRecipeLookupHandler<ItemStackToItemStackRecipe>, PbRecipeContext, IMekCentrifugeTile, IHasEjectorCooldown {

	/** 副输出槽2 — 每进程第3个物品输出槽（ProcessInfo只支持1个secondary，第3个单独管理） */
	private ExtraFactoryOutputInventorySlot[] tertiaryOutputSlots;

	/** 流体输出槽 — 共享，接收PB配方的流体输出 */
	private IExtendedFluidTank fluidOutputTank;

	/** PB配方处理器 — 封装所有PB离心配方处理逻辑 */
	private final PbRecipeProcessor pbProcessor;

	// ===== Task 5: 输出槽状态标志位（批量/增量管理，避免每次 insertItem 全量扫描） =====
	/** 输出槽标志位管理器 */
	private final OutputSlotFlagManager outputSlotFlagManager;
	/** addSlots 中传入的排序监听器，批量输出结束时需要触发一次 */
	private IContentsListener updateSortingListener;

	// ===== Task 16: 输出槽内容版本号（输出槽内容变更时递增） =====
	private volatile long outputContentsVersion = 0L;

	// ===== Task 7: sortInventory 去抖（同 tick 内只标记一次 sortingNeeded） =====
	private volatile boolean sortingMarkedThisTick = false;

	// ===== Task 11: 激活状态计数器（O(1) 判断整体激活，替代 O(processes) 遍历） =====
	private final AtomicInteger activeProcessCount = new AtomicInteger(0);
	/** 每进程 PB 激活状态跟踪（CAS 防重复计数；tier 在 super() 中已设置） */
	private final boolean[] pbActiveStates = new boolean[tier.processes];

	// ===== Task 12: SFM/AE2 高频探测缓存（避免每 tick 反复查配方和 hashItemAndComponents） =====
	/** 输入槽有效性校验缓存（isItemValidForSlot / isValidInputItem） */
	private final InputValidationCache validInputCache = new InputValidationCache();
	/** 输入-输出兼容性校验缓存（inputProducesOutput） */
	private final InputOutputCompatibilityCache inputProducesOutputCache = new InputOutputCompatibilityCache();

	public TileEntityExtraMekCentrifugeFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state);
		// 初始化PB配方处理器（tier在super()中已通过presetVariables设置）
		pbProcessor = new PbRecipeProcessor(this, "ME工厂离心机");
		// 初始化输出槽标志位管理器（tier在super()中已设置）
		outputSlotFlagManager = new OutputSlotFlagManager(this);

		// energySlot通过Accessor Mixin访问，副输出槽2注册、IO配置、流体侧面配置、FLUID弹出器由Helper统一处理
		EnergyInventorySlot energySlot = ((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		ejectorComponent = MekCentrifugeFactoryHelper.setupTertiarySlotsAndIO(
				this, configComponent, inputSlots, outputSlots, tertiaryOutputSlots,
				tier.processes, energySlot, energyContainer, fluidOutputTank,
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
	}

	/**
	 * 重写addSlots — 每进程添加3个输出槽
	 * <br/>
	 * 使用ME的ExtraFactoryInputInventorySlot和ExtraFactoryOutputInventorySlot。
	 * 主输出槽(y=57) + 副输出槽1(y=77) + 副输出槽2(y=97)。
	 * ProcessInfo的secondaryOutputSlot设为副输出槽1，副输出槽2用单独数组管理。
	 * 布局参数：baseX=27, baseXMult=19（与ME原版一致）。
	 */
	@Override
	protected void addSlots(InventorySlotHelper builder, IContentsListener listener, IContentsListener updateSortingListener) {
		inputHandlers = new IInputHandler[tier.processes];
		outputHandlers = new IOutputHandler[tier.processes];
		processInfoSlots = new ProcessInfo[tier.processes];
		tertiaryOutputSlots = new ExtraFactoryOutputInventorySlot[tier.processes];
		this.updateSortingListener = updateSortingListener;

		int baseX = 27;
		int baseXMult = 19;

		for (int i = 0; i < tier.processes; i++) {
			int xPos = baseX + (i * baseXMult);
			@SuppressWarnings("unchecked")
			FactoryRecipeCacheLookupMonitor<ItemStackToItemStackRecipe> lookupMonitor =
					(FactoryRecipeCacheLookupMonitor<ItemStackToItemStackRecipe>) recipeCacheLookupMonitors[i];
			IContentsListener updateSortingAndUnpause = () -> {
				// Task 5: 输出槽变更时更新标志位；批量模式下只标记 dirty，避免每次 insertItem 全量扫描
				outputSlotFlagManager.onSlotChanged();
				// Task 16: 输出槽内容变化时递增版本号，通知 Ejector Mixin 需要重新尝试输出
				outputContentsVersion++;
				// Task 7: 同 tick 内去抖，避免 AE2 高频拉取触发全量排序扫描
				if (!sortingMarkedThisTick) {
					sortingMarkedThisTick = true;
					updateSortingListener.onContentsChanged();
					lookupMonitor.unpause();
				}
			};

			// 主输出槽
			ExtraFactoryOutputInventorySlot outputSlot = ExtraFactoryOutputInventorySlot.at(this, updateSortingAndUnpause, xPos, 57);
			// 副输出槽1
			ExtraFactoryOutputInventorySlot secondaryOutputSlot = ExtraFactoryOutputInventorySlot.at(this, updateSortingAndUnpause, xPos, 77);
			// 副输出槽2
			ExtraFactoryOutputInventorySlot tertiaryOutputSlot = ExtraFactoryOutputInventorySlot.at(this, updateSortingAndUnpause, xPos, 97);
			tertiaryOutputSlots[i] = tertiaryOutputSlot;

			// 输入槽（传入主输出和副输出1用于inputProducesOutput检查）
			ExtraFactoryInputInventorySlot inputSlot = ExtraFactoryInputInventorySlot.create(
					this, i, outputSlot, secondaryOutputSlot, lookupMonitor, xPos, 13);

			int index = i;
			builder.addSlot(inputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE, getWarningCheck(RecipeError.NOT_ENOUGH_INPUT, index)));
			builder.addSlot(outputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT, getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE, index)));
			builder.addSlot(secondaryOutputSlot);
			builder.addSlot(tertiaryOutputSlot);

			// SMELTING配方只使用主输出槽
			inputHandlers[i] = InputHelper.getInputHandler(inputSlot, RecipeError.NOT_ENOUGH_INPUT);
			outputHandlers[i] = ExtraOutputHelper.getOutputHandler(outputSlot, RecipeError.NOT_ENOUGH_OUTPUT_SPACE, this::getOperationsPerTick);
			processInfoSlots[i] = new ProcessInfo(i, inputSlot, outputSlot, secondaryOutputSlot);
		}
	}

	/**
	 * 重写getInitialFluidTanks — 添加共享流体输出槽
	 * <br/>
	 * ME基类默认无流体槽，重写此方法添加输出槽。
	 * 容量随tier.processes缩放。
	 */
	@Nullable
	@Override
	protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
		return MekCentrifugeFactoryHelper.createFluidOutputHolder(this, listener, tier.processes, t -> fluidOutputTank = t);
	}

	/**
	 * 重写isItemValidForSlot — 同时查找SMELTING和PB CentrifugeRecipe
	 * <br/>
	 * 父类默认无条件返回true，导致任意物品都能插入输入槽（如配置卡、红石等）。
	 * 委托给 {@link MekCentrifugeFactoryHelper#getInputValidationResult} 验证物品是否有有效配方，
	 * 缓存完整 {@link com.ayoshiko.productivebeesgenesis.util.InputValidationCache.ValidationResult}，
	 * 供 tryProcessPbRecipe 等路径复用，避免重复 findPbRecipe。
	 * 防止无效物品进入输入槽占用空间。客户端（level为null）返回false避免NPE。
	 */
	@Override
	public boolean isItemValidForSlot(@NotNull ItemStack stack) {
		if (level == null) return false;
		return validInputCache.getResult(level, stack,
				() -> MekCentrifugeFactoryHelper.getInputValidationResult(getRecipeType(), level, stack, pbProcessor)).valid();
	}

	/**
	 * 重写isValidInputItem — 同时查找SMELTING和PB CentrifugeRecipe（带缓存）
	 */
	@Override
	public boolean isValidInputItem(@NotNull ItemStack stack) {
		return validInputCache.getResult(level, stack,
				() -> MekCentrifugeFactoryHelper.getInputValidationResult(getRecipeType(), level, stack, pbProcessor)).valid();
	}

	@Override
	protected int getNeededInput(ItemStackToItemStackRecipe recipe, ItemStack inputStack) {
		return MekCentrifugeFactoryHelper.getNeededInput(recipe, inputStack);
	}

	@Override
	protected boolean isCachedRecipeValid(@Nullable CachedRecipe<ItemStackToItemStackRecipe> cached,
										  @NotNull ItemStack stack) {
		return MekCentrifugeFactoryHelper.isCachedRecipeValid(cached, stack);
	}

	/**
	 * 重写findRecipe — 只查SMELTING配方
	 * <br/>
	 * PB配方不走Mekanism管线，由tryProcessPbRecipe独立处理。
	 */
	@Override
	protected ItemStackToItemStackRecipe findRecipe(int process, @NotNull ItemStack fallbackInput,
													@NotNull IInventorySlot outputSlot,
													@Nullable IInventorySlot secondaryOutputSlot) {
		return MekCentrifugeFactoryHelper.findSmeltingRecipe(getRecipeType(), level, fallbackInput, outputSlot);
	}

	/**
	 * 重写inputProducesOutput — 支持PB配方输出兼容性检查
	 * <br/>
	 * 父类只检查SMELTING配方，PB物品在输出槽非空时被阻止分配到空进程（sortInventory门控）。
	 * 重写后：SMELTING检查失败时回退到PB配方检查，验证PB配方输出与现有输出槽内容兼容。
	 */
	@Override
	public boolean inputProducesOutput(int process, @NotNull ItemStack fallbackInput,
									   @NotNull IInventorySlot outputSlot, @Nullable IInventorySlot secondaryOutputSlot,
									   boolean updateCache) {
		// SFM/AE2 高频调用，20 tick 缓存避免反复查配方和 hashItemAndComponents
		return inputProducesOutputCache.get(level, fallbackInput, outputSlot.getStack(),
				secondaryOutputSlot == null ? ItemStack.EMPTY : secondaryOutputSlot.getStack(),
				() -> super.inputProducesOutput(process, fallbackInput, outputSlot, secondaryOutputSlot, updateCache)
						|| MekCentrifugeFactoryHelper.checkPbOutputFallback(pbProcessor, fallbackInput, outputSlot, secondaryOutputSlot));
	}

	/**
	 * 配置卡兼容性检查
	 * <br/>
	 * ME工厂方块同时有AttributeFactoryType和EMExtraAttributeFactoryType，
	 * MekanismUtils.isSameTypeFactory()无法识别ME/EME方块，导致配置卡粘贴失败。
	 * 此方法增加对AttributeFactoryType和EMExtraAttributeFactoryType的兼容检查，
	 * 允许同类型（如SMELTING）的ME/EME/原版工厂跨等级粘贴配置。
	 */
	@Override
	public boolean isConfigurationDataCompatible(@NotNull Block blockType) {
		return super.isConfigurationDataCompatible(blockType)
				|| MekCompatHooks.isConfigurationDataCompatible(getBlockHolder(), blockType);
	}

	@NotNull
	@Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekCentrifugeFactoryHelper.getSmeltingRecipeType();
	}

	/**
	 * JEI配方查看器跳转支持
	 * <br/>
	 * 返回SMELTING类型，使JEI中点击配方时能正确跳转到熔炼配方类别。
	 * 参考Mekanism原版TileEntityItemStackToItemStackFactory的实现。
	 */
	@NotNull
	@Override
	public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return MekCentrifugeFactoryHelper.getSmeltingRecipeViewerType();
	}

	/**
	 * 重写getRecipe — SMELTING配方查找
	 * <br/>
	 * PB输入物品无SMELTING配方时返回null，Mekanism管线跳过该进程，
	 * 由onUpdateServer中的tryProcessPbRecipe接管。
	 */
	@Nullable
	@Override
	public ItemStackToItemStackRecipe getRecipe(int cacheIndex) {
		return findFirstRecipe(inputHandlers[cacheIndex]);
	}

	@NotNull
	@Override
	public CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(
			@NotNull ItemStackToItemStackRecipe recipe, int cacheIndex) {
		return OneInputCachedRecipe.itemToItem(
				recipe, recheckAllRecipeErrors[cacheIndex],
				inputHandlers[cacheIndex], outputHandlers[cacheIndex])
				.setErrorsChanged(errors -> errorTracker.onErrorsChanged(errors, cacheIndex))
				.setCanHolderFunction(this::canFunction)
				.setActive(active -> setActiveState(active, cacheIndex))
				.setEnergyRequirements(energyContainer::getEnergyPerTick, energyContainer)
				.setRequiredTicks(this::getTicksRequired)
				.setOnFinish(this::markForSave)
				.setBaselineMaxOperations(this::getOperationsPerTick)
				.setOperatingTicksChanged(operatingTicks -> progress[cacheIndex] = operatingTicks);
	}

	/**
	 * 重写onUpdateServer — 先走SMELTING管线，再处理PB配方
	 * <br/>
	 * super.onUpdateServer()处理SMELTING配方（PB输入因getRecipe返回null被跳过），
	 * 并消耗SMELTING能量。之后由Helper统一处理PB配方独立处理、
	 * 激活状态重算和总能量消耗计算（基于super前后能量差，避免SMELTING消耗被双重计算）。
	 * <p>
	 * SMELTING配方检查结果缓存优化（输入变更时才重新查询）由PbRecipeProcessor管理。
	 */
	@Override
	protected boolean onUpdateServer() {
		// Task 7: 重置去抖标志，允许本 tick 重新标记 sortingNeeded（在 super 与 PB 处理前重置）
		sortingMarkedThisTick = false;
		// super前保存能量，由Helper基于能量差计算总消耗（SMELTING + PB）
		TileEntityExtraFactoryAccessor accessor = (TileEntityExtraFactoryAccessor) this;
		long energyBeforeSuper = energyContainer.getEnergy();
		boolean sendUpdatePacket = super.onUpdateServer();
		return MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(
				sendUpdatePacket,
				energyBeforeSuper,
				energyContainer,
				tier.processes,
				inputSlots,
				pbProcessor,
				this,
				getActive(),
				this::setActive,
				v -> accessor.productivebeesgenesis$setLastUsage(v)
		);
	}

	/**
	 * 重写getScaledProgress — PB处理时返回PB进度
	 * <br/>
	 * PB配方处理时progress[]不被Mekanism管线更新，需要用pbOperatingTicks计算进度。
	 */
	@Override
	public double getScaledProgress(int i, int process) {
		return MekCentrifugeFactoryHelper.getScaledProgress(i, process, pbProcessor, () -> super.getScaledProgress(i, process));
	}

	/** 同步PB进度到客户端 */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		super.addContainerTrackers(container);
		pbProcessor.addContainerTrackers(container);
	}

	/** 持久化PB配方处理进度 */
	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		pbProcessor.saveAdditional(nbt);
	}

	/** 加载PB配方处理进度 */
	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		pbProcessor.loadAdditional(nbt);
	}

	@NotNull
	@Override
	public MachineUpgradeData getUpgradeData(HolderLookup.Provider provider) {
		EnergyInventorySlot energySlotAccessor = ((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		return new MachineUpgradeData(provider, redstone, getControlType(),
				getEnergyContainer(), progress, energySlotAccessor, inputSlots, outputSlots,
				isSorting(), getComponents());
	}

	// ===== PbRecipeContext 接口实现 =====

	@Override
	public Level level() {
		return level;
	}

	@Override
	public MachineEnergyContainer<?> energyContainer() {
		return energyContainer;
	}

	@Override
	public IInventorySlot inputSlot(int process) {
		return inputSlots.get(process);
	}

	@Override
	public IInventorySlot primaryOutputSlot(int process) {
		return processInfoSlots[process].outputSlot();
	}

	@Override
	public IInventorySlot secondaryOutputSlot(int process) {
		return processInfoSlots[process].secondaryOutputSlot();
	}

	@Override
	public IInventorySlot tertiaryOutputSlot(int process) {
		return tertiaryOutputSlots[process];
	}

	@Override
	public IExtendedFluidTank fluidOutputTank() {
		return fluidOutputTank;
	}

	@Override
	public int processes() {
		return tier.processes;
	}

	@Override
	public int baseTicksRequired() {
		return BASE_TICKS_REQUIRED;
	}

	@Override
	public void setPbActiveState(boolean active, int process) {
		// Task 11: 通过计数器方法同步维护 activeProcessCount（CAS 防重复计数）
		if (active) {
			productivebeesgenesis$onProcessActivated(process);
		} else {
			productivebeesgenesis$onProcessDeactivated(process);
		}
		setActiveState(active, process);
	}

	@Override
	public int productivityModifier() {
		return 1;
	}

	@Override
	public int operationsPerTick() {
		return MekanismUtils.getOperationsPerTick(this, BASE_TICKS_REQUIRED, 1);
	}

	@Override
	public int getTicksForBase(int baseTime) {
		return MekanismUtils.getTicks(this, baseTime);
	}

	@Override
	public boolean containsSmeltingInput(ItemStack input) {
		return MekCentrifugeFactoryHelper.containsSmeltingInput(getRecipeType(), level, input);
	}

	// ===== Task 5: 输出槽状态标志位实现 =====

	@Override
	public boolean productivebeesgenesis$hasOutputItems() {
		return outputSlotFlagManager.hasOutputItems();
	}

	@Override
	public boolean productivebeesgenesis$outputSlotsFull() {
		return outputSlotFlagManager.outputSlotsFull();
	}

	/** 遍历所有进程的输出槽重新计算标志位（由输出槽 IContentsListener 触发，外部插入/初始化用） */
	@Override
	public void productivebeesgenesis$updateOutputSlotFlags() {
		outputSlotFlagManager.updateAll();
	}

	@Override
	public boolean productivebeesgenesis$outputSlotsFull(int process) {
		return outputSlotFlagManager.outputSlotsFull(process);
	}

	@Override
	public void productivebeesgenesis$beginOutputBatch() {
		outputSlotFlagManager.beginBatch();
	}

	@Override
	public void productivebeesgenesis$endOutputBatch(int process) {
		if (outputSlotFlagManager.endBatch(process)) {
			outputContentsVersion++;
			if (!sortingMarkedThisTick) {
				sortingMarkedThisTick = true;
				if (updateSortingListener != null) {
					updateSortingListener.onContentsChanged();
				}
				if (process >= 0 && process < recipeCacheLookupMonitors.length) {
					recipeCacheLookupMonitors[process].unpause();
				}
			}
		}
	}

	/** Task 16: 返回输出槽内容版本号（供 Ejector Mixin 判断是否跳过 outputItems） */
	@Override
	public long productivebeesgenesis$outputContentsVersion() {
		return outputContentsVersion;
	}

	/** Step 5: 返回所有输出槽物品总数（O(1)，供 Ejector Mixin 替代 countOutputItems 遍历） */
	@Override
	public long productivebeesgenesis$outputItemCount() {
		return outputSlotFlagManager.outputItemCount();
	}

	// ===== Task 11: 激活状态计数器实现 =====

	@Override
	public void productivebeesgenesis$onProcessActivated(int process) {
		MekCentrifugeFactoryHelper.onProcessActivated(process, pbActiveStates, activeProcessCount);
	}

	@Override
	public void productivebeesgenesis$onProcessDeactivated(int process) {
		MekCentrifugeFactoryHelper.onProcessDeactivated(process, pbActiveStates, activeProcessCount);
	}

	@Override
	public boolean productivebeesgenesis$hasActiveProcess() {
		return MekCentrifugeFactoryHelper.hasActiveProcess(activeProcessCount);
	}

	// ===== GUI暴露方法 =====

	/** 获取副输出槽1 — GUI显示用 */
	@Nullable
	public IInventorySlot getSecondaryOutputSlot(int processIndex) {
		return processInfoSlots[processIndex].secondaryOutputSlot();
	}

	/** 获取副输出槽2 — GUI显示用 */
	@NotNull
	public IInventorySlot getTertiaryOutputSlot(int processIndex) {
		return tertiaryOutputSlots[processIndex];
	}

	/** 获取流体输出槽 — GUI显示用 */
	@NotNull
	public IExtendedFluidTank getFluidOutputTank() {
		return fluidOutputTank;
	}
}
