package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
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
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.IHasEjectorCooldown;
import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.PbConfigCardDataHelper;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityElectricMachineAccessor;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * MEK通用机械蜂箱方块实体 — 继承 TileEntityElectricMachine，复用能量/侧面/升级/GUI 体系。
 * 生产周期 1200 ticks。组件架构（SRP）：ApiarySlotManager、FeederSlotManager、ApiaryTickHandler、
 * BeeProduceProcessor、ApiaryUpgradeHandler、ApiaryAe2HostAdapter、ApiaryPbUpgradeHandler、ApiaryNbtSerializer。
 */
public class TileEntityMekApiary extends TileEntityElectricMachine implements IAe2OutputHostBase, IUpgradeableBlockEntity, IMekApiaryTile, IHasEjectorCooldown, IPbUpgradeProvider, com.ayoshiko.productivebeesgenesis.ICustomDataPersistable {

	/** 生产周期：1200 ticks = 60秒（MEK原版标准） */
	public static final int APIARY_TICKS_REQUIRED = 1200;

	private static final LogThrottle AE2_ERROR_THROTTLE = new LogThrottle(100L, 5000L);

	protected ApiarySlotManager slotManager;
	protected FeederSlotManager feederSlotManager;
	protected ApiaryUpgradeHandler upgradeHandler;
	protected BeeProduceProcessor produceProcessor;
	protected ApiaryTickHandler tickHandler;
	private final ApiaryAe2HostAdapter ae2HostAdapter = new ApiaryAe2HostAdapter(this);
	/** 蜂箱→离心机直连快速弹出通道 — 相邻离心机时绕过Ejector节流直接转移蜜脾 */
	private final ApiaryDirectEjectHandler directEjectHandler = new ApiaryDirectEjectHandler(this);
	/** PB升级处理器 — 安装/卸载/NBT迁移（Bug 6 核心数据结构持有者） */
	final ApiaryPbUpgradeHandler pbUpgradeHandler;
	/** PB原版安装桥接器 — 使PB原版潜行右键安装委托给自定义升级系统 */
	private final PbUpgradeInstallHandler pbUpgradeInstallHandler;
	private final ApiaryNbtSerializer nbtSerializer;
	/** F4: 产物溢出缓冲区 — 缓存输出槽满载时的剩余产物，下 tick 重试注入 */
	private final ApiaryOutputBuffer outputBuffer = new ApiaryOutputBuffer(this);
	/** Bug 9：选中的蜜蜂槽位索引（-1=未选择），跨线程访问需 volatile 保证可见性 */
	private volatile int selectedBeeSlot = -1;
	/** 客户端同步用：选中蜜蜂槽位（仅服务端同步回调写入，GUI 通过 getter 读取），跨线程访问需 volatile */
	private volatile int clientSelectedBeeSlot = -1;

	public TileEntityMekApiary(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state, APIARY_TICKS_REQUIRED);
		// 协作组件初始化（super() 期间已通过 getInitialInventory() 懒初始化 slotManager）
		pbUpgradeHandler = new ApiaryPbUpgradeHandler(this);
		// 桥接PB原版安装入口到自定义升级系统（this::installPbUpgrade 委托给 pbUpgradeHandler）
		pbUpgradeInstallHandler = new PbUpgradeInstallHandler(this, this::installPbUpgrade);
		nbtSerializer = new ApiaryNbtSerializer(this);
		feederSlotManager = createFeederSlotManager();
		feederSlotManager.buildFeederSlots(this::setChanged);
		upgradeHandler = new ApiaryUpgradeHandler(this);
		produceProcessor = new BeeProduceProcessor(upgradeHandler);
		tickHandler = new ApiaryTickHandler(this, slotManager, produceProcessor, upgradeHandler, feederSlotManager);
		setupSideConfig();
	}

	/** 创建喂食器槽位管理器 — 工厂版子类重写返回工厂版参数 */
	protected FeederSlotManager createFeederSlotManager() { return new FeederSlotManager(); }

	/** 创建槽位管理器 — 模板方法，工厂版子类重写 */
	protected ApiarySlotManager createSlotManager() { return new ApiarySlotManager(this); }

	/** 懒初始化槽位管理器 — super()构造期间通过getInitialInventory()触发 */
	protected ApiarySlotManager slotManager() {
		if (slotManager == null) slotManager = createSlotManager();
		return slotManager;
	}

	/** 包私有 — 供同包组件访问 */
	ApiarySlotManager getSlotManager() { return slotManager; }

	/** F4: 获取产物溢出缓冲区 — 供同包组件（NbtSerializer/TickHandler/SlotTickProcessor）访问 */
	ApiaryOutputBuffer getOutputBuffer() { return outputBuffer; }

	/** 失效所有蜂箱槽位上限缓存 — 委托 ApiarySlotManager.invalidateCache()，配置 reload 时调用 */
	public static void invalidateSlotManagerCache() {
		ApiarySlotManager.invalidateCache();
	}

	TileEntityElectricMachineAccessor accessor() { return (TileEntityElectricMachineAccessor) this; }
	boolean callSuperOnUpdateServer() { return super.onUpdateServer(); }
	void callSetActive(boolean active) { setActive(active); }
	boolean callSuperCanFunction() { return super.canFunction(); }
	/** 包私有 — 供 NbtSerializer 设置父类 protected redstone 字段（boolean 类型） */
	void setRedstoneControl(boolean value) { redstone = value; }
	/** 包私有 — 供 NbtSerializer 调用父类 protected setOperatingTicks */
	void callSetOperatingTicks(int value) { setOperatingTicks(value); }

	/** 设置蜂箱侧面配置和弹出器 — 覆盖父类单输入/输出配置；蜂笼输出槽不参与弹出；tickDelay=1 由 Mixin 动态调整 */
	private void setupSideConfig() {
		// 物品 IO 配置：蜂笼输入槽作为输入，仅产物输出槽作为输出（蜂笼输出槽不参与 Ejector 弹出）
		List<mekanism.api.inventory.IInventorySlot> outputSlots = new ArrayList<>();
		outputSlots.addAll(slotManager.getOutputSlots());
		configComponent.setupItemIOConfig(
				Collections.singletonList(slotManager.getCageInSlot()),
				outputSlots,
				slotManager.getEnergySlot(), false);
		// 能量输入配置
		configComponent.setupInputConfig(TransmissionType.ENERGY, accessor().productivebeesgenesis$getEnergyContainer());
		// 流体输出配置（右侧）
		configComponent.setupOutputConfig(TransmissionType.FLUID, slotManager.getFluidTank(), RelativeSide.RIGHT);
		// 创建弹出器组件，设置 tickDelay 为 1（实际延迟由 Mixin 动态调整）
		ejectorComponent = new TileComponentEjector(this);
		((TileEntityEjectorAccessor) ejectorComponent).productivebeesgenesis$setTickDelay(1);
		// 同时弹出物品和流体
		ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
	}

	/** 获取配方类型 — 占位返回SMELTING，蜂箱产出由 BeeProduceProcessor 处理 */
	@NotNull @Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekanismRecipeType.SMELTING;
	}

	/** 重写警告检查 — 蜂箱不走 CachedRecipe 管线，手动映射 BeeState 到 RecipeError（Bug 10/1/5） */
	@Override
	public BooleanSupplier getWarningCheck(RecipeError error) {
		if (error == RecipeError.NOT_ENOUGH_OUTPUT_SPACE) return () -> slotManager != null && slotManager.isOutputFull();
		if (error == RecipeError.NOT_ENOUGH_ENERGY) return () -> hasBeeInState(BeeState.WAITING_ENERGY);
		if (error == RecipeError.NOT_ENOUGH_INPUT) return () -> hasBeeInState(BeeState.WAITING_FLOWER);
		return super.getWarningCheck(error);
	}

	/** 检查是否有蜜蜂处于指定状态 */
	private boolean hasBeeInState(BeeState state) {
		if (slotManager == null) return false;
		for (BeeSlot slot : slotManager.getBeeSlots()) {
			if (slot.getState() == state) return true;
		}
		return false;
	}

	@Nullable @Override
	public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() { return null; }

	@NotNull @Override
	protected IInventorySlotHolder getInitialInventory(@NotNull IContentsListener l, @NotNull IContentsListener r,
		@NotNull IContentsListener u) { return new ApiaryCapabilityProvider(this::slotManager).buildInventory(l, r, u); }

	@NotNull @Override
	protected IFluidTankHolder getInitialFluidTanks(@NotNull IContentsListener l, @NotNull IContentsListener r,
		@NotNull IContentsListener u) { return new ApiaryCapabilityProvider(this::slotManager).buildFluidTanks(l, r, u); }

	@Override protected boolean onUpdateServer() {
		try { ae2HostAdapter.tryConnectNode(); } catch (Exception e) { logAe2(e, "tryConnectNode"); }
		try { ae2HostAdapter.refreshAe2ConfigCache(); } catch (Exception e) { logAe2(e, "refreshAe2ConfigCache"); }
		// Bug 1 修复：先产出→直连弹出(优先于 AE2)→AE2 推送；原顺序直连弹出在生产前只能处理上一 tick 残留
		boolean result = tickHandler.onUpdateServer();
		try { directEjectHandler.tryDirectEject(); } catch (Exception e) { logAe2(e, "tryDirectEject"); }
		try { ae2HostAdapter.pushOutputs(); } catch (Exception e) { logAe2(e, "pushOutputs"); }
		return result;
	}
	private void logAe2(Exception e, String n) {
		// NPE 防御:getLevel() 在方块实体卸载后可能返回 null(参考 MEK BlockEntity 源码),
		// tryLog 失败时降级为直接日志输出,避免日志记录本身引发二次异常
		Level level = getLevel();
		if (level != null) {
			AE2_ERROR_THROTTLE.tryLog(level.getGameTime(), s -> ProductiveBeesGenesis.LOGGER.error("AE2 {} 异常", n, e));
		} else {
			ProductiveBeesGenesis.LOGGER.error("AE2 {} 异常(关卡已卸载,跳过节流)", n, e);
		}
	}

	/** 标记直连弹出检测为脏 — 委托 ApiaryDirectEjectHandler.markEjectDirty()，下 tick 立即执行 */
	void markDirectEjectDirty() {
		directEjectHandler.markEjectDirty();
	}

	/** 容器数据同步 — 蜜蜂状态 + PB升级数量 + 安装计数器 + 选中槽位 */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		super.addContainerTrackers(container);
		slotManager.addContainerTrackers(container);
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (type.isBuiltin()) continue;
			container.track(SyncableInt.create(
					() -> pbUpgradeHandler.getPbUpgradeCount(type),
					count -> pbUpgradeHandler.setClientUpgradeCount(type, count)));
		}
		container.track(SyncableInt.create(pbUpgradeHandler::getInstallTicks, pbUpgradeHandler::setClientUpgradeTicks));
		container.track(SyncableInt.create(() -> selectedBeeSlot, v -> clientSelectedBeeSlot = v));
		// per-tile AE2 输出开关同步（无条件添加避免客户端/服务端 tracker 数量不一致）
		container.track(SyncableBoolean.create(
				ae2HostAdapter::isAeItemOutputEnabled,
				ae2HostAdapter::setAeItemOutputEnabled));
		container.track(SyncableBoolean.create(
				ae2HostAdapter::isAeFluidOutputEnabled,
				ae2HostAdapter::setAeFluidOutputEnabled));
	}

	// ===== NBT 持久化 — 委托给 nbtSerializer + pbUpgradeHandler =====

	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		nbtSerializer.saveApiaryState(nbt, provider);
		ae2HostAdapter.saveNodeNBT(nbt);
		ae2HostAdapter.savePerTileState(nbt);
	}

	@NotNull @Override
	public CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		return nbtSerializer.saveCustomData(provider);
	}

	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		nbtSerializer.loadApiaryState(nbt, provider);
		ae2HostAdapter.loadNodeNBT(nbt);
		ae2HostAdapter.loadPerTileState(nbt);
	}

	/** 保存PB升级数量映射 — protected 供工厂版子类调用 */
	protected void savePbUpgradeCounts(@NotNull CompoundTag nbt) { pbUpgradeHandler.savePbUpgradeCounts(nbt); }
	/** 加载PB升级数量 — protected 供工厂版子类调用，兼容历史格式 */
	protected void loadPbUpgradeCounts(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) { pbUpgradeHandler.loadPbUpgradeCounts(nbt, provider); }

	// ===== 升级数据保存/恢复 — 委托给 nbtSerializer =====

	/** 升级数据中的排序开关 — 模板方法，基础版固定 false，工厂版重写返回 isSorting() */
	protected boolean getSortingForUpgradeData() {
		return false;
	}

	/**
	 * 升级数据恢复排序开关 — 模板方法
	 * <br/>
	 * 修复 MEDIUM-2: 由 {@link ApiaryNbtSerializer#applyUpgradeData} 调用以显式恢复 SORTING 字段。
	 * 基础蜂箱无 sorting 字段,此方法为 no-op;工厂版重写以实际设置 sorting 字段。
	 * <p>
	 * 设计原则：开闭原则（OCP）— 通过模板方法扩展,不修改 ApiaryNbtSerializer 调用逻辑。
	 *
	 * @param sorting 升级数据中的 sorting 状态
	 */
	protected void setSortingFromUpgradeData(boolean sorting) {
		// no-op：基础蜂箱无 sorting 字段
	}

	@NotNull @Override
	public ApiaryUpgradeData getUpgradeData(HolderLookup.Provider provider) {
		ApiaryUpgradeData data = nbtSerializer.buildUpgradeData(provider, redstone, getSortingForUpgradeData());
		// 模块 3 Bug 2：构建升级数据后立即清空旧方块所有槽位，防止 setRemoved 触发 Ejector 重复 popResource
		// outputItems 字段已是深拷贝（独立于 outputSlots 引用），清空槽位不影响升级数据完整性
		saveAllItemsForDrop();
		return data;
	}

	@Override
	public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		if (!nbtSerializer.applyUpgradeData(provider, upgradeData)) super.parseUpgradeData(provider, upgradeData);
	}

	/**
	 * 保存全部数据后清空所有槽位（模块 3 Bug 1 + Bug 2）
	 * <br/>
	 * 供 {@link MekApiaryBlock#getDrops}（镐子破坏/扳手拆卸）和 {@link #getUpgradeData}
	 * （ItemTierInstaller 升级）在保存 BLOCK_ENTITY_DATA / 升级数据后调用，
	 * 防止 {@link #setRemoved} 触发 Ejector 组件 {@code popResource} 重复掉落物品到世界。
	 * <p>
	 * 清空范围：蜜蜂槽、喂食槽、PB升级槽（输入+输出）、产物输出槽、蜂笼输入槽、蜂笼输出槽、
	 * 能量槽、流体罐、产物缓冲区（outputBuffer）、选中槽位。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>性能：直接清空槽位，不重复序列化（数据已通过 BLOCK_ENTITY_DATA / 升级数据保存）</li>
	 *   <li>安全：清空所有可能持有物品的槽位，避免遗漏导致 Ejector 重复 popResource</li>
	 *   <li>异常处理：单点异常不影响其他槽位清空（防御性 try-catch）</li>
	 * </ul>
	 */
	public void saveAllItemsForDrop() {
		// 蜜蜂槽数组清空（BeeSlot.clear() 重置全部字段并标记 dirty）
		try {
			for (BeeSlot slot : slotManager.getBeeSlots()) {
				slot.clear();
			}
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空蜜蜂槽异常", e);
		}
		// 喂食槽清空
		try {
			for (FeederInventorySlot slot : feederSlotManager.getFeederInventorySlots()) {
				slot.setStack(ItemStack.EMPTY);
			}
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空喂食槽异常", e);
		}
		// PB 升级输入/输出槽清空
		try {
			pbUpgradeHandler.getInputSlot().setStack(ItemStack.EMPTY);
			pbUpgradeHandler.getOutputSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空 PB 升级槽异常", e);
		}
		// 产物输出槽清空
		try {
			for (BasicInventorySlot slot : slotManager.getOutputSlots()) {
				slot.setStack(ItemStack.EMPTY);
			}
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空产物输出槽异常", e);
		}
		// 蜂笼输入/输出槽清空
		try {
			slotManager.getCageInSlot().setStack(ItemStack.EMPTY);
			slotManager.getCageOutSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空蜂笼槽异常", e);
		}
		// 能量槽清空
		try {
			slotManager.getEnergySlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空能量槽异常", e);
		}
		// 流体罐清空（setEmpty 内部调用 setStack(FluidStack.EMPTY)，触发 onContentsChanged）
		try {
			slotManager.getFluidTank().setEmpty();
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空流体罐异常", e);
		}
		// 产物溢出缓冲区清空（不掉落，仅清空内存引用）
		try {
			outputBuffer.clear();
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空产物缓冲区异常", e);
		}
		// 选中蜜蜂槽位重置为未选择
		setSelectedBeeSlot(-1);
		setChanged();
	}

	// ===== GUI 访问接口（供 Container/Screen 使用） =====

	@NotNull public IExtendedFluidTank getFluidTank() { return slotManager.getFluidTank(); }
	@NotNull public EnergyInventorySlot getEnergySlot() { return slotManager.getEnergySlot(); }
	@NotNull public BasicInventorySlot getCageInSlot() { return slotManager.getCageInSlot(); }
	@NotNull public BasicInventorySlot getCageOutSlot() { return slotManager.getCageOutSlot(); }
	@NotNull public List<BasicInventorySlot> getOutputSlots() { return slotManager.getOutputSlots(); }
	@NotNull public BeeSlot[] getBeeSlots() { return slotManager.getBeeSlots(); }
	@NotNull public BeeSlot getBeeSlot(int index) { return slotManager.getBeeSlot(index); }
	public int getBeeSlotCount() { return slotManager.getBeeSlotCount(); }
	public int getBeeCols() { return slotManager.getBeeCols(); }
	public int getBeeRows() { return slotManager.getBeeRows(); }
	public int getOutputCols() { return slotManager.getOutputCols(); }
	public int getOutputRows() { return slotManager.getOutputRows(); }
	@NotNull public FeederSlotManager getFeederSlotManager() { return feederSlotManager; }
	@NotNull public List<IInventorySlot> getFeederSlots() { return feederSlotManager.getFeederSlots(); }
	@NotNull List<FeederInventorySlot> getFeederInventorySlots() { return feederSlotManager.getFeederInventorySlots(); }

	/** IUpgradeableBlockEntity — 返回PB原版安装桥接器，拦截 insertItem 委托 installPbUpgrade，由 EnumMap 管理数量 */
	@NotNull @Override
	public IItemHandlerModifiable getUpgradeHandler() { return pbUpgradeInstallHandler; }

	@NotNull public ApiaryUpgradeHandler getApiaryUpgradeHandler() { return upgradeHandler; }
	/** 已废弃 — 返回 null 保持兼容性 */
	@Nullable
	public cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.UpgradeHandler getPbUpgradeHandler() { return null; }
	@NotNull public PbUpgradeInventorySlot getPbUpgradeInputSlot() { return pbUpgradeHandler.getInputSlot(); }
	@NotNull public PbUpgradeInventorySlot getPbUpgradeOutputSlot() { return pbUpgradeHandler.getOutputSlot(); }

	// ===== Bug 6：PB 升级安装/卸载 API — 委托给 pbUpgradeHandler =====

	public boolean installPbUpgrade(PbUpgradeType type) { return pbUpgradeHandler.installPbUpgrade(type); }
	/**
	 * 批量安装 PB 升级 — 由 Mixin 拦截 PB 原版 useOn 后调用
	 *
	 * @param type         升级类型
	 * @param maxAvailable 手上持有的最大数量（{@code stack.getCount()}）
	 * @return 实际安装数量（0 表示未安装）
	 */
	public int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) { return pbUpgradeHandler.installPbUpgradeBulk(type, maxAvailable); }
	@NotNull public List<ItemStack> removePbUpgrade(PbUpgradeType type, boolean removeAll) { return pbUpgradeHandler.removePbUpgrade(type, removeAll); }
	public int getPbUpgradeCount(PbUpgradeType type) { return pbUpgradeHandler.getPbUpgradeCount(type); }
	public void processPbUpgradeInput() { pbUpgradeHandler.processPbUpgradeInput(); }
	public boolean extractPbUpgradeByType(PbUpgradeType type) { return pbUpgradeHandler.extractPbUpgradeByType(type); }
	public int getPbUpgradeLimit(PbUpgradeType type) { return pbUpgradeHandler.getPbUpgradeLimit(type); }
	public void tickPbUpgradeAnim() { pbUpgradeHandler.tickPbUpgradeAnim(); }
	public float getClientInstallingProgress() { return pbUpgradeHandler.getClientInstallingProgress(); }
	public float getClientUninstallingProgress() { return pbUpgradeHandler.getClientUninstallingProgress(); }

	// ===== IPbUpgradeProvider 实现 — 蜂箱支持所有非内置升级 =====

	@Override
	public int getPbUpgradeInstalledCount(PbUpgradeType type) { return getPbUpgradeCount(type); }
	@Override
	public boolean isPbUpgradeSupported(PbUpgradeType type) {
		// STABILITY 仅离心机生效，蜂箱不接受（对齐 PB 原版 AdvancedBeehiveBlockEntity 不含 stability 白名单）
		return type != null && !type.isBuiltin() && type != PbUpgradeType.STABILITY;
	}

	// ===== 选中蜜蜂槽位 + 桶式操作 =====

	public int getSelectedBeeSlot() { return selectedBeeSlot; }
	/** 客户端同步的选中蜜蜂槽位 — 供 GUI 读取（封装 clientSelectedBeeSlot） */
	public int getClientSelectedBeeSlot() { return clientSelectedBeeSlot; }
	public void setSelectedBeeSlot(int index) {
		if (selectedBeeSlot != index) {
			selectedBeeSlot = index;
			setChanged();
		}
	}
	public ItemStack cageBeeAtSlot(int slotIndex, ItemStack cursorCage) { return slotManager.tryCageBeeAtSlot(slotIndex, cursorCage); }
	public void confirmCageExtraction(int slotIndex) { slotManager.confirmCageExtraction(slotIndex); }
	public boolean releaseBeeAtSlot(int slotIndex, ItemStack cursorCage) { return slotManager.tryReleaseBeeAtSlot(slotIndex, cursorCage); }
	@NotNull public BeeProduceProcessor getProduceProcessor() { return produceProcessor; }

	/**
	 * 配置卡兼容性 — 允许所有等级蜂箱间互相粘贴配置（通过 instanceof MekApiaryBlock 判断）
	 */
	@Override
	public boolean isConfigurationDataCompatible(@NotNull Block blockType) {
		return super.isConfigurationDataCompatible(blockType) || blockType instanceof MekApiaryBlock;
	}

	/** 写入配置卡数据 — 追加PB升级数量和AE2 per-tile状态到 MEK 配置卡 */
	@Override
	public void writeSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.writeSustainedData(provider, data);
		PbConfigCardDataHelper.writePbUpgrades(data, pbUpgradeHandler.getPbUpgradeCounts(),
				PbConfigCardDataHelper.MachineType.APIARY);
		PbConfigCardDataHelper.writeAe2PerTileState(data,
				ae2HostAdapter.isAeItemOutputEnabled(), ae2HostAdapter.isAeFluidOutputEnabled());
	}

	/** 从配置卡读取 — 恢复AE2 per-tile状态（PB升级粘贴在 setConfigurationData 中处理） */
	@Override
	public void readSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.readSustainedData(provider, data);
		boolean[] ae2State = PbConfigCardDataHelper.readAe2PerTileState(data);
		if (ae2State != null) {
			ae2HostAdapter.setAeItemOutputEnabled(ae2State[0]);
			ae2HostAdapter.setAeFluidOutputEnabled(ae2State[1]);
		}
	}

	/** 设置配置卡数据 — 重写获取 Player 参数，处理PB升级粘贴（生存模式消耗物品，创造模式直接安装） */
	@Override
	public void setConfigurationData(@NotNull HolderLookup.Provider provider,
			@Nullable net.minecraft.world.entity.player.Player player,
			@NotNull CompoundTag data) {
		super.setConfigurationData(provider, player, data);
		// 粘贴PB升级（生存模式消耗物品，创造模式直接安装）
		PbConfigCardDataHelper.readAndApplyPbUpgrades(data, player,
				this::installPbUpgrade,
				this::clearAllPbUpgrades,
				PbConfigCardDataHelper.MachineType.APIARY);
	}

	/**
	 * 清空所有已安装PB升级 — 供配置卡粘贴前调用
	 * <br/>
	 * 修复物品守恒：removePbUpgrade 返回的 ItemStack 列表必须消费，
	 * 由调用方（PbConfigCardDataHelper.readAndApplyPbUpgrades）注入玩家物品栏或掉落地面。
	 *
	 * @return 被清空的 PB 升级物品栈列表
	 */
	private java.util.List<ItemStack> clearAllPbUpgrades() {
		java.util.List<ItemStack> dropped = new java.util.ArrayList<>();
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (!type.isBuiltin() && pbUpgradeHandler.getPbUpgradeCount(type) > 0) {
				dropped.addAll(pbUpgradeHandler.removePbUpgrade(type, true));
			}
		}
		return dropped;
	}

	/** 保存AE2 per-tile状态到NBT — 供 ApiaryNbtSerializer 扳手拆卸持久化调用 */
	void saveAe2PerTileState(CompoundTag nbt) {
		ae2HostAdapter.savePerTileState(nbt);
	}

	// ===== AE2 生命周期与 IAe2OutputHostBase 实现 — 委托给 ae2HostAdapter =====

	@Override public void clearRemoved() { super.clearRemoved(); ae2HostAdapter.prepareForLoad(); }
	@Override public void setRemoved() {
		super.setRemoved();
		// 模块 2 修复（v2.4 最终版）：移除 outputBuffer.dumpToWorld 调用
		// v2.3 的 dumpToWorld 是项目中唯一的 Block.popResource 源，导致创造模式/镐子破坏时
		// 缓冲区物品爆出到世界。缓冲区内容已通过 saveAdditional 序列化到 NBT，
		// 方块破坏时随 BLOCK_ENTITY_DATA 保存到掉落物（生存模式）或销毁（创造模式），
		// 不需要 popResource 兜底。与 MEK 原版 setRemoved 行为一致（不 popResource）。
		// onRemove 中的 saveAllItemsForDrop 仍保留，作为防御性措施清空槽位，防止未来引入新的 popResource 路径。
		ae2HostAdapter.destroyForRemoval();
	}
	@Override public void onChunkUnloaded() { super.onChunkUnloaded(); ae2HostAdapter.destroyForChunkUnload(); }
	@Override public MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler() { return ae2HostAdapter.getAe2LifecycleHandler(); }
	@Override public MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource() { return ae2HostAdapter.getAe2EnergySource(); }
	@Override public Level productivebeesgenesis$getAe2Level() { return ae2HostAdapter.getAe2Level(); }
	@Override public BlockPos productivebeesgenesis$getAe2BlockPos() { return ae2HostAdapter.getAe2BlockPos(); }
	@Override public boolean productivebeesgenesis$isOutputPushEnabled() { return ae2HostAdapter.isOutputPushEnabled(); }
	@Override public boolean productivebeesgenesis$isFluidPushEnabled() { return ae2HostAdapter.isFluidPushEnabled(); }
	@Override public void productivebeesgenesis$injectAe2Energy() { ae2HostAdapter.injectAe2Energy(); }
	@Override public boolean productivebeesgenesis$getPreferAppliedFluxOverAeEnergy() { return ae2HostAdapter.getPreferAppliedFluxOverAeEnergy(); }
	@Override public boolean productivebeesgenesis$isAeItemOutputEnabled() { return ae2HostAdapter.isAeItemOutputEnabled(); }
	@Override public boolean productivebeesgenesis$isAeFluidOutputEnabled() { return ae2HostAdapter.isAeFluidOutputEnabled(); }
	@Override public void productivebeesgenesis$setAeItemOutputEnabled(boolean enabled) { ae2HostAdapter.setAeItemOutputEnabled(enabled); }
	@Override public void productivebeesgenesis$setAeFluidOutputEnabled(boolean enabled) { ae2HostAdapter.setAeFluidOutputEnabled(enabled); }

	/** 模块2.4：AE2 输出超限时暂停蜂箱输入 — 转发到 Mekanism 的 setActive(false) */
	@Override
	public void suspendInput() {
		setActive(false);
	}

	/** 切换 per-tile AE2 物品输出开关（供网络包 handler 调用） */
	public void toggleAeItemOutput() { ae2HostAdapter.toggleAeItemOutput(); markForSave(); }
	/** 切换 per-tile AE2 流体输出开关（供网络包 handler 调用） */
	public void toggleAeFluidOutput() { ae2HostAdapter.toggleAeFluidOutput(); markForSave(); }

	// ===== PbRecipeContext 接口实现 — 委托给 ae2HostAdapter =====

	@Override public Level level() { return ae2HostAdapter.getPbRecipeAdapter().level(); }
	@Override public MachineEnergyContainer<?> energyContainer() { return ae2HostAdapter.getPbRecipeAdapter().energyContainer(); }
	@Override public boolean hasCreativeUpgrade() { return false; }

	@Override public int processes() { return ae2HostAdapter.getPbRecipeAdapter().processes(); }
	@Override public IInventorySlot inputSlot(int process) { return ae2HostAdapter.getPbRecipeAdapter().inputSlot(process); }
	@Override public IInventorySlot primaryOutputSlot(int process) { return ae2HostAdapter.getPbRecipeAdapter().primaryOutputSlot(process); }
	@Override public IInventorySlot secondaryOutputSlot(int process) { return ae2HostAdapter.getPbRecipeAdapter().secondaryOutputSlot(process); }
	@Override public IInventorySlot tertiaryOutputSlot(int process) { return ae2HostAdapter.getPbRecipeAdapter().tertiaryOutputSlot(process); }
	@Override public IExtendedFluidTank fluidOutputTank() { return ae2HostAdapter.getPbRecipeAdapter().fluidOutputTank(); }
	@Override public int baseTicksRequired() { return ae2HostAdapter.getPbRecipeAdapter().baseTicksRequired(); }
	@Override public boolean canFunction() { return ae2HostAdapter.getPbRecipeAdapter().canFunction(); }
	@Override public void setPbActiveState(boolean active, int process) { ae2HostAdapter.getPbRecipeAdapter().setPbActiveState(active, process); }
	@Override public int productivityModifier() { return ae2HostAdapter.getPbRecipeAdapter().productivityModifier(); }
	@Override public int operationsPerTick() { return ae2HostAdapter.getPbRecipeAdapter().operationsPerTick(); }
	@Override public int getTicksForBase(int baseTime) { return ae2HostAdapter.getPbRecipeAdapter().getTicksForBase(baseTime); }
	@Override public boolean containsSmeltingInput(ItemStack input) { return ae2HostAdapter.getPbRecipeAdapter().containsSmeltingInput(input); }
	@Override public boolean productivebeesgenesis$hasOutputItems() { return ae2HostAdapter.hasOutputItems(); }
	@Override public void productivebeesgenesis$updateOutputSlotFlags() { ae2HostAdapter.updateOutputSlotFlags(); }
	@Override public void productivebeesgenesis$beginOutputBatch() { ae2HostAdapter.beginOutputBatch(); }
	@Override public void productivebeesgenesis$endOutputBatch(int process) { ae2HostAdapter.endOutputBatch(process); }
	@Override public void productivebeesgenesis$onProcessActivated(int process) { ae2HostAdapter.onProcessActivated(process); }
	@Override public void productivebeesgenesis$onProcessDeactivated(int process) { ae2HostAdapter.onProcessDeactivated(process); }
	@Override public boolean productivebeesgenesis$hasActiveProcess() { return ae2HostAdapter.hasActiveProcess(); }

	// ===== IMekApiaryTile — 供 Ejector Mixin 读取蜂箱输出槽状态 =====

	@Override public long productivebeesgenesis$outputContentsVersion() { return 0L; }
	@Override public boolean productivebeesgenesis$outputSlotsFull() { return slotManager != null && slotManager.isOutputFull(); }
	@Override public long productivebeesgenesis$outputItemCount() { return slotManager != null ? slotManager.outputItemCount() : 0L; }
}
