package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeData;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInstallHandler;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import com.ayoshiko.productivebeesgenesis.mek.ICachedRecipeBatchAccel;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityElectricMachineAccessor;
import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.config.MekanismConfig;
import mekanism.common.inventory.container.MekanismContainer;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
	 * 基础MEK离心机方块实体 — 继承TileEntityElectricMachine，复用能量/侧面配置/升级/GUI体系。
	 * 扩展PB离心配方查找：先查PB CentrifugeRecipe，未找到则回退到Mekanism SMELTING。额外添加2个副输出槽+FluidTank。
	 * 职责拆分：{@link MekCentrifugeSlotManager}（槽位）、{@link MekCentrifugeSaveHandler}（NBT/同步/配置卡/升级数据）、
	 * {@link MekCentrifugeTickHandler}（tick/PB配方）、{@link MekCentrifugeAe2Handler}（AE2）、
	 * {@link MekCentrifugePbUpgradeHandler}（PB升级）、{@link PbRecipeProcessor}（PB配方）、
	 * {@link MekUpgradeSupport}（升级查询）、{@link MekCentrifugeUpgradeOps}（STACK/CREATIVE 升级运算）。
	 * 单进程（processes()=1），active 由 onUpdateServer 管理，setPbActiveState 为 no-op。
	 */
public class TileEntityMekCentrifuge extends TileEntityElectricMachine
		implements IAe2InputHost, IAe2OutputHostBase, IMekCentrifugeTile, IPbUpgradeProvider, IUpgradeableBlockEntity,
		IMekCentrifugePbUpgradeHost, com.ayoshiko.productivebeesgenesis.ICustomDataPersistable {

	@Override
	public void productivebeesgenesis$onSmeltingCompatChanged() {
		pbProcessor.resetSmeltingCache(0);
		MekCentrifugeFactoryHelper.invalidateRecipeMonitor(recipeCacheLookupMonitor);
	}

	/** 输出槽/流体槽管理器 — 懒初始化（super() 构造期间通过 getInitialInventory() 触发） */
	private MekCentrifugeSlotManager slotManager;
	/** PB配方处理器 — 封装所有PB离心配方处理逻辑（与工厂版共用） */
	private final PbRecipeProcessor pbProcessor;
	/** 持久化处理器 — 封装 NBT/容器同步/配置卡/升级数据逻辑 */
	private final MekCentrifugeSaveHandler saveHandler;
	/** 服务端 tick 处理器 — 封装 onUpdateServer/PB配方处理逻辑 */
	private final MekCentrifugeTickHandler tickHandler;
	/** PB升级处理器 — 管理离心机PB专属升级（仅产量/速度系列） */
	private final MekCentrifugePbUpgradeHandler pbUpgradeHandler;
	/** PB原版安装桥接器 — 使PB原版潜行右键安装委托给自定义升级系统 */
	private final PbUpgradeInstallHandler pbUpgradeInstallHandler;
	/** AE2 集成处理器 — 封装网格节点生命周期、per-tile 开关切换与容器同步 */
	private final MekCentrifugeAe2Handler ae2Handler;

	/**
	 * 掉落数据已序列化标志 — getDrops 幂等防护
	 * <br/>
	 * getDrops 在 saveToItem 序列化掉落物后调用 saveAllItemsForDrop 清空槽位；
	 * 若同一方块被二次调用 getDrops（异常场景），标志避免对已清空的数据重复序列化。
	 */
	private boolean dropsSerialized;

	/** 掉落数据是否已序列化（getDrops 幂等防护） */
	public boolean isDropsSerialized() {
		return dropsSerialized;
	}

	/** 标记掉落数据已序列化（getDrops 幂等防护） */
	public void markDropsSerialized() {
		dropsSerialized = true;
	}

	/** 构造基础MEK离心机方块实体 */
	public TileEntityMekCentrifuge(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state, BASE_TICKS_REQUIRED);
		// super() 期间已通过 getInitialInventory() 懒初始化 slotManager，此处非空
		pbProcessor = new PbRecipeProcessor(this, "MEK离心机");
		ae2Handler = new MekCentrifugeAe2Handler(this);
		pbUpgradeHandler = new MekCentrifugePbUpgradeHandler(this);
		saveHandler = new MekCentrifugeSaveHandler(pbProcessor, this, pbUpgradeHandler, ae2Handler);
		// Task 4: 通过 IAe2InputHost.productivebeesgenesis$getTickAccelTracker() 获取加速检测器引用,
		// 传入 TickHandler 用于批量收获模式（虚拟 tick 银行 + 每 tick 预算）
		tickHandler = new MekCentrifugeTickHandler(this, pbProcessor,
				this.productivebeesgenesis$getTickAccelTracker());
		pbUpgradeInstallHandler = new PbUpgradeInstallHandler(this, pbUpgradeHandler::installPbUpgrade);
		// 重写侧面配置：3个输出槽。切勿将 setupItemIOConfig 移到 getInitialInventory 之前（副输出槽此时为 null 会 NPE）
		configComponent.setupItemIOConfig(
				java.util.Collections.singletonList(accessor().productivebeesgenesis$getInputSlot()),
				List.of(accessor().productivebeesgenesis$getOutputSlot(),
						slotManager.getSecondaryOutputSlot(),
						slotManager.getTertiaryOutputSlot()),
				accessor().productivebeesgenesis$getEnergySlot(), false);
		configComponent.setupInputConfig(TransmissionType.ENERGY, accessor().productivebeesgenesis$getEnergyContainer());
		configComponent.setupOutputConfig(TransmissionType.FLUID, slotManager.getFluidOutputTank(), RelativeSide.RIGHT);
		ejectorComponent = new TileComponentEjector(this, MekanismConfig.general.chemicalAutoEjectRate,
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
		((TileEntityEjectorAccessor) ejectorComponent).productivebeesgenesis$setTickDelay(1);
		ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
	}

	/** 获取Accessor — 供同包 SlotManager/TickHandler/SaveHandler/Ae2Handler 访问父类包私有字段 */
	TileEntityElectricMachineAccessor accessor() { return (TileEntityElectricMachineAccessor) this; }

	/** 懒初始化槽位管理器（super() 构造期间通过 getInitialInventory() 虚方法调用进入此处） */
	MekCentrifugeSlotManager slotManager() {
		if (slotManager == null) {
			slotManager = new MekCentrifugeSlotManager(this);
		}
		return slotManager;
	}

	/** 供 TickHandler 调用父类 onUpdateServer（protected 跨包不可直接访问） */
	boolean callSuperOnUpdateServer() { return super.onUpdateServer(); }

	/**
	 * 轻量 SMELTING 补调 — 仅推进已缓存的熔炉配方（batchMultiplier - 1 次额外配方 tick）。
	 * <br/>
	 * 对比补调完整 super（每 tick 含 ejector tick + 能量槽回填 + getUpdatedCache 配方重查），
	 * 本方法直接调用 {@link CachedRecipe#process()} 推进缓存配方：
	 * <ul>
	 *   <li>同一 gameTick 内输入不变，首次完整 super 已重查配方，跳过每 tick 的 isInputValid/查找</li>
	 *   <li>ejector 与能量回填每 gameTick 由首次完整 super 执行一次即可</li>
	 *   <li>无熔炉配方（空输入/PB 配方/万象蜜脾）时缓存为 null，直接跳过</li>
	 * </ul>
	 * 语义等价于 Mekanism 管线真实推进 batchMultiplier 次 tick，确保 256x 加速下 MSPT 占用极低。
	 *
	 * @param batchMultiplier 批量倍率（≥2 时才有补调意义）
	 * @return 是否有任意一次补调执行（调用方用于触发发送更新包）
	 */
	boolean runLightSmeltingTicks(int batchMultiplier) {
		CachedRecipe<ItemStackToItemStackRecipe> cached = recipeCacheLookupMonitor.getCachedRecipe(0);
		if (cached == null) {
			return false;
		}
		int extraTicks = batchMultiplier - 1;
		if (cached instanceof ICachedRecipeBatchAccel accel) {
			// 批量快速推进（JDTE 合并 flush 思路）：一次完整计算 + 预算内循环推进，
			// 跨周期自动重算，完整计算次数从 M 降到 ~M/配方时长；预算耗尽立即停止补调
			accel.productivebeesgenesis$startBatch(extraTicks);
			for (int i = 1; i < batchMultiplier; i++) {
				cached.process();
				if (accel.productivebeesgenesis$isBatchExhausted()) {
					return true;
				}
			}
			return true;
		}
		// Mixin 未应用（防御回退）：逐 tick 轻量推进
		for (int i = 1; i < batchMultiplier; i++) {
			cached.process();
		}
		return true;
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine} 合并接口委托（仅 JDTE 加载时经 Mixin 生效）。
	 * <br/>
	 * accumulate：仅入账虚拟 tick 银行；flush：强制执行一次完整批量（super + PB 配方 + AE2 推送）。
	 */
	public void productivebeesgenesis$accumulateAcceleratedTicks(int ticks) {
		tickHandler.accumulateAcceleratedTicks(ticks);
	}

	/** JDTE 合并接口 flush 委托（见 {@link #productivebeesgenesis$accumulateAcceleratedTicks}） */
	public void productivebeesgenesis$flushAcceleratedTicks() {
		tickHandler.flushAcceleratedTicks();
	}

	/** 供 TickHandler 调用 setActive（protected 跨包不可直接访问） */
	void callSetActive(boolean active) { setActive(active); }

	/** 供 TickHandler 访问 AE2 处理器 */
	MekCentrifugeAe2Handler ae2Handler() { return ae2Handler; }

	/** 供 TickHandler 访问 PB 升级处理器 */
	MekCentrifugePbUpgradeHandler pbUpgradeHandler() { return pbUpgradeHandler; }

	@NotNull
	@Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekanismRecipeType.SMELTING;
	}

	/** JEI配方查看器跳转支持 — 返回SMELTING类型使JEI中点击配方时能正确跳转 */
	@NotNull
	@Override
	public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return RecipeViewerRecipeType.SMELTING;
	}

	/**
	 * 重写containsRecipe — 同时查找Mekanism SMELTING和PB CentrifugeRecipe。
	 * <br/>
	 * Bug 2 修复：PB 蜜脾/蜜脾块（带 BEE_TYPE 组件或 CONFIGURABLE_COMB_BLOCK 物品）只检查 PB 配方，
	 * 避免 SMELTING 配方（c:honeycombs 标签）误匹配导致任意蜜脾都能放入并产出错误结果。
	 * 非 PB 蜜脾（如 modularbees）走原逻辑，允许 SMELTING 处理。
	 */
	@Override
	public boolean containsRecipe(@NotNull ItemStack input) {
		// 熔炉配方兼容关闭时只接受 PB 离心配方输入
		if (!MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this)) {
			return pbProcessor.findPbRecipe(input) != null;
		}
		// Bug 2: PB 蜜脾/蜜脾块强制走 PB 配方路径，避免 SMELTING（c:honeycombs 标签）误匹配
		if (productivebeesgenesis$isPbCombInput(input)) {
			return pbProcessor.findPbRecipe(input) != null;
		}
		return super.containsRecipe(input) || pbProcessor.findPbRecipe(input) != null;
	}

	/** 重写getRecipe — PB配方存在时返回null阻止SMELTING抢占（modularbees 为 c:honeycombs 注册熔炉配方被SMELTING包含） */
	@Nullable
	@Override
	public ItemStackToItemStackRecipe getRecipe(int cacheIndex) {
		// 熔炉配方兼容关闭时阻止 SMELTING 管线处理
		if (!MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(this)) {
			return null;
		}
		ItemStack input = accessor().productivebeesgenesis$getInputSlot().getStack();
		if (!input.isEmpty() && pbProcessor.findPbRecipe(input) != null) return null;
		return super.getRecipe(cacheIndex);
	}

	/** 重写getInitialInventory — 委托 {@link MekCentrifugeSlotManager#buildInventory}，添加2个副输出槽 */
	@NotNull
	@Override
	protected IInventorySlotHolder getInitialInventory(@NotNull IContentsListener listener,
			@NotNull IContentsListener recipeCacheListener,
			@NotNull IContentsListener recipeCacheUnpauseListener) {
		return slotManager().buildInventory(listener, recipeCacheListener, recipeCacheUnpauseListener);
	}

	/** 重写getInitialFluidTanks — 委托 {@link MekCentrifugeSlotManager#buildFluidTanks}，添加PB流体输出槽 */
	@NotNull
	@Override
	protected IFluidTankHolder getInitialFluidTanks(@NotNull IContentsListener listener,
			@NotNull IContentsListener recipeCacheListener,
			@NotNull IContentsListener recipeCacheUnpauseListener) {
		return slotManager().buildFluidTanks(listener, recipeCacheListener, recipeCacheUnpauseListener);
	}

	/** 获取流体输出槽 — GUI显示用 */
	@NotNull
	public IExtendedFluidTank getFluidOutputTank() { return slotManager.getFluidOutputTank(); }

	/** 获取副输出槽1 — GUI显示用（分等级堆叠倍率） */
	@NotNull
	public TieredOutputInventorySlot getSecondaryOutputSlot() { return slotManager.getSecondaryOutputSlot(); }

	/** 获取副输出槽2 — GUI显示用（分等级堆叠倍率） */
	@NotNull
	public TieredOutputInventorySlot getTertiaryOutputSlot() { return slotManager.getTertiaryOutputSlot(); }

	/** 重写getScaledProgress — PB配方处理时使用pbProcessor的进度（operatingTicks不被PB路径更新） */
	@Override
	public double getScaledProgress() {
		return pbProcessor.isPbProcessing(0) ? pbProcessor.getPbScaledProgress(1, 0) : super.getScaledProgress();
	}

	/** 重写createNewCachedRecipe — 委托 {@link MekCentrifugeUpgradeOps#configureCachedRecipe} 配置能量与并行 */
	@NotNull
	@Override
	public CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(
			@NotNull ItemStackToItemStackRecipe recipe, int cacheIndex) {
		return MekCentrifugeUpgradeOps.configureCachedRecipe(this, super.createNewCachedRecipe(recipe, cacheIndex));
	}

	@Override
	public int getTicksRequired() {
		return MekUpgradeSupport.hasCreativeUpgrade(this) ? 0 : super.getTicksRequired();
	}

	@Override
	public int getOperationsPerTick() { return operationsPerTick(); }

	@Override
	public void recalculateUpgrades(Upgrade upgrade) {
		super.recalculateUpgrades(upgrade);
		MekCentrifugeUpgradeOps.handleCreativeEnergy(this, upgrade);
		MekCentrifugeEnergyScaling.ensureCapacity(this);
	}

	@NotNull
	@Override
	public List<Component> getInfo(@NotNull Upgrade upgrade) {
		return MekCentrifugeUpgradeOps.getUpgradeInfo(this, upgrade);
	}

	/** 服务端tick — 全权委托 {@link MekCentrifugeTickHandler#onUpdateServer}（含 AE2 连接/PB升级输入/AE2输出推送） */
	@Override
	protected boolean onUpdateServer() {
		return tickHandler.onUpdateServer();
	}

	// ===== IMekCentrifugeTile 接口实现（委托 slotManager；Ejector Mixin 读取） =====

	@Override
	public boolean productivebeesgenesis$hasOutputItems() { return slotManager.hasOutputItems(); }
	@Override
	public long productivebeesgenesis$outputContentsVersion() { return slotManager.outputContentsVersion(); }
	@Override
	public long productivebeesgenesis$outputItemCount() { return slotManager.outputItemCount(); }
	@Override
	public boolean productivebeesgenesis$outputSlotsFull() { return slotManager.outputSlotsFull(); }
	@Override
	public int productivebeesgenesis$getInputSlotCount() { return 1; }
	@Override
	public IInventorySlot productivebeesgenesis$getInputSlot(int index) {
		return index == 0 ? accessor().productivebeesgenesis$getInputSlot() : null;
	}
	@Override
	public boolean productivebeesgenesis$isValidInput(ItemStack stack) { return containsRecipe(stack); }

	// ===== PbRecipeContext 接口实现（setPbActiveState/onProcessActivated/Deactivated 为 no-op） =====

	@Override
	public Level level() { return level; }
	@Override
	public MachineEnergyContainer<?> energyContainer() { return accessor().productivebeesgenesis$getEnergyContainer(); }
	@Override
	public boolean hasCreativeUpgrade() { return MekCentrifugeUpgradeOps.hasCreativeUpgrade(this); }
	@Override
	public IInventorySlot inputSlot(int process) { return accessor().productivebeesgenesis$getInputSlot(); }
	@Override
	public IInventorySlot primaryOutputSlot(int process) { return accessor().productivebeesgenesis$getOutputSlot(); }
	@Override
	public IInventorySlot secondaryOutputSlot(int process) { return slotManager.getSecondaryOutputSlot(); }
	@Override
	public IInventorySlot tertiaryOutputSlot(int process) { return slotManager.getTertiaryOutputSlot(); }
	@Override
	public IExtendedFluidTank fluidOutputTank() { return slotManager.getFluidOutputTank(); }
	@Override
	public int processes() { return 1; }
	@Override
	public int baseTicksRequired() { return BASE_TICKS_REQUIRED; }
	@Override
	public boolean canFunction() { return super.canFunction(); }
	/** 设置 PB 进程激活状态 — 基础机器为 no-op（active 由 onUpdateServer 管理） */
	@Override
	public void setPbActiveState(boolean active, int process) { /* no-op */ }
	@Override
	public int productivityModifier() {
		return Math.max(1, (int) Math.floor(pbUpgradeHandler.getProductivityMultiplier()));
	}
	@Override
	public int productivityParallelModifier() {
		return pbUpgradeHandler.getProductivityParallelModifier();
	}
	@Override
	public float stabilityBonus() { return pbUpgradeHandler.getStabilityBonus(); }
	@Override
	public int operationsPerTick() { return MekCentrifugeUpgradeOps.calcOperationsPerTick(this); }
	@Override
	public int getTicksForBase(int baseTime) {
		return MekCentrifugeUpgradeOps.calcTicksForBase(this, baseTime, pbUpgradeHandler.getTimeMultiplier());
	}
	@Override
	public boolean containsSmeltingInput(ItemStack input) {
		return level != null && getRecipeType().getInputCache().containsInput(level, input);
	}
	@Override
	public void productivebeesgenesis$updateOutputSlotFlags() { slotManager.updateOutputSlotFlags(); }
	@Override
	public boolean productivebeesgenesis$outputSlotsFull(int process) { return slotManager.outputSlotsFull(); }
	@Override
	public void productivebeesgenesis$beginOutputBatch() { slotManager.beginOutputBatch(); }
	@Override
	public void productivebeesgenesis$endOutputBatch(int process) { slotManager.endOutputBatch(); }
	/** 基础机器不用计数器，hasActiveProcess 直接读 pbProcessor 状态 */
	@Override
	public boolean productivebeesgenesis$hasActiveProcess() { return pbProcessor.isPbProcessing(0); }
	/** 基础机器不用计数器，激活/失活为 no-op */
	@Override
	public void productivebeesgenesis$onProcessActivated(int process) { /* no-op */ }
	@Override
	public void productivebeesgenesis$onProcessDeactivated(int process) { /* no-op */ }

	// ===== IPbUpgradeProvider 实现 + PB升级槽位访问（委托 pbUpgradeHandler） =====

	@Override
	public int getPbUpgradeInstalledCount(PbUpgradeType type) { return pbUpgradeHandler.getInstalledCount(type); }
	@Override
	public int getPbUpgradeLimit(PbUpgradeType type) { return pbUpgradeHandler.getLimit(type); }
	@Override
	public float getClientInstallingProgress() { return pbUpgradeHandler.getClientInstallingProgress(); }
	@Override
	public float getClientUninstallingProgress() { return pbUpgradeHandler.getClientUninstallingProgress(); }
	@Override
	public boolean isPbUpgradeSupported(PbUpgradeType type) { return pbUpgradeHandler.isSupported(type); }
	/** 获取PB升级输入槽 — 供 Container 创建虚拟槽位 */
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeInputSlot() { return pbUpgradeHandler.getInputSlot(); }
	/** 获取PB升级输出槽 — 供 Container 创建虚拟槽位 */
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeOutputSlot() { return pbUpgradeHandler.getOutputSlot(); }
	/** 卸载指定类型的PB升级到输出槽 — 供网络包调用 */
	public boolean extractPbUpgradeByType(PbUpgradeType type) { return pbUpgradeHandler.extractPbUpgradeByType(type); }

	/**
	 * 批量安装 PB 升级 — 由 Mixin 拦截 PB 原版 useOn 后调用
	 *
	 * @param type         升级类型
	 * @param maxAvailable 手上持有的最大数量（{@code stack.getCount()}）
	 * @return 实际安装数量（0 表示未安装）
	 */
	public int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		return pbUpgradeHandler.installPbUpgradeBulk(type, maxAvailable);
	}

	/**
	 * IUpgradeableBlockEntity — 返回PB原版安装桥接器
	 * <br/>
	 * PB原版 AbstractUpgradeItem.useOn 要求返回 UpgradeHandler 实例并调用 insertItem 安装。
	 * 桥接器拦截 insertItem 委托给 pbUpgradeHandler.installPbUpgrade，使升级物品由自定义 EnumMap 管理数量。
	 * 离心机仅接受产量（PRODUCTIVITY）和时间（TIME）系列升级。
	 */
	@NotNull
	@Override
	public IItemHandlerModifiable getUpgradeHandler() { return pbUpgradeInstallHandler; }

	// ===== 客户端同步和持久化（委托 saveHandler） =====

	/** 同步PB进度、PB升级数量/安装进度和 AE2 开关 — 委托 {@link MekCentrifugeSaveHandler#addContainerTrackers} */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		super.addContainerTrackers(container);
		saveHandler.addContainerTrackers(container);
	}
	/** 持久化完整状态（PB进度/PB升级/AE2节点/per-tile开关） — 委托 {@link MekCentrifugeSaveHandler#saveAdditional} */
	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		saveHandler.saveAdditional(nbt, provider);
	}
	/** 保存自定义数据为 NBT — 供扳手拆卸持久化（AE2 节点不保存，per-tile 开关保存） */
	@Override
	@NotNull
	public CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		return saveHandler.saveCustomDataForItem(provider);
	}
	/** 加载完整状态 — 委托 {@link MekCentrifugeSaveHandler#loadAdditional} */
	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		saveHandler.loadAdditional(nbt, provider);
	}

	/** 保存全部数据后清空所有槽位 — 委托 {@link MekCentrifugeSaveHandler#saveAllItemsForDrop} */
	public void saveAllItemsForDrop() {
		saveHandler.saveAllItemsForDrop();
	}
	/** 写入配置卡数据 — 添加PB升级数量和AE2 per-tile状态 */
	@Override
	public void writeSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.writeSustainedData(provider, data);
		saveHandler.writeSustainedData(data);
	}
	/** 从配置卡数据读取 — 恢复AE2 per-tile状态（PB升级粘贴在 setConfigurationData 中处理） */
	@Override
	public void readSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.readSustainedData(provider, data);
		saveHandler.readSustainedData(data);
	}
	/** 设置配置卡数据 — 处理PB升级粘贴（含生存模式物品消耗） */
	@Override
	public void setConfigurationData(@NotNull HolderLookup.Provider provider,
			@Nullable Player player, @NotNull CompoundTag data) {
		super.setConfigurationData(provider, player, data);
		saveHandler.setConfigurationData(data, player);
	}

	// ===== 工厂安装器升级数据持久化（委托 saveHandler） =====

	/**
	 * 构建升级数据 — 委托 {@link MekCentrifugeSaveHandler#buildUpgradeData}
	 * <br/>
	 * 返回 {@link CentrifugeUpgradeData} 保存完整状态供工厂安装器升级时流转。
	 * <p>
	 * 模块 3 Bug 2：buildUpgradeData 内部已通过 ItemStack.copy() 深拷贝槽位内容到
	 * {@link CentrifugeUpgradeData} 的 outputItems/inputItems/energyItem 字段，
	 * 此处调用 {@link #saveAllItemsForDrop()} 清空旧方块所有槽位，
	 * 防止 setRemoved 触发 Ejector 重复 popResource 导致物品复制。
	 *
	 * @param provider 注册表访问器
	 * @return 包含完整离心机状态的升级数据
	 */
	@NotNull
	@Override
	public CentrifugeUpgradeData getUpgradeData(HolderLookup.Provider provider) {
		CentrifugeUpgradeData data = saveHandler.buildUpgradeData(provider, redstone, getControlType(),
				getOperatingTicks(), getComponents());
		// 模块 3 Bug 2：深拷贝已保存到升级数据，清空旧方块槽位防 Ejector 重复 popResource
		saveAllItemsForDrop();
		return data;
	}

	/**
	 * 应用升级数据 — 委托 {@link MekCentrifugeSaveHandler#applyUpgradeData} 恢复 PB 升级和 AE2 per-tile 设置
	 * <br/>
	 * 父类恢复标准字段（能量/进度/槽位/组件），saveHandler 对非 CentrifugeUpgradeData 类型为 no-op。
	 *
	 * @param provider    注册表访问器
	 * @param upgradeData 升级数据
	 */
	@Override
	public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		super.parseUpgradeData(provider, upgradeData);
		saveHandler.applyUpgradeData(provider, upgradeData);
	}

	// ===== AE2 网格节点生命周期与 IAe2OutputHostBase/IAe2InputHost 实现（委托 ae2Handler） =====

	/** 方块实体加载完成时委托 AE2 处理器准备节点 */
	@Override
	public void clearRemoved() {
		super.clearRemoved();
		ae2Handler.handleLoad();
	}
	/** 方块被移除时委托 AE2 处理器销毁节点 */
	@Override
	public void setRemoved() {
		super.setRemoved();
		ae2Handler.handleRemove();
	}
	/** 区块卸载时委托 AE2 处理器销毁节点（幂等，与 setRemoved 重复调用安全） */
	@Override
	public void onChunkUnloaded() {
		super.onChunkUnloaded();
		ae2Handler.handleChunkUnload();
	}
	/** 获取 AE2 生命周期处理器 — 供 IAe2OutputHostBase default 方法委托使用 */
	@Override
	public MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler() {
		return ae2Handler.getLifecycleHandler();
	}
	@Override
	public MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource() { return energyContainer(); }
	@Override
	public Level productivebeesgenesis$getAe2Level() { return level; }
	@Override
	public BlockPos productivebeesgenesis$getAe2BlockPos() { return getBlockPos(); }
	/** 切换 per-tile AE2 物品输出开关（供网络包 handler 调用） */
	@Override
	public void toggleAeItemOutput() { ae2Handler.toggleAeItemOutput(); }
	/** 切换 per-tile AE2 流体输出开关（供网络包 handler 调用） */
	@Override
	public void toggleAeFluidOutput() { ae2Handler.toggleAeFluidOutput(); }
	/** 获取用于拉取的输入槽列表 — 委托 {@link MekCentrifugeAe2Handler#getInputSlotsForPull}（基础机单元素列表） */
	@Override
	public List<IInventorySlot> productivebeesgenesis$getInputSlotsForPull() { return ae2Handler.getInputSlotsForPull(); }
	/** 切换 per-tile AE2 输入拉取开关（供网络包 handler 调用） */
	@Override
	public void productivebeesgenesis$toggleAeItemInput() { ae2Handler.toggleAeItemInput(); }
	/** 切换 per-tile AE2 输入 NBT 忽略开关（供网络包 handler 调用） */
	@Override
	public void productivebeesgenesis$toggleAeInputNbtIgnore() { ae2Handler.toggleAeInputNbtIgnore(); }
}
