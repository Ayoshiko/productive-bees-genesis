package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeData;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityFactoryAccessor;
import com.ayoshiko.productivebeesgenesis.util.InputOutputCompatibilityCache;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;
import mekanism.api.IContentsListener;
import mekanism.api.SerializationConstants;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.lookup.ISingleRecipeLookupHandler.ItemRecipeLookupHandler;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tile.factory.TileEntityItemToItemFactory;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
	 * 工厂版MEK离心机抽象基类 — 模板方法模式，封装原版工厂公共逻辑。
	 * <br/>
	 * 继承 Mekanism 的 {@link TileEntityItemToItemFactory}，复用多进程并行处理。
	 * 双配方路径：SMELTING走Mekanism管线；PB CentrifugeRecipe独立处理（3输出槽+流体槽）。
	 * 公共逻辑委托给 {@link CentrifugeFactoryCommonLogic}，消除三工厂间代码重复。
	 *
	 * @author ayoshiko
	 * @since Task 21
	 */
public abstract class AbstractMekCentrifugeFactory extends TileEntityItemToItemFactory<ItemStackToItemStackRecipe>
		implements ItemRecipeLookupHandler<ItemStackToItemStackRecipe>, IFactoryPbDelegateAccess, IHasEjectorCooldown,
		IAe2InputHost, IAe2OutputHostBase, IPbUpgradeProvider, IUpgradeableBlockEntity, IMekCentrifugePbUpgradeHost,
		com.ayoshiko.productivebeesgenesis.ICustomDataPersistable, IMultiFluidTankHost {

	@Override
	public void productivebeesgenesis$onSmeltingCompatChanged() {
		AbstractMekCentrifugeFactorySetupHelper.onSmeltingCompatChanged(this, tier.processes, recipeCacheLookupMonitors);
	}

	/** 副输出槽2 — 每进程第3个物品输出槽（ProcessInfo 只支持1个 secondary） */
	protected OutputInventorySlot[] tertiaryOutputSlots;
	/** 流体输出槽 — 共享,接收 PB 配方的流体输出 */
	protected IExtendedFluidTank fluidOutputTank;
	/** Task 9: 流体输出槽持有者 — MULTI_PER_FLUID 持有 MultiFluidTankHolder;SINGLE 持有 FluidTankHelper */
	protected IFluidTankHolder fluidOutputHolder;
	/** Task 8: 客户端同步的流体槽位数;Task 1: 初始值由 tankCountSetter 构造时设置,移除 =1 避免字段初始化覆盖 */
	protected int fluidOutputTankCount;
	/** Task 8: 客户端同步的多流体槽模式状态(由 SyncableBoolean 同步,确保 Tab 显示与服务端一致) */
	protected boolean isMultiFluidModeSynced = false;
	/** Task 6: 孤儿多流体槽 NBT — MULTI→SINGLE 降级时保留的多流体槽数据,saveAdditional 显式写出确保持久化 */
	@Nullable
	private CompoundTag orphanedMultiFluidTanksNbt;
	/** PB 配方处理器 — 封装所有 PB 离心配方处理逻辑 */
	protected final PbRecipeProcessor pbProcessor;
	/** AE2 生命周期处理器 — 封装网格节点、缓存和待连接标志 */
	protected final MekAe2LifecycleHandler productivebeesgenesis$ae2LifecycleHandler = new MekAe2LifecycleHandler();
	/** 工厂 PB 上下文委托 — 封装公共状态和方法（Task 5/7/11/16） */
	protected FactoryPbContextDelegate delegate;
	/** PB 升级委托 — 封装 PB 升级安装/卸载/同步/持久化/倍率计算 */
	protected final FactoryPbUpgradeDelegate pbUpgradeDelegate;
	/** Task 9: GUI 访问委托 — 封装 GUI/Container 查询方法，减少本类行数 */
	private final FactoryGuiAccessor guiAccessor;
	/** 输入槽有效性校验缓存（isItemValidForSlot / isValidInputItem） */
	protected final InputValidationCache validInputCache = new InputValidationCache();
	/** 输入-输出兼容性校验缓存（inputProducesOutput） */
	protected final InputOutputCompatibilityCache inputProducesOutputCache = new InputOutputCompatibilityCache();
	/** Per-tile 批量收获状态 — 工厂变体共用，用于 skipPb 判断 */
	protected final TickBatchSkipState tickBatchSkipState = new TickBatchSkipState();

	/**
	 * TickAccelTracker 懒缓存 — Spark 优化（报告 l5oASjsSuW）
	 * <br/>
	 * 原接口链（getAe2StateHolder → getStateHolder → getTickAccelTracker，3 层
	 * megamorphic 接口分发）在 JDTE 256x 加速下每 gameTick 被调用 256 次，
	 * 是模组内最大热点方法（268ms self）。tracker 是 holder 的 final 字段，
	 * tile 生命周期内引用稳定，首次解析后缓存，后续为单次字段读。
	 */
	private TickAccelTracker productivebeesgenesis$cachedTickAccelTracker;

	@Override
	public TickAccelTracker productivebeesgenesis$getTickAccelTracker() {
		TickAccelTracker tracker = productivebeesgenesis$cachedTickAccelTracker;
		if (tracker != null) {
			return tracker;
		}
		// lifecycleHandler 为构造器初始化的 final 字段，getStateHolder 必非 null；null 时不缓存以便重试
		tracker = productivebeesgenesis$ae2LifecycleHandler.getStateHolder().getTickAccelTracker();
		if (tracker != null) {
			productivebeesgenesis$cachedTickAccelTracker = tracker;
		}
		return tracker;
	}

	/** 构造函数 — 初始化PB处理器、PB升级委托和IO配置 */
	public AbstractMekCentrifugeFactory(Holder<net.minecraft.world.level.block.Block> blockProvider, BlockPos pos,
		BlockState state) {
		super(
			blockProvider,
			pos,
			state,
			MekCentrifugeFactoryHelper.TRACKED_ERROR_TYPES,
			MekCentrifugeFactoryHelper.GLOBAL_ERROR_TYPES
		);
		pbProcessor = new PbRecipeProcessor(this, getPbProcessorName());
		pbUpgradeDelegate = new FactoryPbUpgradeDelegate(this);
		guiAccessor = new FactoryGuiAccessor(this);
		EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		// Task 5/6/13: 传入 fluidOutputHolder/fluidOutputTank,通过 setupFluidOutputConfig 暴露给
		// MEK 侧面配置 GUI;fluidEjectRate 由 ModConfig 提供
		ejectorComponent = MekCentrifugeFactoryHelper.setupTertiarySlotsAndIO(
				this, configComponent, inputSlots, outputSlots, tertiaryOutputSlots,
				tier.processes, energySlot, energyContainer, fluidOutputHolder, fluidOutputTank,
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
	}

	/** 子类提供PB处理器名称（用于日志标识） */
	protected abstract String getPbProcessorName();

	/** 重建初始物品栏（委托 AbstractMekCentrifugeFactorySetupHelper） */
	@NotNull
	@Override
	protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
		return AbstractMekCentrifugeFactorySetupHelper.createInitialInventory(this, listener, tier, energyContainer,
			this::addSlots);
	}

	/** 构建初始流体罐（委托 AbstractMekCentrifugeFactorySetupHelper） */
	@Nullable
	@Override
	protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
		return AbstractMekCentrifugeFactorySetupHelper.createInitialFluidTanks(this, listener, tier);
	}

	/** 同时查找SMELTING和PB CentrifugeRecipe，带缓存避免高频探测重复查配方 */
	@Override
	public boolean isItemValidForSlot(@NotNull ItemStack stack) {
		return CentrifugeFactoryCommonLogic.isItemValidForSlot(level, stack, validInputCache, pbProcessor, getRecipeType(),
			MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this));
	}

	/** 同isItemValidForSlot，带缓存 */
	@Override
	public boolean isValidInputItem(@NotNull ItemStack stack) {
		return CentrifugeFactoryCommonLogic.isItemValidForSlot(level, stack, validInputCache, pbProcessor, getRecipeType(),
			MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this));
	}

	@Override
	protected int getNeededInput(ItemStackToItemStackRecipe recipe, ItemStack inputStack) {
		return MekCentrifugeFactoryHelper.getNeededInput(recipe, inputStack);
	}

	@Override
	protected boolean isCachedRecipeValid(
		@Nullable CachedRecipe<ItemStackToItemStackRecipe> cached,
		@NotNull ItemStack stack
	) {
		return MekCentrifugeFactoryHelper.isCachedRecipeValid(cached, stack);
	}

	/** 查找 SMELTING/PB 配方（委托 AbstractMekCentrifugeFactorySetupHelper） */
	@Override
	protected ItemStackToItemStackRecipe findRecipe(
		int process,
		@NotNull ItemStack fallbackInput,
		@NotNull IInventorySlot outputSlot,
		@Nullable IInventorySlot secondaryOutputSlot
	) {
		return AbstractMekCentrifugeFactorySetupHelper.findRecipe(this, fallbackInput, outputSlot);
	}

	/** 支持PB配方输出兼容性检查，带缓存避免SFM/AE2高频调用重复查配方 */
	@Override
	public boolean inputProducesOutput(
		int process,
		@NotNull ItemStack fallbackInput,
		@NotNull IInventorySlot outputSlot,
		@Nullable IInventorySlot secondaryOutputSlot,
		boolean updateCache
	) {
		return CentrifugeFactoryCommonLogic.inputProducesOutput(level,
			fallbackInput, outputSlot, secondaryOutputSlot, inputProducesOutputCache, pbProcessor,
			() -> super.inputProducesOutput(process, fallbackInput, outputSlot, secondaryOutputSlot, updateCache));
	}

	/** 配置卡兼容性检查 — 支持ME/EME工厂跨等级粘贴配置 */
	@Override
	public boolean isConfigurationDataCompatible(@NotNull net.minecraft.world.level.block.Block blockType) {
		return super.isConfigurationDataCompatible(blockType) ||
			MekCompatHooks.isConfigurationDataCompatible(getBlockHolder(),
			blockType);
	}

	/** 写入配置卡数据 — 添加PB升级数量、AE2 per-tile状态和sorting字段（覆盖super用isSorting()写入的false） */
	@Override
	public void writeSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.writeSustainedData(provider, data);
		CentrifugeFactoryCommonLogic.writeSustainedData(data, pbUpgradeDelegate,
				productivebeesgenesis$getAe2StateHolder());
		data.putBoolean(SerializationConstants.SORTING,
				((TileEntityFactoryAccessor) this).productivebeesgenesis$getSorting());
	}

	/** 扳手拆卸隐式组件 — 覆盖super用isSorting()写入的false，持久化实际sorting字段值 */
	@Override
	protected void collectImplicitComponents(@NotNull DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		builder.set(MekanismDataComponents.SORTING,
				((TileEntityFactoryAccessor) this).productivebeesgenesis$getSorting());
	}

	/** 从配置卡数据读取 — 恢复AE2 per-tile状态 */
	@Override
	public void readSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.readSustainedData(provider, data);
		CentrifugeFactoryCommonLogic.readSustainedData(data, productivebeesgenesis$getAe2StateHolder());
	}

	/** 设置配置卡数据 — 处理PB升级粘贴（含生存模式物品消耗） */
	@Override
	public void setConfigurationData(@NotNull HolderLookup.Provider provider,
			@Nullable net.minecraft.world.entity.player.Player player, @NotNull CompoundTag data) {
		super.setConfigurationData(provider, player, data);
		CentrifugeFactoryCommonLogic.setConfigurationData(data, player, pbUpgradeDelegate);
	}

	@NotNull
	@Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput,
			ItemStackToItemStackRecipe, SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return AbstractMekCentrifugeFactoryJdteSupport.getRecipeType();
	}

	@NotNull
	@Override
	public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return AbstractMekCentrifugeFactoryJdteSupport.recipeViewerType();
	}

	/** Returns the active recipe (implementation moved to {@link AbstractMekCentrifugeFactoryJdteSupport#getRecipe}). */
	@Nullable
	@Override
	public ItemStackToItemStackRecipe getRecipe(int cacheIndex) {
		return AbstractMekCentrifugeFactoryJdteSupport.getRecipe(inputHandlers, cacheIndex, pbProcessor,
				this::findFirstRecipe, MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this));
	}

	@NotNull
	@Override
	public CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(
			@NotNull ItemStackToItemStackRecipe recipe, int cacheIndex) {
		return CentrifugeFactoryCommonLogic.createNewCachedRecipe(recipe, cacheIndex, recheckAllRecipeErrors,
				inputHandlers, outputHandlers, errorTracker::onErrorsChanged, this::canFunction,
				this::setActiveState, () -> MekUpgradeSupport.hasCreativeUpgrade(this), energyContainer,
				this::getTicksRequired, this::markForSave, this::getOperationsPerTick, progress);
	}

	/** JDTE 累计钩子（委托 AbstractMekCentrifugeFactoryJdteSupport） */
	public void productivebeesgenesis$accumulateAcceleratedTicks(int ticks) {
		AbstractMekCentrifugeFactoryJdteSupport.accumulateAcceleratedTicks(this, ticks);
	}

	/** JDTE flush (implementation moved to {@link AbstractMekCentrifugeFactoryJdteSupport#flushAcceleratedTicks}). */
	public void productivebeesgenesis$flushAcceleratedTicks() {
		AbstractMekCentrifugeFactoryJdteSupport.flushAcceleratedTicks(this, inputSlots, () -> super.onUpdateServer());
	}

	@Override
	protected boolean onUpdateServer() {
		return AbstractMekCentrifugeFactoryJdteSupport.onUpdateServer(this, inputSlots, () -> super.onUpdateServer());
	}

	/**
	 * 轻量 SMELTING 补调 — 仅推进各 lane 已缓存的熔炉配方（batchMultiplier - 1 次额外配方 tick）。
	 * <br/>
	 * 见 {@link MekCentrifugeFactoryHelper#runLightSmeltingTicks}：跳过 ejector/能量回填/配方重查，
	 * 语义等价于真实推进 batchMultiplier 次 tick，256x 加速下 MSPT 占用极低。
	 * 本方法供 {@link FactoryUpgradeStateHelper} 访问受保护的 {@code recipeCacheLookupMonitors}。
	 */
	public boolean productivebeesgenesis$runLightSmeltingTicks(int batchMultiplier) {
		return MekCentrifugeFactoryHelper.runLightSmeltingTicks(recipeCacheLookupMonitors, batchMultiplier);
	}

	/** 按钮显示与 ME/EME 一致：返回 sorting 字段实际值（委托 StateSupport，绕过原版死锁） */
	@Override
	public boolean isSorting() {
		return AbstractMekCentrifugeFactoryStateSupport.isSorting(this);
	}

	/** 覆写 toggleSorting — 直接设置 sorting 字段，避免原版 sorting = !isSorting() 死锁 */
	@Override
	public void toggleSorting() {
		boolean current = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getSorting();
		((TileEntityFactoryAccessor) this).productivebeesgenesis$setSorting(!current);
		markForSave();
	}

	/** PB处理时返回PB进度 */
	@Override
	public double getScaledProgress(int i, int process) {
		return MekCentrifugeFactoryHelper.getScaledProgress(i, process, pbProcessor,
			() -> super.getScaledProgress(i, process));
	}

	/** 注册容器同步追踪器（委托 AbstractMekCentrifugeFactorySetupHelper） */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		AbstractMekCentrifugeFactorySetupHelper.addContainerTrackers(this, container,
			() -> super.addContainerTrackers(container));
	}

	/** 持久化PB进度、PB升级、AE2节点、AE2 per-tile状态和多流体槽 */
	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		CentrifugeFactoryCommonLogic.saveAdditional(nbt,
			provider, pbProcessor, pbUpgradeDelegate, productivebeesgenesis$ae2LifecycleHandler, this, fluidOutputHolder,
			() -> super.saveAdditional(nbt, provider));
	}

	/** 保存自定义数据为NBT — 供扳手拆卸持久化使用（含多流体槽内容） */
	@Override
	@NotNull
	public CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		return CentrifugeFactoryCommonLogic.saveCustomDataForItem(provider,
			pbProcessor, pbUpgradeDelegate, productivebeesgenesis$getAe2StateHolder(), fluidOutputHolder, this::getType,
			this);
	}

	/** 加载PB进度、PB升级、AE2节点、AE2 per-tile状态和多流体槽 */
	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		CentrifugeFactoryCommonLogic.loadAdditional(nbt,
			provider, pbProcessor, pbUpgradeDelegate, productivebeesgenesis$ae2LifecycleHandler, this, fluidOutputHolder,
			() -> super.loadAdditional(nbt, provider));
	}

	// ===== AE2 网格节点生命周期 =====

	@Override
	public void clearRemoved() {
		CentrifugeFactoryCommonLogic.onClearRemoved(productivebeesgenesis$ae2LifecycleHandler, this, super::clearRemoved);
	}

	@Override
	public void setRemoved() {
		CentrifugeFactoryCommonLogic.onSetRemoved(productivebeesgenesis$ae2LifecycleHandler, this, super::setRemoved);
	}

	@Override
	public void onChunkUnloaded() {
		CentrifugeFactoryCommonLogic.onChunkUnloaded(productivebeesgenesis$ae2LifecycleHandler, this, super::onChunkUnloaded);
	}

	@Override
	public MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler(
	) {
		return productivebeesgenesis$ae2LifecycleHandler;
	}

	@Override
	public Ae2OutputStateHolder productivebeesgenesis$getAe2StateHolder() {
		return productivebeesgenesis$ae2LifecycleHandler.getStateHolder();
	}

	@Override
	public MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource() { return energyContainer; }

	@Override
	public Level productivebeesgenesis$getAe2Level() { return level; }

	@Override
	public BlockPos productivebeesgenesis$getAe2BlockPos() { return getBlockPos(); }

	/** 模块2.4：AE2 输出超限时暂停离心机输入 — 转发到 Mekanism 的 setActive(false) */
	@Override
	public void suspendInput() {
		setActive(false);
	}

	/** 构建升级数据 — 保存完整状态供等级切换时流转，含PB升级、AE2设置和多流体槽（Task 5） */
	@NotNull
	@Override
	public CentrifugeUpgradeData getUpgradeData(HolderLookup.Provider provider) {
		EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		return CentrifugeFactoryCommonLogic.getUpgradeData(provider, redstone, getControlType(),
				getEnergyContainer(), progress, energySlot, inputSlots, outputSlots,
				((TileEntityFactoryAccessor) this).productivebeesgenesis$getSorting(),
				getComponents(), pbUpgradeDelegate, productivebeesgenesis$getAe2StateHolder(),
				fluidOutputHolder);
	}

	/** 应用升级数据 — 恢复PB升级/AE2设置/多流体槽/深拷贝槽位（委托 StateSupport） */
	@Override
	public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		AbstractMekCentrifugeFactoryStateSupport.parseUpgradeData(this, provider, upgradeData,
				inputSlots, outputSlots, data -> super.parseUpgradeData(provider, data));
	}

	// ===== PbRecipeContext 接口实现 =====

	@Override
	public Level level() { return level; }

	@Override
	public MachineEnergyContainer<?> energyContainer() { return energyContainer; }

	/** CREATIVE升级兜底 — 手动检查零能耗，不依赖MEKExtras Mixin */
	@Override
	public boolean hasCreativeUpgrade() { return MekUpgradeSupport.hasCreativeUpgrade(this); }

	@Override
	public IInventorySlot inputSlot(int process) { return inputSlots.get(process); }

	@Override
	public IInventorySlot primaryOutputSlot(int process) { return processInfoSlots[process].outputSlot(); }

	@Override
	public IInventorySlot secondaryOutputSlot(int process) { return processInfoSlots[process].secondaryOutputSlot(); }

	@Override
	public IInventorySlot tertiaryOutputSlot(int process) { return tertiaryOutputSlots[process]; }

	@Override
	public IExtendedFluidTank fluidOutputTank() { return fluidOutputTank; }
	/** Task 9: MULTI_PER_FLUID 按流体类型路由到独立槽;SINGLE 模式 fallback 到主槽 */
	@Override
	public IExtendedFluidTank fluidOutputTankForInsert(FluidStack stack) {
		return MultiFluidTankHostHelper.fluidOutputTankForInsert(fluidOutputHolder, fluidOutputTank, stack);
	}
	@Override
	public void reserveFluidOutputType(FluidStack stack) {
		MultiFluidTankHostHelper.reserveFluidOutputType(fluidOutputHolder, stack);
	}
	/** Task 9: MULTI_PER_FLUID 返回已分配槽位数;SINGLE 模式返回 1 */
	@Override
	public int fluidOutputTankCount() { return MultiFluidTankHostHelper.fluidOutputTankCount(fluidOutputHolder); }

	// ===== Task 8: IMultiFluidTankHost 实现(委托给 MultiFluidTankHostHelper) =====

	@Override // Task 2: 客户端返回同步值,避免 holder 类型不一致
	public int getFluidTankCount() {
		return MultiFluidTankHostHelper.getFluidTankCount(fluidOutputHolder, fluidOutputTankCount,
			level != null && level.isClientSide());
	}
	@Override
	public IExtendedFluidTank getFluidTank(int index) {
		return MultiFluidTankHostHelper.getFluidTank(fluidOutputHolder, fluidOutputTank, index);
	}
	@Override
	public List<IExtendedFluidTank> getFluidTanks() {
		return MultiFluidTankHostHelper.getFluidTanks(fluidOutputHolder, fluidOutputTank);
	}
	/** 多流体模式标志（委托 AbstractMekCentrifugeFactorySetupHelper） */
	@Override
	public boolean isMultiFluidMode() {
		return AbstractMekCentrifugeFactorySetupHelper.isMultiFluidMode(this);
	}

	/** Task 1(选项 A):返回 SyncableBoolean 同步值,绕过 level 判断,确保 GUI 构造期(level 为 null)也能获取正确值 */
	@Override
	public boolean isMultiFluidModeSynced() {
		return isMultiFluidModeSynced;
	}

	/** Task 9: MULTI_PER_FLUID 按索引返回槽位;SINGLE 模式 fallback 到主槽 */
	@Override
	public IExtendedFluidTank fluidOutputTank(int index) {
		return MultiFluidTankHostHelper.fluidOutputTank(fluidOutputHolder, fluidOutputTank, index);
	}

	@Override
	public void productivebeesgenesis$onAe2FluidPushComplete() {
		AbstractMekCentrifugeFactorySetupHelper.onAe2FluidPushComplete(this);
	}

	/** Task 12: 检查流体槽类型不匹配 — SINGLE 主槽类型不同返回 true;MULTI_PER_FLUID 委托 MultiFluidTankHolder.isTypeMismatch */
	@Override
	public boolean isFluidTankTypeMismatch(FluidStack stack) {
		return MultiFluidTankHostHelper.isFluidTankTypeMismatch(fluidOutputHolder, fluidOutputTank, stack);
	}
	/** Task 4: 检查所有已分配流体槽是否都满载(委托给 Helper) */
	@Override
	public boolean areAllFluidTanksFull() {
		return MultiFluidTankHostHelper.areAllFluidTanksFull(fluidOutputHolder, fluidOutputTank);
	}
	/** Task 4: 检查是否还能分配新槽接收新流体类型(委托给 Helper) */
	@Override
	public boolean canAllocateNewFluidTank() {
		return MultiFluidTankHostHelper.canAllocateNewFluidTank(fluidOutputHolder);
	}
	/** Task 6: 存入孤儿多流体槽 NBT — MULTI→SINGLE 降级时保留数据,saveAdditional 显式写出确保持久化 */
	@Override
	public void setOrphanedMultiFluidTanksNbt(@Nullable CompoundTag nbt) { orphanedMultiFluidTanksNbt = nbt; }
	/** Task 6: 获取孤儿多流体槽 NBT — 供 saveAdditional 在 SINGLE 模式下写出 */
	@Override
	public @Nullable CompoundTag getOrphanedMultiFluidTanksNbt() { return orphanedMultiFluidTanksNbt; }

	@Override
	public int processes() { return tier.processes; }

	@Override
	public int baseTicksRequired() { return BASE_TICKS_REQUIRED; }

	@Override
	public void setPbActiveState(boolean active, int process) {
		FactoryUpgradeStateHelper.setPbActiveState(this, active, process, this::setActiveState);
	}

	@Override
	public int productivityModifier() { return CentrifugeFactoryCommonLogic.productivityModifier(pbUpgradeDelegate); }
	@Override
	public int productivityParallelModifier() { return pbUpgradeDelegate.getProductivityParallelModifier(); }

	@Override
	public float stabilityBonus() { return pbUpgradeDelegate.getStabilityBonus(); }

	@Override
	public int operationsPerTick() { return CentrifugeFactoryCommonLogic.operationsPerTick(this, BASE_TICKS_REQUIRED); }

	/** 重写recalculateUpgrades — 复刻MEKExtras，支持STACK升级并行和CREATIVE无限能量 */
	@Override
	public void recalculateUpgrades(Upgrade upgrade) {
		super.recalculateUpgrades(upgrade);
		FactoryUpgradeStateHelper.recalculateUpgrades(this, upgrade);
	}

	/** 重写getTicksRequired — CREATIVE 返回 0，对齐 Mekanism Extras 最大处理速率 */
	@Override
	public int getTicksRequired() { return FactoryUpgradeStateHelper.getTicksRequired(this); }

	/** 重写getInfo — STACK显示并行倍数，CREATIVE显示∞效率和0能耗 */
	@NotNull
	@Override
	public List<Component> getInfo(@NotNull Upgrade upgrade) { return FactoryUpgradeStateHelper.getInfo(this, upgrade); }
	@Override
	public int getTicksForBase(int baseTime) {
		return CentrifugeFactoryCommonLogic.getTicksForBase(this, baseTime, pbUpgradeDelegate);
	}
	@Override
	public boolean containsSmeltingInput(ItemStack input) {
		return MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this)
				&& MekCentrifugeFactoryHelper.containsSmeltingInput(getRecipeType(), level, input);
	}

	@Override
	public FactoryPbContextDelegate productivebeesgenesis$getDelegate() { return delegate; }
	/** 获取批量收获状态管理器（供 FactoryUpgradeStateHelper 和 ME/EME 工厂访问） */
	public TickBatchSkipState productivebeesgenesis$getTickBatchSkipState() { return tickBatchSkipState; }
	/** 返回自身的 pbProcessor — 供 IFactoryPbDelegateAccess.isValidInput 默认实现检查 PB CentrifugeRecipe */
	@Override
	public PbRecipeProcessor productivebeesgenesis$getPbProcessor() { return pbProcessor; }
	// ===== GUI暴露方法（Task 9: 委托给 FactoryGuiAccessor） =====

	@Nullable
	public IInventorySlot getSecondaryOutputSlot(int processIndex) {
		return guiAccessor.getSecondaryOutputSlot(processIndex);
	}
	@NotNull
	public IInventorySlot getTertiaryOutputSlot(int processIndex) {
		return guiAccessor.getTertiaryOutputSlot(processIndex);
	}
	@NotNull
	public IExtendedFluidTank getFluidOutputTank() { return guiAccessor.getFluidOutputTank(); }
	/** 切换per-tile AE2物品输出开关（供网络包handler调用） */
	public void toggleAeItemOutput() {
		CentrifugeFactoryCommonLogic.toggleAeItemOutput(productivebeesgenesis$getAe2StateHolder(), this::markForSave);
	}
	/** 切换per-tile AE2流体输出开关（供网络包handler调用） */
	public void toggleAeFluidOutput() {
		CentrifugeFactoryCommonLogic.toggleAeFluidOutput(productivebeesgenesis$getAe2StateHolder(), this::markForSave);
	}
	// ===== IAe2InputHost 接口实现（AE2输入拉取契约） =====

	/** 获取用于拉取的输入槽列表 — 工厂版每进程1个输入槽，按顺序填充 */
	@Override
	public List<IInventorySlot> productivebeesgenesis$getInputSlotsForPull() {
		// 父类已维护稳定的输入槽列表；直接复用，避免 AE2 拉取路径每次分配 ArrayList。
		return inputSlots;
	}

	/** 切换per-tile AE2输入拉取开关（供网络包handler调用） */
	@Override
	public void productivebeesgenesis$toggleAeItemInput(
	) {
		CentrifugeFactoryCommonLogic.toggleAeItemInput(productivebeesgenesis$getAe2StateHolder(), this::markForSave);
	}
	/** 切换per-tile AE2输入NBT忽略开关（供网络包handler调用） */
	@Override
	public void productivebeesgenesis$toggleAeInputNbtIgnore(
	) {
		CentrifugeFactoryCommonLogic.toggleAeInputNbtIgnore(productivebeesgenesis$getAe2StateHolder(), this::markForSave);
	}
	// ===== IPbUpgradeProvider实现 + PB升级槽位访问（委托给pbUpgradeDelegate） =====

	@Override
	public int getPbUpgradeInstalledCount(PbUpgradeType type) {
		return pbUpgradeDelegate.getPbUpgradeInstalledCount(type);
	}
	@Override
	public int getPbUpgradeLimit(PbUpgradeType type) { return pbUpgradeDelegate.getPbUpgradeLimit(type); }
	@Override
	public float getClientInstallingProgress() { return pbUpgradeDelegate.getClientInstallingProgress(); }
	@Override
	public float getClientUninstallingProgress() { return pbUpgradeDelegate.getClientUninstallingProgress(); }
	@Override
	public boolean isPbUpgradeSupported(PbUpgradeType type) { return pbUpgradeDelegate.isPbUpgradeSupported(type); }
	/** 获取PB升级输入槽 — 供Container创建虚拟槽位 */
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeInputSlot() { return pbUpgradeDelegate.getPbUpgradeInputSlot(); }
	/** 获取PB升级输出槽 — 供Container创建虚拟槽位 */
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeOutputSlot() { return pbUpgradeDelegate.getPbUpgradeOutputSlot(); }
	/** 卸载指定类型的PB升级到输出槽 — 供网络包调用 */
	public boolean extractPbUpgradeByType(PbUpgradeType type) { return pbUpgradeDelegate.extractPbUpgradeByType(type); }
	/**
	 * 批量安装 PB 升级 — 由 Mixin 拦截 PB 原版 useOn 后调用
	 * @param type 升级类型;@param maxAvailable 持有数量(stack.getCount());@return 实际安装数量(0 表示未安装)
	 */
	public int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		return AbstractMekCentrifugeFactoryStateSupport.installPbUpgradeBulk(pbUpgradeDelegate, type, maxAvailable);
	}
	/** IUpgradeableBlockEntity — 返回PB原版安装桥接器，委托给自定义升级系统 */
	@NotNull
	@Override
	public IItemHandlerModifiable getUpgradeHandler() { return pbUpgradeDelegate.getInstallHandler(); }
}
