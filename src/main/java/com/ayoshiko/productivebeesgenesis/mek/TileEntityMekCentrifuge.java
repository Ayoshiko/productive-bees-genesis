package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Collections;
import java.util.List;

import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.config.MekanismConfig;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityElectricMachineAccessor;

/**
 * 基础MEK离心机方块实体
 * <br/>
 * 继承Mekanism的TileEntityElectricMachine，复用完整的能量/侧面配置/升级/GUI体系。
 * 扩展PB离心配方查找：先查PB CentrifugeRecipe，找到则用PB逻辑处理（概率多物品输出+流体），
 * 未找到则回退到Mekanism的SMELTING配方。
 * <p>
 * 额外添加2个副输出槽+FluidTank，支持PB配方的多物品和流体输出。
 * 通过Accessor Mixin访问父类的包私有字段（inputSlot/outputSlot/energySlot/energyContainer）。
 * <p>
 * Task 9/11 重构：职责拆分为多个协作组件，主类仅负责接口实现与组件编排：
 * <ul>
 *   <li>{@link MekCentrifugeSlotManager}：输出槽/流体槽初始化与状态标志位维护</li>
 *   <li>{@link MekCentrifugeSaveHandler}：NBT 持久化与客户端容器同步</li>
 *   <li>{@link MekCentrifugeTickHandler}：服务端 tick 与 PB 配方处理逻辑</li>
 *   <li>{@link PbRecipeProcessor}：PB 离心配方处理（与工厂版共用，消除约600行重复代码）</li>
 * </ul>
 * 基础机器与工厂版的差异：
 * <ul>
 *   <li>单进程（processes()=1），PbRecipeProcessor 内部用长度1的数组管理</li>
 *   <li>active 状态由 onUpdateServer 中的 pbWasProcessing 逻辑管理，setPbActiveState 为 no-op</li>
 *   <li>SMELTING 检查在 tryProcessPbRecipe 中完成（工厂版在 MekCentrifugeFactoryHelper 中完成）</li>
 * </ul>
 */
public class TileEntityMekCentrifuge extends TileEntityElectricMachine
		implements IAe2OutputHost, IMekCentrifugeTile {

	/**
	 * 输出槽/流体槽管理器
	 * <br/>
	 * 懒初始化：super() 构造期间会通过虚方法调用触发 getInitialInventory()，
	 * 此时 pbProcessor 等字段尚未初始化，slotManager 必须能独立完成槽位构建。
	 * 非声明为 final 以支持懒初始化模式。
	 */
	private MekCentrifugeSlotManager slotManager;

	/** PB配方处理器 — 封装所有PB离心配方处理逻辑（与工厂版共用） */
	private final PbRecipeProcessor pbProcessor;

	/** 持久化处理器 — 封装 PbRecipeProcessor 的 NBT/容器同步调用 */
	private final MekCentrifugeSaveHandler saveHandler;

	/** 服务端 tick 处理器 — 封装 onUpdateServer/PB配方处理逻辑 */
	private final MekCentrifugeTickHandler tickHandler;

	/**
	 * AE2 输出状态持有者 — 封装网格节点、AEItemKey 缓存和待连接标志
	 * <br/>
	 * 消除 IAe2OutputHost 实现类的 AE2 字段/方法重复，通过接口的 default 方法委托访问。
	 */
	private final Ae2OutputStateHolder productivebeesgenesis$ae2StateHolder = new Ae2OutputStateHolder();

	public TileEntityMekCentrifuge(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state, BASE_TICKS_REQUIRED);
		// super() 期间已通过 getInitialInventory() 懒初始化 slotManager，此处非空
		// 初始化 PB 处理器及其协作组件
		pbProcessor = new PbRecipeProcessor(this, "MEK离心机");
		saveHandler = new MekCentrifugeSaveHandler(pbProcessor);
		tickHandler = new MekCentrifugeTickHandler(this, pbProcessor);

		// 重写侧面配置：3个输出槽（参考PrecisionSawmill）
		// 注：secondaryOutputSlot/tertiaryOutputSlot 在 super() 期间通过 getInitialInventory()
		// （TileEntityMekanism 构造函数虚方法调用）已赋值，此处 List.of 不会 NPE。
		// 切勿将 setupItemIOConfig 移到 getInitialInventory 之前或重构 super() 调用顺序，
		// 否则 List.of 的 null 检查会立即抛出 NPE。
		configComponent.setupItemIOConfig(
				Collections.singletonList(accessor().productivebeesgenesis$getInputSlot()),
				List.of(accessor().productivebeesgenesis$getOutputSlot(),
						slotManager.getSecondaryOutputSlot(),
						slotManager.getTertiaryOutputSlot()),
				accessor().productivebeesgenesis$getEnergySlot(), false);
		configComponent.setupInputConfig(TransmissionType.ENERGY, accessor().productivebeesgenesis$getEnergyContainer());
		// 流体槽作为输出配置（右侧），参考TileEntityNutritionalLiquifier
		configComponent.setupOutputConfig(TransmissionType.FLUID, slotManager.getFluidOutputTank(), RelativeSide.RIGHT);

		// 使用自定义流体弹出速率覆盖 Mekanism 默认值，同时把物品弹出 tickDelay 设为 1 tick
		// （TileComponentEjectorMixin 会在此基础上根据输出槽状态做动态调整）
		ejectorComponent = new TileComponentEjector(this, MekanismConfig.general.chemicalAutoEjectRate,
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
		((TileEntityEjectorAccessor) ejectorComponent).productivebeesgenesis$setTickDelay(1);
		// 同时弹出物品和流体
		ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
	}

	/**
	 * 获取Accessor — 用于访问父类包私有字段
	 * <br/>
	 * 包私有可见性：供同包的 {@link MekCentrifugeSlotManager}、{@link MekCentrifugeTickHandler} 访问。
	 */
	TileEntityElectricMachineAccessor accessor() {
		return (TileEntityElectricMachineAccessor) this;
	}

	/**
	 * 懒初始化槽位管理器
	 * <br/>
	 * super() 构造期间通过 getInitialInventory() 虚方法调用进入此处，
	 * 此时构造函数体尚未执行，slotManager 为 null，需在此创建。
	 */
	private MekCentrifugeSlotManager slotManager() {
		if (slotManager == null) {
			slotManager = new MekCentrifugeSlotManager(this);
		}
		return slotManager;
	}

	/** 供 {@link MekCentrifugeTickHandler} 调用父类 onUpdateServer（protected 跨包不可直接访问） */
	boolean callSuperOnUpdateServer() {
		return super.onUpdateServer();
	}

	/** 供 {@link MekCentrifugeTickHandler} 调用 setActive（protected 跨包不可直接访问） */
	void callSetActive(boolean active) {
		setActive(active);
	}

	@NotNull
	@Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekanismRecipeType.SMELTING;
	}

	/**
	 * JEI配方查看器跳转支持
	 * <br/>
	 * 返回SMELTING类型，使JEI中点击配方时能正确跳转到熔炼配方类别。
	 * 参考Mekanism原版TileEntityEnergizedSmelter的实现。
	 */
	@NotNull
	@Override
	public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return RecipeViewerRecipeType.SMELTING;
	}

	/**
	 * 重写containsRecipe — 同时查找Mekanism SMELTING和PB CentrifugeRecipe
	 * <br/>
	 * 输入槽通过this::containsRecipe判断物品是否有效。
	 * 默认实现只查SMELTING配方缓存，PB蜜脾不在其中会被拒绝。
	 * 重写后同时检查PB配方，使PB蜜脾能放入输入槽。
	 */
	@Override
	public boolean containsRecipe(@NotNull ItemStack input) {
		if (super.containsRecipe(input)) return true;
		return pbProcessor.findPbRecipe(input) != null;
	}

	/**
	 * 重写getInitialInventory — 委托给 {@link MekCentrifugeSlotManager#buildInventory}
	 * <br/>
	 * 父类只有1个输出槽，PB离心配方最多3个物品输出，由槽位管理器添加2个副输出槽。
	 * 性能优化：3个输出槽使用组合 listener，内容变更时维护标志位，避免每次都遍历槽位。
	 */
	@NotNull
	@Override
	protected IInventorySlotHolder getInitialInventory(@NotNull IContentsListener listener,
													   @NotNull IContentsListener recipeCacheListener,
													   @NotNull IContentsListener recipeCacheUnpauseListener) {
		return slotManager().buildInventory(listener, recipeCacheListener, recipeCacheUnpauseListener);
	}

	/**
	 * 初始化FluidTank — 委托给 {@link MekCentrifugeSlotManager#buildFluidTanks}
	 * <br/>
	 * TileEntityRecipeMachine的1参数getInitialFluidTanks是final的，需要重写3参数版本。
	 * TileEntityElectricMachine默认没有FluidTank，重写此方法添加PB流体输出槽。
	 */
	@NotNull
	@Override
	protected IFluidTankHolder getInitialFluidTanks(@NotNull IContentsListener listener,
													 @NotNull IContentsListener recipeCacheListener,
													 @NotNull IContentsListener recipeCacheUnpauseListener) {
		return slotManager().buildFluidTanks(listener, recipeCacheListener, recipeCacheUnpauseListener);
	}

	/** 获取流体输出槽 — GUI显示用 */
	@NotNull
	public IExtendedFluidTank getFluidOutputTank() {
		return slotManager.getFluidOutputTank();
	}

	/** 获取副输出槽1 — GUI显示用 */
	@NotNull
	public OutputInventorySlot getSecondaryOutputSlot() {
		return slotManager.getSecondaryOutputSlot();
	}

	/** 获取副输出槽2 — GUI显示用 */
	@NotNull
	public OutputInventorySlot getTertiaryOutputSlot() {
		return slotManager.getTertiaryOutputSlot();
	}

	/**
	 * 重写getScaledProgress — PB配方处理时使用pbProcessor的进度
	 * <br/>
	 * 父类的getScaledProgress()使用operatingTicks/ticksRequired。
	 * PB配方处理时operatingTicks不被更新（PB用自己的pbOperatingTicks），
	 * 所以需要重写此方法，在PB处理时返回pbOperatingTicks/processingTime。
	 */
	@Override
	public double getScaledProgress() {
		if (pbProcessor.isPbProcessing(0)) {
			return pbProcessor.getPbScaledProgress(1, 0);
		}
		return super.getScaledProgress();
	}

	/**
	 * 服务端tick — 委托给 {@link MekCentrifugeTickHandler#onUpdateServer}
	 * <br/>
	 * 总是调用super以确保ejector被tick；PB配方在tryProcessPbRecipe中独立处理。
	 * 详细逻辑（能量追踪、性能监控、PB状态管理）见 tickHandler。
	 * <p>
	 * Task 3-5：tick 末尾尝试将输出槽物品推送到 AE2 网络（绕过 SFM 中介）。
	 * AE2 未安装或配置关闭时 pushOutputs 内部安全短路。
	 */
	@Override
	protected boolean onUpdateServer() {
		// 延迟连接 AE2 网格节点（避免在 clearRemoved 中连接导致递归栈溢出）
		if (productivebeesgenesis$ae2StateHolder.isAe2NodePending()) {
			productivebeesgenesis$ae2StateHolder.setAe2NodePending(false);
			Ae2GridNodeManager.connectNode(this);
		}
		boolean result = tickHandler.onUpdateServer();
		Ae2OutputPusher.pushOutputs(this);
		return result;
	}

	// ===== IMekCentrifugeTile 接口实现（委托给 slotManager） =====

	/**
	 * 输出槽是否有物品（供 TileComponentEjectorMixin 读取，避免每次弹出遍历所有槽位）
	 */
	@Override
	public boolean productivebeesgenesis$hasOutputItems() {
		return slotManager.hasOutputItems();
	}

	/**
	 * 输出槽内容版本号（供 TileComponentEjectorCooldownMixin 读取）
	 * <br/>
	 * 每次输出槽内容变化时递增，使 Ejector Mixin 能在内容未变化时跳过 outputItems 调用。
	 */
	@Override
	public long productivebeesgenesis$outputContentsVersion() {
		return slotManager.outputContentsVersion();
	}

	/**
	 * Step 5: 返回所有输出槽物品总数（O(1)，供 Ejector Mixin 替代 countOutputItems 遍历）
	 */
	@Override
	public long productivebeesgenesis$outputItemCount() {
		return slotManager.outputItemCount();
	}

	/**
	 * 输出槽是否已满（供 TileComponentEjectorCooldownMixin 和 PbRecipeProcessor 读取）
	 * <br/>
	 * Mixin 在输出槽满时强制重置跳过计数器，避免产物因跳过 outputItems 而积压停机。
	 */
	@Override
	public boolean productivebeesgenesis$outputSlotsFull() {
		return slotManager.outputSlotsFull();
	}

	// ===== PbRecipeContext 接口实现 =====
	// Task 9：基础机器实现 PbRecipeContext，将 PB 配方处理委托给 PbRecipeProcessor。
	// 与工厂版的差异：setPbActiveState / onProcessActivated / onProcessDeactivated 为 no-op，
	// 因为基础机器的 active 状态由 onUpdateServer 中的 pbWasProcessing 逻辑管理（不用计数器）。

	@Override
	public Level level() {
		return level;
	}

	@Override
	public MachineEnergyContainer<?> energyContainer() {
		return accessor().productivebeesgenesis$getEnergyContainer();
	}

	@Override
	public IInventorySlot inputSlot(int process) {
		return accessor().productivebeesgenesis$getInputSlot();
	}

	@Override
	public IInventorySlot primaryOutputSlot(int process) {
		return accessor().productivebeesgenesis$getOutputSlot();
	}

	@Override
	public IInventorySlot secondaryOutputSlot(int process) {
		return slotManager.getSecondaryOutputSlot();
	}

	@Override
	public IInventorySlot tertiaryOutputSlot(int process) {
		return slotManager.getTertiaryOutputSlot();
	}

	@Override
	public IExtendedFluidTank fluidOutputTank() {
		return slotManager.getFluidOutputTank();
	}

	@Override
	public int processes() {
		return 1;
	}

	@Override
	public int baseTicksRequired() {
		return BASE_TICKS_REQUIRED;
	}

	@Override
	public boolean canFunction() {
		return super.canFunction();
	}

	/**
	 * 设置 PB 进程激活状态 — 基础机器为 no-op
	 * <br/>
	 * 基础机器的 active 状态由 onUpdateServer 中的 pbWasProcessing 逻辑管理（setActive），
	 * 不通过 setPbActiveState 设置。PbRecipeProcessor 内部调用此方法时为 no-op，
	 * 避免与 SMELTING 的 setActive 冲突（SMELTING 处理时 super 已 setActive(true)）。
	 */
	@Override
	public void setPbActiveState(boolean active, int process) {
		// no-op：active 由 onUpdateServer 管理
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
		if (level == null) return false;
		return getRecipeType().getInputCache().containsInput(level, input);
	}

	@Override
	public void productivebeesgenesis$updateOutputSlotFlags() {
		slotManager.updateOutputSlotFlags();
	}

	@Override
	public boolean productivebeesgenesis$outputSlotsFull(int process) {
		return slotManager.outputSlotsFull();
	}

	@Override
	public void productivebeesgenesis$beginOutputBatch() {
		slotManager.beginOutputBatch();
	}

	@Override
	public void productivebeesgenesis$endOutputBatch(int process) {
		slotManager.endOutputBatch();
	}

	/** Task 11: 基础机器不用计数器，hasActiveProcess 直接读 pbProcessor 状态 */
	@Override
	public boolean productivebeesgenesis$hasActiveProcess() {
		return pbProcessor.isPbProcessing(0);
	}

	/** Task 11: 基础机器不用计数器，激活/失活为 no-op */
	@Override
	public void productivebeesgenesis$onProcessActivated(int process) {
		// no-op：基础机器用 pbWasProcessing 管理激活状态
	}

	@Override
	public void productivebeesgenesis$onProcessDeactivated(int process) {
		// no-op：基础机器用 pbWasProcessing 管理激活状态
	}

	// ===== 客户端同步和持久化（委托给 saveHandler） =====

	/**
	 * 同步PB进度到客户端
	 * <br/>
	 * 委托给 {@link MekCentrifugeSaveHandler#addContainerTrackers}，同步 pbOperatingTicks、
	 * pbProcessing、pbProcessingTime 数组（基础机器数组长度为1）。
	 */
	@Override
	public void addContainerTrackers(mekanism.common.inventory.container.MekanismContainer container) {
		super.addContainerTrackers(container);
		saveHandler.addContainerTrackers(container);
	}

	/**
	 * 持久化PB配方处理进度
	 * <br/>
	 * 委托给 {@link MekCentrifugeSaveHandler#save}。
	 * 注意：NBT 格式从 putInt 改为 putIntArray（数组长度1），旧存档（putInt）无法加载，
	 * 但模组暂未发布，无需兼容旧存档。
	 * <p>
	 * Task 3-5：同时持久化 AE2 网格节点状态（连接信息等）。
	 */
	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		saveHandler.save(nbt);
		Ae2GridNodeManager.saveNodeNBT(this, nbt);
	}

	/** 加载PB配方处理进度 — 委托给 saveHandler。Task 3-5：同时加载 AE2 网格节点。 */
	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		saveHandler.load(nbt);
		Ae2GridNodeManager.loadNodeNBT(this, nbt);
	}

	// ===== Task 3-5: AE2 网格节点生命周期与 IAe2OutputHost 实现 =====

	/** 方块实体加载完成时准备 AE2 网格节点（不接入网格，避免递归栈溢出） */
	@Override
	public void clearRemoved() {
		super.clearRemoved();
		Ae2GridNodeManager.prepareNode(this);
		productivebeesgenesis$ae2StateHolder.setAe2NodePending(true);
	}

	/** 方块被移除时销毁 AE2 网格节点，避免内存泄漏 */
	@Override
	public void setRemoved() {
		super.setRemoved();
		Ae2GridNodeManager.destroyNode(this);
		productivebeesgenesis$ae2StateHolder.clear();
	}

	/** 区块卸载时销毁 AE2 网格节点（destroyNode 幂等，与 setRemoved 重复调用安全） */
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
		return energyContainer();
	}

	@Override
	public Level productivebeesgenesis$getAe2Level() {
		return level;
	}

	@Override
	public BlockPos productivebeesgenesis$getAe2BlockPos() {
		return getBlockPos();
	}
}
