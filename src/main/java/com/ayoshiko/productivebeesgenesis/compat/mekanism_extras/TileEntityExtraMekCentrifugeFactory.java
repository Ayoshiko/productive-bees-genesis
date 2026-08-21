package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeData;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeFluidTankMultipliers;
import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeInputStackMultipliers;
import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeOutputStackMultipliers;
import com.ayoshiko.productivebeesgenesis.inventory.FactoryExternalInsertPolicy;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic;
import com.ayoshiko.productivebeesgenesis.mek.FactoryPbContextDelegate;
import com.ayoshiko.productivebeesgenesis.mek.FactoryPbUpgradeDelegate;
import com.ayoshiko.productivebeesgenesis.mek.IFactoryPbDelegateAccess;
import com.ayoshiko.productivebeesgenesis.mek.IHasEjectorCooldown;
import com.ayoshiko.productivebeesgenesis.mek.IJdteCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugePbUpgradeHost;
import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.mek.MultiFluidTankHostDelegate;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeProcessor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.mek.TickBatchSkipState;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityExtraFactoryAccessor;
import com.ayoshiko.productivebeesgenesis.util.InputOutputCompatibilityCache;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import com.jerry.mekextras.api.recipes.outputs.ExtraOutputHelper;
import com.jerry.mekextras.common.inventory.slot.ExtraFactoryInputInventorySlot;
import com.jerry.mekextras.common.inventory.slot.ExtraFactoryOutputInventorySlot;
import com.jerry.mekextras.common.tile.factory.TileEntityExtraItemStackToItemStackFactory;
import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.lookup.ISingleRecipeLookupHandler.ItemRecipeLookupHandler;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.recipe.lookup.monitor.FactoryRecipeCacheLookupMonitor;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.IntSupplier;

/**
	 * ME 扩展版 MEK 离心机工厂方块实体 — 继承 ME 的 TileEntityExtraItemStackToItemStackFactory。
	 * 因 Java 单继承限制通过组合模式(PbRecipeProcessor、FactoryPbContextDelegate、IAe2OutputHostBase)复用逻辑。
	 * 双配方路径:SMELTING 走 Mekanism 管线(主输出槽);PB CentrifugeRecipe 独立处理(3 输出槽 + 流体槽)。
	 */
public class TileEntityExtraMekCentrifugeFactory extends TileEntityExtraItemStackToItemStackFactory
		implements ItemRecipeLookupHandler<ItemStackToItemStackRecipe>, IFactoryPbDelegateAccess, IHasEjectorCooldown,
		IAe2OutputHostBase, IPbUpgradeProvider, IUpgradeableBlockEntity, IMekCentrifugePbUpgradeHost,
		com.ayoshiko.productivebeesgenesis.ICustomDataPersistable, IMultiFluidTankHost,
		IJdteCentrifugeFactory {

	@Override public void productivebeesgenesis$onSmeltingCompatChanged(
	) {
		TileEntityExtraFactoryDelegates.onSmeltingCompatChanged(validInputCache,
			inputProducesOutputCache, pbProcessor, tier.processes,
			recipeCacheLookupMonitors);
	}

	/** 副输出槽2 — 每进程第3个物品输出槽 */
	private ExtraFactoryOutputInventorySlot[] tertiaryOutputSlots;
	/**
	 * 多流体槽委托 — 封装 fluidOutputTank/fluidOutputHolder/同步状态/orphaned NBT 等流体相关字段与方法
	 * <br/>
	 * 非 final + 懒初始化：父类 {@code TileEntityMekanism.<init>} 在 super() 期间调用
	 * {@link #getInitialFluidTanks} 虚方法，此时子类字段初始化器还未执行（Java 字段初始化
	 * 在 super() 之后）。若使用 final 字段初始化器，super() 期间 multiFluidDelegate 为 null，
	 * 导致 {@code multiFluidDelegate.setFluidOutputHolder} 抛 NPE。通过 {@link #getOrCreateDelegate()}
	 * 在首次访问时懒初始化，确保 super() 期间也能安全访问。
	 */
	private MultiFluidTankHostDelegate multiFluidDelegate;

	/**
	 * 懒初始化多流体槽委托 — 解决父类构造函数调用虚方法的字段初始化顺序问题
	 * <br/>
	 * Java 字段初始化器在 super() 之后执行，但父类 {@code TileEntityMekanism.<init>}
	 * 在 super() 期间调用 {@link #getInitialFluidTanks}，此时 final 字段初始化器未执行。
	 * 通过本方法在首次访问时懒初始化，确保 super() 期间也能安全访问。
	 *
	 * @return 多流体槽委托实例（非 null）
	 */
	private MultiFluidTankHostDelegate getOrCreateDelegate() {
		if (multiFluidDelegate == null) {
			multiFluidDelegate = new MultiFluidTankHostDelegate(() -> level);
		}
		return multiFluidDelegate;
	}

	/** PB配方处理器 */
	private final PbRecipeProcessor pbProcessor;
	/** AE2 生命周期处理器 */
	private final MekAe2LifecycleHandler productivebeesgenesis$ae2LifecycleHandler = new MekAe2LifecycleHandler();
	/** 工厂 PB 上下文委托 */
	private FactoryPbContextDelegate delegate;
	/** PB 升级委托 — 封装 PB 升级安装/卸载/同步/持久化/倍率计算 */
	private final FactoryPbUpgradeDelegate pbUpgradeDelegate;
	/** 输入槽有效性校验缓存 */
	private final InputValidationCache validInputCache = new InputValidationCache();
	/** 输入-输出兼容性校验缓存 */
	private final InputOutputCompatibilityCache inputProducesOutputCache = new InputOutputCompatibilityCache();
	/** Per-tile 批量收获状态 — skipPb "虚拟 tick 银行"策略,256x JDTE 加速下避免每 gameTick 256 次完整 PB 处理 */
	private final TickBatchSkipState tickBatchSkipState = new TickBatchSkipState();

	/**
	 * TickAccelTracker 懒缓存 — Spark 优化（报告 l5oASjsSuW）
	 * <br/>
	 * 原每 tick 经 getAe2StateHolder() 3 层接口分发链解析（JDTE 256x 下每 gameTick 256 次），
	 * 是模组最大热点方法。tracker 是 holder 的 final 字段，tile 生命周期内稳定，
	 * 首次解析后缓存。本类不继承 AbstractMekCentrifugeFactory（ME 工厂基类），
	 * 需独立持有缓存。
	 */
	private TickAccelTracker productivebeesgenesis$cachedTickAccelTracker;

	/** 获取 TickAccelTracker（懒缓存）— 供 onUpdateServer/accumulate/flush 三入口共用 */
	TickAccelTracker productivebeesgenesis$getTickAccelTracker() {
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

	public TileEntityExtraMekCentrifugeFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state);
		pbProcessor = new PbRecipeProcessor(this, "ME工厂离心机");
		pbUpgradeDelegate = new FactoryPbUpgradeDelegate(this);
		EnergyInventorySlot energySlot = ((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		// Task 13: 传入 fluidOutputHolder 和 fluidOutputTank,在 MULTI_PER_FLUID 模式下让 Ejector 自动遍历所有槽
		// 使用 getOrCreateDelegate() 懒初始化：super() 期间 getInitialFluidTanks 已创建 delegate 实例
		MultiFluidTankHostDelegate delegate = getOrCreateDelegate();
		ejectorComponent = MekCentrifugeFactoryHelper.setupTertiarySlotsAndIO(
				this, configComponent, inputSlots, outputSlots, tertiaryOutputSlots,
				tier.processes, energySlot, energyContainer,
				delegate.getFluidOutputHolder(), delegate.getFluidOutputTank(),
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
	}

	/** 每进程3个输出槽（y=57/77/97），使用ME的ExtraFactorySlot类型，baseX=27, baseXMult=19 */
	@Override
	protected void addSlots(
		InventorySlotHelper builder,
		IContentsListener listener,
		IContentsListener updateSortingListener
	) {
		inputHandlers = new IInputHandler[tier.processes];
		outputHandlers = new IOutputHandler[tier.processes];
		processInfoSlots = new ProcessInfo[tier.processes];
		tertiaryOutputSlots = new ExtraFactoryOutputInventorySlot[tier.processes];
		delegate = FactoryPbContextDelegate.create(this, updateSortingListener, recipeCacheLookupMonitors);

		int baseX = 27;
		int baseXMult = 19;
		FactoryExternalInsertPolicy externalInputPolicy = new FactoryExternalInsertPolicy(
				() -> level == null ? Long.MIN_VALUE : level.getGameTime(),
				() -> FactoryExternalInsertPolicy.recommendedWorkingSet(
						operationsPerTick(), productivebeesgenesis$getTickBatchSkipState().getBatchMultiplier(),
						productivityParallelModifier()));

		for (int i = 0; i < tier.processes; i++) {
			int xPos = baseX + (i * baseXMult);
			@SuppressWarnings("unchecked")
			FactoryRecipeCacheLookupMonitor<ItemStackToItemStackRecipe> lookupMonitor =
					(FactoryRecipeCacheLookupMonitor<ItemStackToItemStackRecipe>) recipeCacheLookupMonitors[i];
			IContentsListener updateSortingAndUnpause = delegate.createOutputSlotListener(i);

			ExtraFactoryOutputInventorySlot outputSlot = ExtraFactoryOutputInventorySlot.at(this, updateSortingAndUnpause, xPos,
				57);
			ExtraFactoryOutputInventorySlot secondaryOutputSlot = ExtraFactoryOutputInventorySlot.at(this,
				updateSortingAndUnpause, xPos,
				77);
			ExtraFactoryOutputInventorySlot tertiaryOutputSlot = ExtraFactoryOutputInventorySlot.at(this,
				updateSortingAndUnpause, xPos,
				97);
			// Task 8: 工厂版输出槽同步应用 stack_multiplier（替换 ME 默认 8<<tier 倍率）
			IntSupplier outputMultiplier = CentrifugeOutputStackMultipliers.forMEFactory(tier.ordinal());
			((TieredInputSlot) outputSlot).productivebeesgenesis$setInputStackMultiplier(outputMultiplier);
			((TieredInputSlot) secondaryOutputSlot).productivebeesgenesis$setInputStackMultiplier(outputMultiplier);
			((TieredInputSlot) tertiaryOutputSlot).productivebeesgenesis$setInputStackMultiplier(outputMultiplier);
			tertiaryOutputSlots[i] = tertiaryOutputSlot;

			ExtraFactoryInputInventorySlot inputSlot = ExtraFactoryInputInventorySlot.create(
					this, i, outputSlot, secondaryOutputSlot, lookupMonitor, xPos, 13);
			// Task 7: 注入输入槽分等级堆叠倍率（按 ExtraFactoryTier.ordinal 索引配置，替换 ME 默认 8<<tier 倍率）
			((TieredInputSlot) inputSlot).productivebeesgenesis$setInputStackMultiplier(
					CentrifugeInputStackMultipliers.forMEFactory(tier.ordinal()));
			externalInputPolicy.register(inputSlot);

			int index = i;
			builder.addSlot(inputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE,
				getWarningCheck(RecipeError.NOT_ENOUGH_INPUT,
				index)));
			builder.addSlot(outputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT,
				getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE,
				index)));
			builder.addSlot(secondaryOutputSlot);
			builder.addSlot(tertiaryOutputSlot);

			inputHandlers[i] = InputHelper.getInputHandler(inputSlot, RecipeError.NOT_ENOUGH_INPUT);
			outputHandlers[i] = ExtraOutputHelper.getOutputHandler(outputSlot, RecipeError.NOT_ENOUGH_OUTPUT_SPACE,
				this::getOperationsPerTick);
			processInfoSlots[i] = new ProcessInfo(i, inputSlot, outputSlot, secondaryOutputSlot);
		}
	}

	/** 添加共享流体输出槽，容量随tier.processes和tier倍率缩放 */
	@Nullable
	@Override
	protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
		IntSupplier fluidTankMultiplier = CentrifugeFluidTankMultipliers.forMEFactory(tier.ordinal());
		// Task 13: 保存 holder 用于 setupTertiarySlotsAndIO 多槽弹出配置
		// Task 1: tankCountSetter 构造时设置 fluidOutputTankCount,避免 Tab 窗口过窄
		// 使用 getOrCreateDelegate() 懒初始化：本方法在 super() 期间被父类调用，multiFluidDelegate 字段初始化器还未执行
		MultiFluidTankHostDelegate delegate = getOrCreateDelegate();
		IFluidTankHolder holder = MekCentrifugeFactoryHelper.createFluidOutputHolder(this,
			listener, tier.processes, fluidTankMultiplier, level != null && level.isClientSide(), delegate::setFluidOutputTank,
			delegate::setFluidOutputTankCount);
		delegate.setFluidOutputHolder(holder);
		return holder;
	}

	/** 同时查找SMELTING和PB配方，带缓存避免高频探测重复查配方 */
	@Override public boolean isItemValidForSlot(@NotNull ItemStack stack) {
		return CentrifugeFactoryCommonLogic.isItemValidForSlot(level, stack, validInputCache, pbProcessor, getRecipeType(),
			MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this));
	}
	/** 同isItemValidForSlot，带缓存 */
	@Override public boolean isValidInputItem(@NotNull ItemStack stack) {
		return CentrifugeFactoryCommonLogic.isItemValidForSlot(level, stack, validInputCache, pbProcessor, getRecipeType(),
			MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this));
	}
	@Override protected int getNeededInput(ItemStackToItemStackRecipe recipe, ItemStack inputStack) {
		return MekCentrifugeFactoryHelper.getNeededInput(recipe, inputStack);
	}
	@Override protected boolean isCachedRecipeValid(
		@Nullable CachedRecipe<ItemStackToItemStackRecipe> cached,
		@NotNull ItemStack stack
	) {
		return MekCentrifugeFactoryHelper.isCachedRecipeValid(cached, stack);
	}

	/** 只查SMELTING配方，PB配方由tryProcessPbRecipe独立处理 */
	@Override protected ItemStackToItemStackRecipe findRecipe(
		int process,
		@NotNull ItemStack fallbackInput,
		@NotNull IInventorySlot outputSlot,
		@Nullable IInventorySlot secondaryOutputSlot
	) {
		return TileEntityExtraFactoryDelegates.findRecipe(this, level, fallbackInput, outputSlot);
	}

	/** 支持PB配方输出兼容性检查，带缓存避免SFM/AE2高频调用重复查配方 */
	@Override public boolean inputProducesOutput(
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
	/** 配置卡兼容性检查 — 支持EME/ME工厂跨等级粘贴配置 */
	@Override public boolean isConfigurationDataCompatible(@NotNull Block blockType) {
		return super.isConfigurationDataCompatible(blockType) ||
			MekCompatHooks.isConfigurationDataCompatible(getBlockHolder(),
			blockType);
	}

	/** 写入配置卡数据 — 添加PB升级数量和AE2 per-tile状态 */
	@Override public void writeSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		TileEntityExtraFactoryDelegates.writeSustainedData(provider,
			data, pbUpgradeDelegate, productivebeesgenesis$getAe2StateHolder(),
				((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getSorting(),
			() -> super.writeSustainedData(provider, data));
	}

	/** 扳手拆卸隐式组件 — 覆盖父类用 isSorting() 写入的 false，持久化实际 sorting 字段值 */
	@Override
	protected void collectImplicitComponents(
		@NotNull net.minecraft.core.component.DataComponentMap.Builder builder
	) {
		TileEntityExtraFactoryDelegates.collectImplicitComponents(builder,
			((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getSorting(),
			() -> super.collectImplicitComponents(builder));
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
@NotNull @Override public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
		SingleItem<ItemStackToItemStackRecipe>> getRecipeType() { return TileEntityExtraFactoryDelegates.getRecipeType(); }
	@NotNull @Override public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType(
	) {
		return TileEntityExtraFactoryDelegates.recipeViewerType();
	}

	/** PB配方存在时返回null，阻止SMELTING管线抢占输入 */
	@Nullable
	@Override
	public ItemStackToItemStackRecipe getRecipe(int cacheIndex) {
		return TileEntityExtraFactoryDelegates.getRecipe(this, inputHandlers, cacheIndex, pbProcessor);
	}
	@NotNull
	@Override
	public CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(
			@NotNull ItemStackToItemStackRecipe recipe, int cacheIndex) {
		return TileEntityExtraFactoryDelegates.createNewCachedRecipe(recipe, cacheIndex, recheckAllRecipeErrors,
				inputHandlers, outputHandlers, errorTracker::onErrorsChanged, this::canFunction,
				this::setActiveState, () -> MekUpgradeSupport.hasCreativeUpgrade(this), energyContainer,
				this::getTicksRequired, this::markForSave, this::getOperationsPerTick, progress);
	}

	/**
	 * 先走SMELTING管线，再处理PB配方，末尾推送输出到AE2网络。
	 * <br/>
	 * skipPb 批量收获（镜像 AbstractMekCentrifugeFactory）：256x JDTE 加速下每 gameTick 调用 256 次,
	 * 第一次执行 PB、升级槽与 AE I/O（使用上一 gameTick 倍率），后续调用只保留入口门控。
	 * decideAction 内部已调用 tracker.onTick并处理同 gameTick 门控。
	 */
	@Override
	protected boolean onUpdateServer() {
		// Spark 优化：走基类懒缓存 getter（原每 tick 3 层接口链分发是模组最大热点）
		TickAccelTracker tracker = productivebeesgenesis$getTickAccelTracker();
		Level level = getLevel();
		TickBatchSkipState skipState = productivebeesgenesis$getTickBatchSkipState();
		TickBatchSkipState.TickAction action = skipState.decideAction(tracker, level);
		if (action == TickBatchSkipState.TickAction.ALREADY_HANDLED) {
			// 同 gameTick 已由 JDTE flush（基类完整 tick）处理：完全跳过，避免双跑
			return false;
		}
		boolean skipPb = action == TickBatchSkipState.TickAction.SKIP;
		return productivebeesgenesis$runTick(skipPb, skipState.getBatchMultiplier());
	}

	/** JDTE coalesced path: credit virtual ticks without entering the expensive factory ticker. */
	@Override
	public void productivebeesgenesis$accumulateAcceleratedTicks(int ticks) {
		TickAccelTracker tracker = productivebeesgenesis$getTickAccelTracker();
		if (tracker != null) {
			tracker.addVirtualTicks(ticks);
		}
	}

	/** JDTE coalesced path: execute the full factory pipeline once for this real game tick. */
	@Override
	public void productivebeesgenesis$flushAcceleratedTicks() {
		Level level = getLevel();
		if (level == null || level.isClientSide) {
			return;
		}
		TickAccelTracker tracker = productivebeesgenesis$getTickAccelTracker();
		TickBatchSkipState skipState = productivebeesgenesis$getTickBatchSkipState();
		long gameTick = level.getGameTime();
		if (tracker == null || !skipState.tryBeginGameTick(gameTick)) {
			return;
		}
		int batchMultiplier = skipState.takeSharedBatchMultiplier(tracker, gameTick);
		productivebeesgenesis$runTick(false, batchMultiplier);
	}

	private boolean productivebeesgenesis$runTick(boolean skipPb, int batchMultiplier) {

		productivebeesgenesis$ae2LifecycleHandler.tryConnectNode(this);
		if (!skipPb) {
			pbUpgradeDelegate.processPbUpgradeInput();
			delegate.resetSortingMark();
		}
		productivebeesgenesis$injectAe2Energy(batchMultiplier);
		TileEntityExtraFactoryAccessor accessor = (TileEntityExtraFactoryAccessor) this;
		long energyBeforeSuper = energyContainer.getEnergy();
		boolean sendUpdatePacket = super.onUpdateServer();

		boolean result;
		if (!skipPb) {
			// SMELTING（电力熔炼炉）配方加速 — 与基础机/原版工厂一致：批量倍率 > 1
			// 且存在 SMELTING 配方通道时轻量补调，使熔炉管线按倍率 M 推进
			// （JDTE 时间加速器与 JDT 时间手杖均生效）。JDTE coalesced flush 与
			// 普通 ticker 共用这一轻量路径，保证两种加速入口语义一致。
			if (batchMultiplier > 1) {
				// 轻量补调：仅推进已缓存熔炉配方（跳过 ejector/能量回填/每 tick 配方重查），
				// 语义等价于真实推进 batchMultiplier 次 tick，256x 加速下 MSPT 占用极低。
				if (productivebeesgenesis$runLightSmeltingTicks(batchMultiplier)) {
					sendUpdatePacket = true;
				}
			}
			// 执行 PB：设置批量倍率（虚拟 tick 银行取款）
			pbProcessor.setTickMultiplier(batchMultiplier);
			result = MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(
					sendUpdatePacket, energyBeforeSuper, energyContainer, tier.processes,
					inputSlots, pbProcessor, this, getActive(), this::setActive,
					v -> accessor.productivebeesgenesis$setLastUsage(v));
			// 同步流体槽位数到客户端 — 仅执行 PB 时同步,跳过 255 次冗余赋值
			getOrCreateDelegate().setFluidOutputTankCount(getOrCreateDelegate().getFluidOutputHolder()
					instanceof MultiFluidTankHolder h ? h.getTankCount() : 1);
			// AE I/O 与 PB 批处理共用真实游戏刻门控，避免 256x 子 tick 重复进入短路链。
			CentrifugeFactoryCommonLogic.pushAe2OutputsAndPullInputs(this, batchMultiplier);
		} else {
			// 跳过 PB：本 gameTick 后续调用,仅保留 super 返回值
			result = sendUpdatePacket;
		}

		// 消耗后补回 AE2 能量高水位，稳定客户端能量条并保证下一批有完整储备。
		productivebeesgenesis$injectAe2Energy(batchMultiplier);

		return result;
	}

	/**
	 * 轻量 SMELTING 补调 — 仅推进各 lane 已缓存的熔炉配方（batchMultiplier - 1 次额外配方 tick）。
	 * <br/>
	 * 见 {@link MekCentrifugeFactoryHelper#runLightSmeltingTicks}：跳过 ejector/能量回填/配方重查，
	 * 语义等价于真实推进 batchMultiplier 次 tick，256x 加速下 MSPT 占用极低。
	 * 本方法供 onUpdateServer 访问受保护的 {@code recipeCacheLookupMonitors}。
	 */
	public boolean productivebeesgenesis$runLightSmeltingTicks(int batchMultiplier) {
		return MekCentrifugeFactoryHelper.runLightSmeltingTicks(recipeCacheLookupMonitors, batchMultiplier);
	}

	/** PB处理时返回PB进度 */
	@Override public double getScaledProgress(int i, int process) {
		return TileEntityExtraFactoryDelegates.getScaledProgress(i, process, pbProcessor,
			() -> super.getScaledProgress(i, process));
	}
	/** 同步PB进度、PB升级数量、AE2 per-tile状态(含过滤模式)和流体槽状态到客户端 */
	@Override public void addContainerTrackers(MekanismContainer container) {
		TileEntityExtraFactoryDelegates.addContainerTrackers(container,
			pbProcessor, pbUpgradeDelegate, productivebeesgenesis$getAe2StateHolder(), getOrCreateDelegate(),
			() -> super.addContainerTrackers(container));
	}

	/** 持久化PB进度、PB升级、AE2节点、AE2 per-tile状态和多流体槽 */
	@Override public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		CentrifugeFactoryCommonLogic.saveAdditional(nbt, provider,
			pbProcessor, pbUpgradeDelegate, productivebeesgenesis$ae2LifecycleHandler,
				this, getOrCreateDelegate().getFluidOutputHolder(),
			() -> super.saveAdditional(nbt, provider));
	}

	/** 保存自定义数据为NBT — 供扳手拆卸持久化使用（含多流体槽内容） */
	@NotNull
	@Override
	public CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		return CentrifugeFactoryCommonLogic.saveCustomDataForItem(provider, pbProcessor, pbUpgradeDelegate,
				productivebeesgenesis$getAe2StateHolder(), getOrCreateDelegate().getFluidOutputHolder(), this::getType, this);
	}

	/** 加载PB进度、PB升级、AE2节点、AE2 per-tile状态和多流体槽 */
	@Override public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		CentrifugeFactoryCommonLogic.loadAdditional(nbt, provider,
			pbProcessor, pbUpgradeDelegate, productivebeesgenesis$ae2LifecycleHandler,
				this, getOrCreateDelegate().getFluidOutputHolder(),
			() -> super.loadAdditional(nbt, provider));
	}
	/** 切换per-tile AE2物品输出开关（供网络包handler调用） */
	public void toggleAeItemOutput() {
		CentrifugeFactoryCommonLogic.toggleAeItemOutput(productivebeesgenesis$getAe2StateHolder(), this::markForSave);
	}
	/** 切换per-tile AE2流体输出开关（供网络包handler调用） */
	public void toggleAeFluidOutput() {
		CentrifugeFactoryCommonLogic.toggleAeFluidOutput(productivebeesgenesis$getAe2StateHolder(), this::markForSave);
	}

	// ===== AE2 网格节点生命周期 =====

	@Override public void clearRemoved() {
		CentrifugeFactoryCommonLogic.onClearRemoved(productivebeesgenesis$ae2LifecycleHandler, this, super::clearRemoved);
	}
	@Override public void setRemoved() {
		CentrifugeFactoryCommonLogic.onSetRemoved(productivebeesgenesis$ae2LifecycleHandler, this, super::setRemoved);
	}
	@Override public void onChunkUnloaded() {
		CentrifugeFactoryCommonLogic.onChunkUnloaded(productivebeesgenesis$ae2LifecycleHandler, this, super::onChunkUnloaded);
	}
	@Override public MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler(
	) {
		return productivebeesgenesis$ae2LifecycleHandler;
	}
	@Override public MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource() { return energyContainer; }
	@Override public Level productivebeesgenesis$getAe2Level() { return level; }
	@Override public BlockPos productivebeesgenesis$getAe2BlockPos() { return getBlockPos(); }

	/** 构建升级数据 — 保存完整状态供等级切换时流转，含PB升级、AE2设置和多流体槽（Task 5） */
	@NotNull
	@Override
	public CentrifugeUpgradeData getUpgradeData(HolderLookup.Provider provider) {
		EnergyInventorySlot energySlot = ((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		return CentrifugeFactoryCommonLogic.getUpgradeData(provider, redstone, getControlType(),
				getEnergyContainer(), progress, energySlot, inputSlots, outputSlots, isSorting(),
				getComponents(), pbUpgradeDelegate, productivebeesgenesis$getAe2StateHolder(),
				getOrCreateDelegate().getFluidOutputHolder());
	}

	/** 应用升级数据 — 先委托父类恢复标准字段，再恢复PB升级/AE2设置/多流体槽和深拷贝槽位内容 */
	@Override
	public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		EnergyInventorySlot energySlot = ((TileEntityExtraFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
		CentrifugeFactoryCommonLogic.parseUpgradeData(provider, upgradeData, pbUpgradeDelegate,
				productivebeesgenesis$getAe2StateHolder(), getOrCreateDelegate().getFluidOutputHolder(),
				inputSlots, outputSlots, energySlot,
				data -> super.parseUpgradeData(provider, data));
	}

	// ===== PbRecipeContext 接口实现 =====

	@Override public Level level() { return level; }
	@Override public MachineEnergyContainer<?> energyContainer() { return energyContainer; }

	/** CREATIVE升级兜底 — 手动检查零能耗，不依赖MEKExtras Mixin */
	@Override public boolean hasCreativeUpgrade() { return MekUpgradeSupport.hasCreativeUpgrade(this); }
	@Override public IInventorySlot inputSlot(int process) { return inputSlots.get(process); }
	@Override public IInventorySlot primaryOutputSlot(int process) { return processInfoSlots[process].outputSlot(); }
	@Override public IInventorySlot secondaryOutputSlot(int process) {
		return processInfoSlots[process].secondaryOutputSlot();
	}
	@Override public IInventorySlot tertiaryOutputSlot(int process) { return tertiaryOutputSlots[process]; }
	@Override public IExtendedFluidTank fluidOutputTank() { return getOrCreateDelegate().fluidOutputTank(); }
	@Override public IExtendedFluidTank fluidOutputTankForInsert(FluidStack stack) {
		return getOrCreateDelegate().fluidOutputTankForInsert(stack);
	}
	@Override public void reserveFluidOutputType(FluidStack stack) { getOrCreateDelegate().reserveFluidOutputType(stack); }
	@Override public int fluidOutputTankCount() { return getOrCreateDelegate().fluidOutputTankCount(); }

	// ===== IMultiFluidTankHost 实现(委托给 MultiFluidTankHostDelegate) =====

	@Override public int getFluidTankCount() { return getOrCreateDelegate().getFluidTankCount(); }
	@Override public IExtendedFluidTank getFluidTank(int index) { return getOrCreateDelegate().getFluidTank(index); }
	@Override public List<IExtendedFluidTank> getFluidTanks() { return getOrCreateDelegate().getFluidTanks(); }
	@Override public boolean isMultiFluidMode() { return getOrCreateDelegate().isMultiFluidMode(); }
	@Override public boolean isMultiFluidModeSynced() { return getOrCreateDelegate().isMultiFluidModeSynced(); }
	@Override public IExtendedFluidTank fluidOutputTank(int index) { return getOrCreateDelegate().fluidOutputTank(index); }
	@Override public void productivebeesgenesis$onAe2FluidPushComplete(
	) {
		TileEntityExtraFactoryDelegates.onAe2FluidPushComplete(getOrCreateDelegate());
	}
	@Override public boolean isFluidTankTypeMismatch(FluidStack stack) {
		return getOrCreateDelegate().isFluidTankTypeMismatch(stack);
	}
	@Override public boolean areAllFluidTanksFull() { return getOrCreateDelegate().areAllFluidTanksFull(); }
	@Override public boolean canAllocateNewFluidTank() { return getOrCreateDelegate().canAllocateNewFluidTank(); }
	@Override public void setOrphanedMultiFluidTanksNbt(@Nullable CompoundTag nbt) {
		getOrCreateDelegate().setOrphanedMultiFluidTanksNbt(nbt);
	}
	@Override
	public @Nullable CompoundTag getOrphanedMultiFluidTanksNbt() {
		return getOrCreateDelegate().getOrphanedMultiFluidTanksNbt();
	}
	@Override public int processes() { return tier.processes; }
	@Override public int baseTicksRequired() { return BASE_TICKS_REQUIRED; }

	@Override
	public void setPbActiveState(boolean active, int process) {
		if (active) {
			productivebeesgenesis$onProcessActivated(process);
		} else {
			productivebeesgenesis$onProcessDeactivated(process);
		}
		setActiveState(active, process);
	}

	@Override public int productivityModifier() {
		return CentrifugeFactoryCommonLogic.productivityModifier(pbUpgradeDelegate);
	}
	@Override public int productivityParallelModifier() { return pbUpgradeDelegate.getProductivityParallelModifier(); }
	@Override public int operationsPerTick() {
		return CentrifugeFactoryCommonLogic.operationsPerTick(this, BASE_TICKS_REQUIRED);
	}

	/** 重写getOperationsPerTick — 委托给动态计算的operationsPerTick()，使SMELTING路径支持STACK升级 */
	@Override
	public void recalculateUpgrades(Upgrade upgrade) {
		super.recalculateUpgrades(upgrade);
		// 升级数量变更（MEK SPEED/ENERGY 等）时立即失效 PB 倍率缓存，与基础离心机/MEK 工厂路径保持一致
		pbUpgradeDelegate.invalidateMultiplierCache();
		MekCentrifugeEnergyScaling.normalizeCapacity(this);
	}

	@Override public int getOperationsPerTick() { return operationsPerTick(); }
	@Override public int getTicksForBase(int baseTime) {
		return CentrifugeFactoryCommonLogic.getTicksForBase(this, baseTime, pbUpgradeDelegate);
	}
	@Override public boolean containsSmeltingInput(ItemStack input) {
		return TileEntityExtraFactoryDelegates.containsSmeltingInput(this, level, input);
	}
	@Override public FactoryPbContextDelegate productivebeesgenesis$getDelegate() { return delegate; }
	/** 获取批量收获状态管理器（与 AbstractMekCentrifugeFactory 对称,per-tile 独立实例） */
	public TickBatchSkipState productivebeesgenesis$getTickBatchSkipState() { return tickBatchSkipState; }
	/** 返回自身的 pbProcessor — 字段隐藏修复:子类重新声明了 pbProcessor,必须 override 返回自身实例 */
	@Override public PbRecipeProcessor productivebeesgenesis$getPbProcessor() { return pbProcessor; }

	// ===== GUI暴露方法 =====

	@Nullable
	public IInventorySlot getSecondaryOutputSlot(
		int processIndex
	) {
		return processInfoSlots[processIndex].secondaryOutputSlot();
	}
	@NotNull public IInventorySlot getTertiaryOutputSlot(int processIndex) { return tertiaryOutputSlots[processIndex]; }
	@NotNull public IExtendedFluidTank getFluidOutputTank() { return getOrCreateDelegate().getFluidOutputTank(); }

	// ===== IPbUpgradeProvider实现 + PB升级槽位访问（委托给pbUpgradeDelegate） =====

	@Override public int getPbUpgradeInstalledCount(PbUpgradeType type) {
		return pbUpgradeDelegate.getPbUpgradeInstalledCount(type);
	}
	@Override public int getPbUpgradeLimit(PbUpgradeType type) { return pbUpgradeDelegate.getPbUpgradeLimit(type); }
	@Override public float getClientInstallingProgress() { return pbUpgradeDelegate.getClientInstallingProgress(); }
	@Override public float stabilityBonus() { return pbUpgradeDelegate.getStabilityBonus(); }
	@Override public float getClientUninstallingProgress() { return pbUpgradeDelegate.getClientUninstallingProgress(); }
	@Override public boolean isPbUpgradeSupported(PbUpgradeType type) {
		return pbUpgradeDelegate.isPbUpgradeSupported(type);
	}

	/** 获取PB升级输入槽 — 供Container创建虚拟槽位 */
	@NotNull public PbUpgradeInventorySlot getPbUpgradeInputSlot() { return pbUpgradeDelegate.getPbUpgradeInputSlot(); }

	/** 获取PB升级输出槽 — 供Container创建虚拟槽位 */
	@NotNull public PbUpgradeInventorySlot getPbUpgradeOutputSlot() { return pbUpgradeDelegate.getPbUpgradeOutputSlot(); }

	/** 卸载指定类型的PB升级到输出槽 — 供网络包调用 */
	public boolean extractPbUpgradeByType(PbUpgradeType type) { return pbUpgradeDelegate.extractPbUpgradeByType(type); }

	/**
	 * 批量安装 PB 升级 — 由 Mixin 拦截 PB 原版 useOn 后调用
	 * @param type 升级类型;@param maxAvailable 持有数量(stack.getCount());@return 实际安装数量(0 表示未安装)
	 */
	public int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		return pbUpgradeDelegate.installPbUpgradeBulk(type, maxAvailable);
	}

	/** IUpgradeableBlockEntity — 返回PB原版安装桥接器，委托给自定义升级系统 */
	@NotNull @Override public IItemHandlerModifiable getUpgradeHandler() { return pbUpgradeDelegate.getInstallHandler(); }
}
