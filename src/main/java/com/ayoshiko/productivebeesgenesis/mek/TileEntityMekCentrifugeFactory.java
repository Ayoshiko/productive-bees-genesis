package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.cache.OneInputCachedRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.api.recipes.outputs.OutputHelper;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.FactoryInputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.ISingleRecipeLookupHandler.ItemRecipeLookupHandler;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.factory.TileEntityItemToItemFactory;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityFactoryAccessor;
import com.ayoshiko.productivebeesgenesis.util.InputOutputCompatibilityCache;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;

/**
 * 工厂版MEK离心机方块实体 — 继承TileEntityItemToItemFactory，复用多进程并行处理。
 * <br/>
 * 双配方路径：SMELTING走Mekanism管线（主输出槽）；PB CentrifugeRecipe独立处理（3输出槽+流体槽）。
 * SMELTING优先于PB。PB逻辑委托给 {@link PbRecipeProcessor}，通过 {@link IFactoryPbDelegateAccess} 提供依赖。
 */
public class TileEntityMekCentrifugeFactory extends TileEntityItemToItemFactory<ItemStackToItemStackRecipe>
		implements ItemRecipeLookupHandler<ItemStackToItemStackRecipe>, IFactoryPbDelegateAccess, IHasEjectorCooldown, IAe2OutputHost {

	/** 副输出槽2 — 每进程第3个物品输出槽（ProcessInfo只支持1个secondary，第3个单独管理） */
	private OutputInventorySlot[] tertiaryOutputSlots;

	/** 流体输出槽 — 共享，接收PB配方的流体输出 */
	private IExtendedFluidTank fluidOutputTank;

	/** PB配方处理器 — 封装所有PB离心配方处理逻辑 */
	private final PbRecipeProcessor pbProcessor;

	/**
	 * AE2 输出状态持有者 — 封装网格节点、AEItemKey 缓存和待连接标志
	 * <br/>
	 * 消除三个工厂类的 AE2 字段/方法重复，通过 {@link IAe2OutputHost} 接口的
	 * default 方法委托访问。
	 */
	private final Ae2OutputStateHolder productivebeesgenesis$ae2StateHolder = new Ae2OutputStateHolder();

	/**
	 * Task 10: 工厂 PB 上下文委托 — 封装 Task 5/7/11/16 公共状态和方法
	 * <br/>
	 * 三个工厂继承不同 Mekanism 父类，无法通过继承抽取公共逻辑，
	 * 采用组合模式委托给 {@link FactoryPbContextDelegate}，消除约 100 行重复代码。
	 */
	private FactoryPbContextDelegate delegate;

	// ===== Task 12: SFM/AE2 高频探测缓存（避免每 tick 反复查配方和 hashItemAndComponents） =====
	/** 输入槽有效性校验缓存（isItemValidForSlot / isValidInputItem） */
	private final InputValidationCache validInputCache = new InputValidationCache();
	/** 输入-输出兼容性校验缓存（inputProducesOutput） */
	private final InputOutputCompatibilityCache inputProducesOutputCache = new InputOutputCompatibilityCache();

	public TileEntityMekCentrifugeFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state, MekCentrifugeFactoryHelper.TRACKED_ERROR_TYPES, MekCentrifugeFactoryHelper.GLOBAL_ERROR_TYPES);
		// 初始化PB配方处理器（tier在super()中已通过presetVariables设置）
		pbProcessor = new PbRecipeProcessor(this, "工厂离心机");
		// delegate 在 addSlots() 中初始化（此时 tier 和 this 都可用）

		// energySlot是TileEntityFactory的包私有字段，通过Accessor Mixin访问
		// 副输出槽2注册、IO配置、流体侧面配置、FLUID弹出器由Helper统一处理
		EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		ejectorComponent = MekCentrifugeFactoryHelper.setupTertiarySlotsAndIO(
				this, configComponent, inputSlots, outputSlots, tertiaryOutputSlots,
				tier.processes, energySlot, energyContainer, fluidOutputTank,
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
	}

	/** 重写getInitialInventory — 调整energySlot位置（原版4等级保持(7,13)，EM高等级复刻EM原版公式） */
	@NotNull
	@Override
	protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
		InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this);
		TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) this;
		addSlots(builder, listener, () -> {
			listener.onContentsChanged();
			accessor.productivebeesgenesis$setSortingNeeded(true);
		});
		// 计算energySlot坐标：原版4等级保持默认(7,13)；EM高等级使用EM原版公式
		int energySlotX = FactoryLayoutHelper.getFactoryEnergySlotX(tier);
		int energySlotY = FactoryLayoutHelper.getFactoryEnergySlotY(tier);
		EnergyInventorySlot energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener, energySlotX, energySlotY);
		accessor.productivebeesgenesis$setEnergySlot(energySlot);
		builder.addSlot(energySlot);
		return builder.build();
	}

	/** 重写addSlots — 每进程3个输出槽（y=57/77/97），副输出槽2用单独数组管理。布局通过 FactoryLayoutHelper 计算。 */
	@Override
	protected void addSlots(InventorySlotHelper builder, IContentsListener listener, IContentsListener updateSortingListener) {
		inputHandlers = new IInputHandler[tier.processes];
		outputHandlers = new IOutputHandler[tier.processes];
		processInfoSlots = new ProcessInfo[tier.processes];
		tertiaryOutputSlots = new OutputInventorySlot[tier.processes];
		// 初始化委托：创建实例 + 设置排序监听器 + unpause 回调
		delegate = FactoryPbContextDelegate.create(this, updateSortingListener, recipeCacheLookupMonitors);

		// 通过FactoryLayoutHelper动态计算布局参数，支持原版4等级与EM扩展高等级
		int baseX = FactoryLayoutHelper.getBaseX(tier);
		int baseXMult = FactoryLayoutHelper.getBaseXMult(tier);

		for (int i = 0; i < tier.processes; i++) {
			int xPos = baseX + (i * baseXMult);
			var lookupMonitor = recipeCacheLookupMonitors[i];
			// Task 10: 通过委托创建输出槽监听器（封装标志位更新、版本号递增、去抖排序/unpause）
			IContentsListener updateSortingAndUnpause = delegate.createOutputSlotListener(i);

			// 主输出槽
			OutputInventorySlot outputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 57);
			// 副输出槽1
			OutputInventorySlot secondaryOutputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 77);
			// 副输出槽2
			OutputInventorySlot tertiaryOutputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 97);
			tertiaryOutputSlots[i] = tertiaryOutputSlot;

			// 输入槽（传入主输出和副输出1用于inputProducesOutput检查）
			FactoryInputInventorySlot inputSlot = FactoryInputInventorySlot.create(this, i, outputSlot, secondaryOutputSlot, lookupMonitor, xPos, 13);

			int index = i;
			builder.addSlot(inputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE, getWarningCheck(RecipeError.NOT_ENOUGH_INPUT, index)));
			builder.addSlot(outputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT, getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE, index)));
			builder.addSlot(secondaryOutputSlot);
			builder.addSlot(tertiaryOutputSlot);

			// SMELTING配方只使用主输出槽
			inputHandlers[i] = InputHelper.getInputHandler(inputSlot, RecipeError.NOT_ENOUGH_INPUT);
			outputHandlers[i] = OutputHelper.getOutputHandler(outputSlot, RecipeError.NOT_ENOUGH_OUTPUT_SPACE);
			processInfoSlots[i] = new ProcessInfo(i, inputSlot, outputSlot, secondaryOutputSlot);
		}
	}

	/** 重写getInitialFluidTanks — 添加共享流体输出槽，容量随tier.processes缩放。 */
	@Nullable
	@Override
	protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
		// 容量 = 基础容量 * 进程数，与Mekanism原版化学工厂化学槽容量计算方式一致
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
	 * <br/>
	 * 输入物品只要有任一配方即可放入输入槽。客户端（level为null）返回false避免NPE：
	 * InputValidationCache 在 level==null 时直接调用 validator，会将 null 传入
	 * getInputValidationResult → containsSmeltingInput → containsInput(level,...) 导致 NPE。
	 */
	@Override
	public boolean isValidInputItem(@NotNull ItemStack stack) {
		if (level == null) return false;
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
	 * <p>
	 * 此方法在两处被调用：
	 * 1. sortInventory() → addEmptySlotsAsTargets() — 排序时决定空进程能否接收物品
	 * 2. FactoryInputInventorySlot构造函数 — 验证物品能否插入输入槽
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
	 * 原版/EM工厂方块有AttributeFactoryType属性，ME/EME工厂方块有EMExtraAttributeFactoryType属性，
	 * MekanismUtils.isSameTypeFactory()无法识别ME/EME方块，导致配置卡粘贴失败。
	 * 此方法增加对AttributeFactoryType和EMExtraAttributeFactoryType的兼容检查，
	 * 允许同类型（如SMELTING）的工厂跨等级粘贴配置。
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
	 * <p>
	 * Task 3-5：tick 末尾尝试将输出槽物品推送到 AE2 网络（绕过 SFM 中介）。
	 */
	@Override
	protected boolean onUpdateServer() {
		// 延迟连接 AE2 网格节点（避免在 clearRemoved 中连接导致递归栈溢出）
		if (productivebeesgenesis$ae2StateHolder.isAe2NodePending()) {
			productivebeesgenesis$ae2StateHolder.setAe2NodePending(false);
			Ae2GridNodeManager.connectNode(this);
		}
		// Task 7: 重置去抖标志，允许本 tick 重新标记 sortingNeeded（在 super 与 PB 处理前重置）
		delegate.resetSortingMark();
		// super前保存能量，由Helper基于能量差计算总消耗（SMELTING + PB）
		TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) this;
		long energyBeforeSuper = energyContainer.getEnergy();
		boolean sendUpdatePacket = super.onUpdateServer();
		boolean result = MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(
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
		Ae2OutputPusher.pushOutputs(this);
		return result;
	}

	/**
	 * 重写getScaledProgress — PB处理时返回PB进度
	 * <br/>
	 * PB配方处理时progress[]不被Mekanism管线更新，需要用pbOperatingTicks计算进度。
	 * 使用同步的pbProcessingTime避免客户端重新计算（客户端无法访问升级组件）。
	 */
	@Override
	public double getScaledProgress(int i, int process) {
		return MekCentrifugeFactoryHelper.getScaledProgress(i, process, pbProcessor, () -> super.getScaledProgress(i, process));
	}

	/**
	 * 同步PB进度到客户端
	 * <br/>
	 * 每进程的pbOperatingTicks、pbProcessing和pbProcessingTime需要同步给客户端用于GUI显示。
	 */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		super.addContainerTrackers(container);
		pbProcessor.addContainerTrackers(container);
	}

	/**
	 * 持久化PB配方处理进度
	 * <br/>
	 * 防止重启后PB处理进度丢失。Task 3-5：同时持久化 AE2 网格节点状态。
	 */
	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		pbProcessor.saveAdditional(nbt);
		Ae2GridNodeManager.saveNodeNBT(this, nbt);
	}

	/** 加载PB配方处理进度。Task 3-5：同时加载 AE2 网格节点。 */
	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		pbProcessor.loadAdditional(nbt);
		Ae2GridNodeManager.loadNodeNBT(this, nbt);
	}

	// ===== Task 3-5: AE2 网格节点生命周期与 IAe2OutputHost 实现 =====

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		// 仅准备节点（不接入网格），避免区块加载时 AE2 连接扫描递归栈溢出
		Ae2GridNodeManager.prepareNode(this);
		productivebeesgenesis$ae2StateHolder.setAe2NodePending(true);
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		Ae2GridNodeManager.destroyNode(this);
		productivebeesgenesis$ae2StateHolder.clear();
	}

	@Override
	public void onChunkUnloaded() {
		super.onChunkUnloaded();
		Ae2GridNodeManager.destroyNode(this);
		productivebeesgenesis$ae2StateHolder.clear();
	}

	/** 获取 AE2 状态持有者 — 供 IAe2OutputHost default 方法委托使用 */
	@Override
	public Ae2OutputStateHolder productivebeesgenesis$getAe2StateHolder() {
		return productivebeesgenesis$ae2StateHolder;
	}

	@Override
	public MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource() {
		return energyContainer;
	}

	@Override
	public Level productivebeesgenesis$getAe2Level() {
		return level;
	}

	@Override
	public BlockPos productivebeesgenesis$getAe2BlockPos() {
		return getBlockPos();
	}

	@NotNull
	@Override
	public MachineUpgradeData getUpgradeData(HolderLookup.Provider provider) {
		EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		return new MachineUpgradeData(provider, redstone, getControlType(),
				getEnergyContainer(), progress, energySlot, inputSlots, outputSlots,
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

	/** 获取PB上下文委托 — 供 IFactoryPbDelegateAccess 默认方法转发使用 */
	@Override
	public FactoryPbContextDelegate productivebeesgenesis$getDelegate() {
		return delegate;
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
