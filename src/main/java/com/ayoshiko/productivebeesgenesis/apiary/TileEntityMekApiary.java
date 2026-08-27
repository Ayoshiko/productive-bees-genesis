package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.IHasEjectorCooldown;
import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.MekCreativeEnergyHelper;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityElectricMachineAccessor;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import java.util.function.BooleanSupplier;

/**
	 * MEK通用机械蜂箱方块实体 — 继承 TileEntityElectricMachine，复用能量/侧面/升级/GUI 体系。
	 * 生产周期 1200 ticks。组件架构（SRP）：ApiarySlotManager、FeederSlotManager、ApiaryTickHandler、
	 * BeeProduceProcessor、ApiaryUpgradeHandler、ApiaryAe2HostAdapter、ApiaryPbUpgradeHandler、ApiaryNbtSerializer。
	 */
public class TileEntityMekApiary extends TileEntityElectricMachine implements IAe2OutputHostBase,
		IUpgradeableBlockEntity, IMekApiaryTile, IHasEjectorCooldown, IPbUpgradeProvider,
		com.ayoshiko.productivebeesgenesis.ICustomDataPersistable {

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

	/** PB升级处理器 — 安装/卸载/NBT迁移（Bug 6 核心数据结构持有者） */
	final ApiaryPbUpgradeHandler pbUpgradeHandler;
	/** PB原版安装桥接器 — 使PB原版潜行右键安装委托给自定义升级系统 */
	private final PbUpgradeInstallHandler pbUpgradeInstallHandler;
	private final ApiaryNbtSerializer nbtSerializer;
	/** F4: 产物溢出缓冲区 — 缓存输出槽满载时的剩余产物，下 tick 重试注入 */
	private final ApiaryOutputBuffer outputBuffer = new ApiaryOutputBuffer(this);
	/** Honey that was accepted by neither AE2 nor the local tank yet. Kept outside FluidStack because its amount is long. */
	private long pendingHoneyFluidAmount;
	/** Type/components for {@link #pendingHoneyFluidAmount}; current bee recipes produce honey only, but preserve the template. */
	@Nullable
	private FluidStack pendingHoneyFluidTemplate;
	/** Bug 9：选中的蜜蜂槽位索引（-1=未选择），跨线程访问需 volatile 保证可见性 */
	private volatile int selectedBeeSlot = -1;
	/** 客户端同步用：选中蜜蜂槽位（仅服务端同步回调写入，GUI 通过 getter 读取），跨线程访问需 volatile */
	private volatile int clientSelectedBeeSlot = -1;
	/** 是否启用蜂箱到相邻离心机的特殊直连通道；默认开启以兼容旧存档。 */
	private boolean directEjectEnabled = true;
	private boolean directAeOutputEnabled = false;
	private boolean centrifugePriorityEnabled = true;
	/**
	 * 喂食槽转化开关（默认关闭）
	 * <br/>
	 * 控制蜜蜂是否对喂食槽内物品执行 PB 的 item_conversion / block_conversion 转化
	 * （如末影龙蜜蜂把黑曜石转为哭泣的黑曜石）。默认关闭以免玩家放在喂食槽里
	 * 单纯当花朵用的物品被意外消耗，需要转化时由玩家在喂食槽 GUI 主动开启。
	 */
	private boolean feederConversionEnabled = false;

	public TileEntityMekApiary(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state, APIARY_TICKS_REQUIRED);
		// 协作组件初始化（super() 期间已通过 getInitialInventory() 懒初始化 slotManager）
		pbUpgradeHandler = new ApiaryPbUpgradeHandler(this);
		// 桥接PB原版安装入口到自定义升级系统（this::installPbUpgrade 委托给 pbUpgradeHandler）
		pbUpgradeInstallHandler = new PbUpgradeInstallHandler(this, this::installPbUpgrade);
		nbtSerializer = new ApiaryNbtSerializer(this);
		feederSlotManager = createFeederSlotManager();
		feederSlotManager.buildFeederSlots(this::setChanged);
		// 注入 per-tile 转化开关查询：关闭时「转化原料算有效花朵」通路失效，
		// 避免只放转化原料（如末影龙蜜蜂的黑曜石）也被判定为有花朵而照常采蜜
		feederSlotManager.setConversionEnabledSupplier(this::isFeederConversionEnabled);
		upgradeHandler = new ApiaryUpgradeHandler(this);
		produceProcessor = new BeeProduceProcessor(upgradeHandler, this);
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
	int pushGeneratedItemToAe(ItemStack stack) { return ae2HostAdapter.pushGeneratedItem(stack); }
	long pushGeneratedFluidToAe(FluidStack stack, long amount) {
		return ae2HostAdapter.pushGeneratedFluid(stack, amount);
	}

	long getPendingHoneyFluidAmount() {
		return pendingHoneyFluidAmount;
	}

	@Nullable
	FluidStack getPendingHoneyFluidTemplate() {
		return pendingHoneyFluidTemplate == null ? null : pendingHoneyFluidTemplate.copyWithAmount(1);
	}

	void addPendingHoneyFluid(FluidStack template, long amount) {
		if (template == null || template.isEmpty() || amount <= 0) return;
		FluidStack normalized = template.copyWithAmount(1);
		if (pendingHoneyFluidTemplate == null || pendingHoneyFluidTemplate.isEmpty()) {
			pendingHoneyFluidTemplate = normalized;
		} else if (!FluidStack.isSameFluidSameComponents(pendingHoneyFluidTemplate, normalized)) {
			// BeeFluidOutputResolver currently emits one type (honey). Do not overwrite a
			// different pending type if a future recipe is added accidentally.
			ProductiveBeesGenesis.LOGGER.error("Apiary pending fluid type conflict at {}", getBlockPos());
			return;
		}
		long previous = pendingHoneyFluidAmount;
		pendingHoneyFluidAmount = SaturatingMath.saturatingAdd(previous, amount);
		if (pendingHoneyFluidAmount != previous) setChanged();
	}

	void setPendingHoneyFluid(long amount, @Nullable FluidStack template) {
		long previousAmount = pendingHoneyFluidAmount;
		FluidStack previousTemplate = pendingHoneyFluidTemplate;
		if (amount <= 0 || template == null || template.isEmpty()) {
			pendingHoneyFluidAmount = 0L;
			pendingHoneyFluidTemplate = null;
		} else {
			pendingHoneyFluidAmount = amount;
			pendingHoneyFluidTemplate = template.copyWithAmount(1);
		}
		boolean templateChanged = previousTemplate == null ? pendingHoneyFluidTemplate != null
				: pendingHoneyFluidTemplate == null
						|| !FluidStack.isSameFluidSameComponents(previousTemplate, pendingHoneyFluidTemplate);
		if (previousAmount != pendingHoneyFluidAmount || templateChanged) setChanged();
	}

	void clearPendingHoneyFluid() {
		if (pendingHoneyFluidAmount != 0L || pendingHoneyFluidTemplate != null) {
			pendingHoneyFluidAmount = 0L;
			pendingHoneyFluidTemplate = null;
			setChanged();
		}
	}

	/** 失效所有蜂箱槽位上限缓存 — 委托 ApiarySlotManager.invalidateCache()，配置 reload 时调用 */
	public static void invalidateSlotManagerCache() {
		ApiarySlotManager.invalidateCache();
	}

	/**
	 * 重写recalculateUpgrades — 支持MEKExtras CREATIVE升级的无限电容量
	 * <br/>
	 * 复用离心机的 {@link MekCreativeEnergyHelper}（1:1复刻MEKExtras
	 * MixinMachineEnergyContainer.mekanism_Extras$extraRecalculateUpgrades）：
	 * <ul>
	 *   <li>CREATIVE 安装：setMaxEnergy(Long.MAX_VALUE) 并回满能量</li>
	 *   <li>CREATIVE 移除：恢复正常电容量（MekanismUtils.getMaxEnergy 公式）</li>
	 *   <li>CREATIVE 已装时 SPEED/ENERGY 变动：恢复被 super 覆盖的无限容量</li>
	 * </ul>
	 * 能耗归零无需手动处理 — MEKExtras Mixin 在 MachineEnergyContainer.getEnergyPerTick
	 * 层面自动返回 0（蜂箱能耗直接读该方法，见 BeeSlotTickProcessor）。
	 * 同时失效升级缓存，使 hasCreativeUpgrade 等查询立即反映变更（不等待100-tick自动刷新）。
	 */
	@Override
	public void recalculateUpgrades(Upgrade upgrade) {
		super.recalculateUpgrades(upgrade);
		// 不走缓存的直查 — 缓存尚未失效时读到的是安装前的旧值
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MekCreativeEnergyHelper.recalculateCreativeEnergy(
					accessor().productivebeesgenesis$getEnergyContainer(), upgrade,
					MekUpgradeSupport.hasCreativeUpgrade(this));
		}
		upgradeHandler.invalidateUpgradeCache();
		MekCentrifugeEnergyScaling.normalizeCapacity(this);
	}

	/**
	 * 重写getInfo — GUI升级窗口显示CREATIVE的自定义信息
	 * <br/>
	 * MEK原版GuiUpgradeWindow渲染升级tooltip时调用tile.getInfo(upgrade)。
	 * 委托 {@link MekUpgradeSupport#getUpgradeInfo}：CREATIVE 显示"效率: ∞ / 能耗: 0"，
	 * 其他升级保持 MEK 原版效率显示（与离心机实现一致）。
	 */
	@NotNull
	@Override
	public List<Component> getInfo(@NotNull Upgrade upgrade) {
		return MekUpgradeSupport.getUpgradeInfo(this, upgrade);
	}

	TileEntityElectricMachineAccessor accessor() { return (TileEntityElectricMachineAccessor) this; }

	boolean callSuperOnUpdateServer() { return super.onUpdateServer(); }
	void callSetActive(boolean active) { setActive(active); }
	boolean callSuperCanFunction() { return super.canFunction(); }
	/** 包私有 — 供 NbtSerializer 设置父类 protected redstone 字段（boolean 类型） */
	void setRedstoneControl(boolean value) { redstone = value; }
	/** 包私有 — 供持久化桥接读取父类 protected redstone 字段 */
	boolean getRedstoneControl() { return redstone; }
	/** 包私有 — 供 NbtSerializer 调用父类 protected setOperatingTicks */
	void callSetOperatingTicks(int value) { setOperatingTicks(value); }

	/** 包私有 — 供 ApiaryTilePersistence 等同包组件访问 NBT 序列化器 */
	ApiaryNbtSerializer nbtSerializer() { return nbtSerializer; }

	/** 包私有 — 供 ApiaryTilePersistence 等同包组件访问 AE2 宿主适配器 */
	ApiaryAe2HostAdapter ae2HostAdapter() { return ae2HostAdapter; }

	/** 包私有 — 供 ApiaryTilePersistence 等同包组件访问 PB 升级处理器 */
	ApiaryPbUpgradeHandler pbUpgradeHandler() { return pbUpgradeHandler; }

	/** 包私有 — 供容器 tracker 写入客户端选中槽位镜像 */
	void setClientSelectedBeeSlot(int index) { clientSelectedBeeSlot = index; }

	/** 设置蜂箱侧面配置和弹出器 — 委托 {@link ApiarySideConfigSupport#setupSideConfig} */
	private void setupSideConfig() {
		ApiarySideConfigSupport.setupSideConfig(this);
	}

	/** 获取配方类型 — 占位返回SMELTING，蜂箱产出由 BeeProduceProcessor 处理 */
	@NotNull @Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekanismRecipeType.SMELTING;
	}

	/** 重写警告检查 — 委托 {@link ApiarySideConfigSupport#getWarningCheck}，未映射项回退父类 */
	@Override
	public BooleanSupplier getWarningCheck(RecipeError error) {
		BooleanSupplier mapped = ApiarySideConfigSupport.getWarningCheck(this, error);
		return mapped != null ? mapped : super.getWarningCheck(error);
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
		// Bug 1 修复：先产出→直连弹出(优先于 AE2)→AE2 推送；原顺序直连弹出在生产前只能处理上一 tick 残留
		boolean result = tickHandler.onUpdateServer();
		// Legacy accelerators may invoke this wrapper repeatedly without advancing game time.
		// The production handler already merges those calls; keep the outer routing/AE2 stage
		// on the same real-tick gate so it does not repeatedly scan adjacent centrifuges.
		if (tickHandler.handledLastInvocation()) {
			productivebeesgenesis$runPostProductionAe2();
		}
		return result;
	}

	/** Prepares AE2 only after this invocation wins the real-tick gate. */
	void prepareAe2ForTick() {
		try { ae2HostAdapter.tryConnectNode(); } catch (Exception | LinkageError e) { logAe2(e, "tryConnectNode"); }
		try { ae2HostAdapter.refreshAe2ConfigCache(); } catch (Exception | LinkageError e) { logAe2(e, "refreshAe2ConfigCache"); }
	}

	/** Runs apiary post-production routing once for the selected real game tick. */
	private void productivebeesgenesis$runPostProductionAe2() {
		// LinkageError 兜底：NoClassDefFoundError 属 Error 非 Exception，原 catch 拦不住类加载失败；
		// 漏守卫路径降级为节流日志而非 tick 崩溃（Issue #8 防御深度）
		try { directEjectHandler.tryDirectEject(); } catch (Exception | LinkageError e) { logAe2(e, "tryDirectEject"); }
		try { ae2HostAdapter.pushOutputs(); } catch (Exception | LinkageError e) { logAe2(e, "pushOutputs"); }
	}
	private void logAe2(Throwable e, String n) {
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

	/**
	 * 产出直连转移 — 产出阶段蜜脾快速通道（离心机优先 + 直连开启时跳过输出槽中转）
	 * <br/>
	 * 供 {@link BeeProduceProcessor#processBatchProduce} 在分发输出槽前调用，
	 * 直接将可处理产物写入相邻离心机输入槽，返回未能转移的剩余列表。
	 */
	List<ItemStack> directTransferProducedToCentrifuges(List<ItemStack> stacks) {
		return directEjectHandler.transferProducedStacks(stacks);
	}

	/** Called when item-side routing changes, so same-tick direct-eject caches cannot stay stale. */
	void onDirectEjectRoutingChanged() {
		directEjectHandler.onRoutingConfigChanged();
	}

	/** Called by every physical output slot, including changes made by external automation. */
	void onOutputSlotContentsChanged() {
		// Inventory slots are constructed from the superclass constructor before our fields initialize.
		if (outputBuffer != null) outputBuffer.onOutputSlotContentsChanged();
		if (directEjectHandler != null) directEjectHandler.markEjectDirty();
		if (ae2HostAdapter != null) ae2HostAdapter.onOutputSlotContentsChanged();
	}

	/** New overflow content must get an immediate AE attempt even after an older batch was rejected. */
	void onOutputBufferContentsChanged() {
		if (ae2HostAdapter != null) ae2HostAdapter.onOutputBufferContentsChanged();
	}

	public boolean isDirectEjectEnabled() {
		return directEjectEnabled;
	}

	public void setDirectEjectEnabled(boolean enabled) {
		if (directEjectEnabled == enabled) return;
		directEjectEnabled = enabled;
		directEjectHandler.onRoutingConfigChanged();
		setChanged();
	}

	public void toggleDirectEject() {
		setDirectEjectEnabled(!directEjectEnabled);
	}

	public boolean isDirectAeOutputEnabled() { return directAeOutputEnabled; }
	public void setDirectAeOutputEnabled(boolean enabled) {
		if (directAeOutputEnabled == enabled) return;
		directAeOutputEnabled = enabled;
		setChanged();
	}
	public void toggleDirectAeOutput() { setDirectAeOutputEnabled(!directAeOutputEnabled); }

	public boolean isCentrifugePriorityEnabled() { return centrifugePriorityEnabled; }
	public void setCentrifugePriorityEnabled(boolean enabled) {
		if (centrifugePriorityEnabled == enabled) return;
		centrifugePriorityEnabled = enabled;
		// 开关切换影响 hold 判定与 AE2 推送过滤，立即失效拓扑掩码缓存并解除直连退避
		directEjectHandler.onRoutingConfigChanged();
		setChanged();
	}
	public void toggleCentrifugePriority() {
		setCentrifugePriorityEnabled(!centrifugePriorityEnabled);
	}

	/** 喂食槽转化功能是否开启（默认关闭）— 由 {@link ApiaryConversionProcessor} 在转化入口读取 */
	public boolean isFeederConversionEnabled() { return feederConversionEnabled; }

	public void setFeederConversionEnabled(boolean enabled) {
		if (feederConversionEnabled == enabled) return;
		feederConversionEnabled = enabled;
		setChanged();
	}

	public void toggleFeederConversion() { setFeederConversionEnabled(!feederConversionEnabled); }

	/**
	 * 离心机优先判定：该产物是否应保留给相邻离心机处理（不推 AE2）。
	 * <br/>
	 * 与直连开关解耦 — 关闭直连时蜜脾仍不回 AE，等待 Ejector/管道/玩家收取；
	 * 判定结果由 {@link ApiaryDirectEjectHandler} 的跨 tick 掩码缓存加速
	 * （拓扑/配方变化时失效）。
	 */
	boolean shouldHoldForCentrifuge(ItemStack stack) {
		if (!centrifugePriorityEnabled) return false;
		// When direct routing is active, hold only while a compatible lane has space. A full
		// centrifuge must release honeycomb to the normal AE2 path instead of hiding an
		// unbounded backlog in the apiary buffer. With direct routing disabled, preserve the
		// legacy Ejector/pipe hand-off semantics and hold by recipe compatibility alone.
		return directEjectEnabled
				? directEjectHandler.canAnyTargetAccept(stack)
				: directEjectHandler.canAnyTargetProcess(stack);
	}

	/** AE2 推送路径的蜜脾保持判定 — 供 {@code Ae2OutputPusher} 过滤输出槽蜜脾（OCP 接口扩展） */
	@Override
	public boolean productivebeesgenesis$shouldHoldForCentrifuge(ItemStack stack) {
		return shouldHoldForCentrifuge(stack);
	}

	/**
	 * 产物剩余入缓冲区（离心机优先协调入口）
	 * <br/>
	 * hold 物品（蜜脾）缓冲区满时不淘汰 — 超出输出上限的溢出部分推送 AE2；
	 * AE 也拒收时回退普通 offer（FIFO 淘汰兜底，物品守恒不丢失）。
	 * <br/>
	 * 溢出保底受 AE 输出总开关（isAeItemOutputEnabled，经 prepareDirectItemPush 检查）控制：
	 * 即使"直输 AE"子开关关闭，极端溢出场景下蜜脾仍会经本保底进入 AE（优于 FIFO 淘汰丢弃）。
	 *
	 * @param leftovers distributeToOutput 未成功插入输出槽的剩余产物
	 */
	void offerLeftoversWithCentrifugeHold(List<ItemStack> leftovers) {
		if (leftovers == null || leftovers.isEmpty()) return;
		List<ItemStack> overflow = outputBuffer.offerHolding(leftovers, this::shouldHoldForCentrifuge);
		if (overflow == null || overflow.isEmpty()) return;
		for (int i = 0; i < overflow.size(); i++) {
			ItemStack stack = overflow.get(i);
			int accepted = pushGeneratedItemToAe(stack);
			if (accepted < stack.getCount()) {
				ItemStack remaining = stack.copy();
				remaining.shrink(Math.max(0, accepted));
				outputBuffer.offer(remaining);
			}
		}
	}

	/** 容器数据同步 — 蜜蜂状态 + PB升级数量 + 安装计数器 + 选中槽位 */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		super.addContainerTrackers(container);
		slotManager.addContainerTrackers(container);
		ApiaryContainerTrackers.addTrackers(this, container);
	}

	// ===== NBT 持久化 — 委托给 nbtSerializer + pbUpgradeHandler =====

	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		ApiaryTilePersistence.saveAdditional(this, nbt, provider);
	}

	@NotNull @Override
	public CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		return ApiaryTilePersistence.saveCustomDataForItem(this, provider);
	}

	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		ApiaryTilePersistence.loadAdditional(this, nbt, provider);
	}

	/** 保存PB升级数量映射 — protected 供工厂版子类调用 */
	protected void savePbUpgradeCounts(@NotNull CompoundTag nbt) { pbUpgradeHandler.savePbUpgradeCounts(nbt); }
	/** 加载PB升级数量 — protected 供工厂版子类调用，兼容历史格式 */
	protected void loadPbUpgradeCounts(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		pbUpgradeHandler.loadPbUpgradeCounts(nbt, provider);
	}

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
		return ApiaryTilePersistence.getUpgradeData(this, provider);
	}

	@Override
	public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		if (!ApiaryTilePersistence.applyUpgradeData(this, provider, upgradeData)) {
			super.parseUpgradeData(provider, upgradeData);
		}
	}

	/** 保存全部数据后清空所有槽位 — 委托 {@link ApiaryTilePersistence#saveAllItemsForDrop} */
	public void saveAllItemsForDrop() {
		ApiaryTilePersistence.saveAllItemsForDrop(this);
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
	public int getOutputSlotsPerPage() { return slotManager.getOutputSlotsPerPage(); }
	public int getOutputPageCount() { return slotManager.getOutputPageCount(); }
	@NotNull public FeederSlotManager getFeederSlotManager() { return feederSlotManager; }
	@NotNull public List<IInventorySlot> getFeederSlots() { return feederSlotManager.getFeederSlots(); }
	@NotNull List<FeederInventorySlot> getFeederInventorySlots() { return feederSlotManager.getFeederInventorySlots(); }

	/** IUpgradeableBlockEntity — 返回PB原版安装桥接器，拦截 insertItem 委托 installPbUpgrade，由 EnumMap 管理数量 */
	@NotNull @Override
	public IItemHandlerModifiable getUpgradeHandler() { return pbUpgradeInstallHandler; }

	@NotNull public ApiaryUpgradeHandler getApiaryUpgradeHandler() { return upgradeHandler; }
	/** 已废弃 — 返回 null 保持兼容性 */
	@Nullable
	public cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.UpgradeHandler getPbUpgradeHandler() {
		return null;
	}
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
	public int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		return pbUpgradeHandler.installPbUpgradeBulk(type, maxAvailable);
	}
	public List<ItemStack> removePbUpgrade(
		PbUpgradeType type,
		boolean removeAll
	) {
		return pbUpgradeHandler.removePbUpgrade(type, removeAll);
	}
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
	public ItemStack cageBeeAtSlot(int slotIndex, ItemStack cursorCage) {
		return slotManager.tryCageBeeAtSlot(slotIndex, cursorCage);
	}
	public void confirmCageExtraction(int slotIndex) { slotManager.confirmCageExtraction(slotIndex); }
	public boolean releaseBeeAtSlot(int slotIndex, ItemStack cursorCage) {
		return slotManager.tryReleaseBeeAtSlot(slotIndex, cursorCage);
	}
	@NotNull public BeeProduceProcessor getProduceProcessor() { return produceProcessor; }

	/**
	 * 配置卡兼容性 — 允许所有等级蜂箱间互相粘贴配置（通过 instanceof MekApiaryBlock 判断）
	 */
	@Override
	public boolean isConfigurationDataCompatible(@NotNull Block blockType) {
		return super.isConfigurationDataCompatible(blockType) || blockType instanceof MekApiaryBlock;
	}

	/** 写入配置卡数据 — 追加PB升级、AE2状态和产物路由开关到 MEK 配置卡 */
	@Override
	public void writeSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.writeSustainedData(provider, data);
		ApiaryTilePersistence.writeSustainedData(this, provider, data);
	}

	/** 从配置卡读取 — 恢复AE2状态和产物路由开关（PB升级粘贴在 setConfigurationData 中处理） */
	@Override
	public void readSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data) {
		super.readSustainedData(provider, data);
		ApiaryTilePersistence.readSustainedData(this, provider, data);
	}

	/** 设置配置卡数据 — 重写获取 Player 参数，处理PB升级粘贴（生存模式消耗物品，创造模式直接安装） */
	@Override
	public void setConfigurationData(@NotNull HolderLookup.Provider provider,
			@Nullable net.minecraft.world.entity.player.Player player,
			@NotNull CompoundTag data) {
		super.setConfigurationData(provider, player, data);
		ApiaryTilePersistence.setConfigurationData(this, provider, player, data);
	}

	/** 保存AE2 per-tile状态到NBT — 供 ApiaryNbtSerializer 扳手拆卸持久化调用 */
	void saveAe2PerTileState(CompoundTag nbt) {
		ApiaryTilePersistence.saveAe2PerTileState(this, nbt);
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
	@Override public MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler(
	) {
		return ae2HostAdapter.getAe2LifecycleHandler();
	}
	@Override public Ae2OutputStateHolder productivebeesgenesis$getAe2StateHolder() {
		return ae2HostAdapter.getAe2StateHolder();
	}
	@Override public MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource(
	) {
		return ae2HostAdapter.getAe2EnergySource();
	}
	@Override public Level productivebeesgenesis$getAe2Level() { return ae2HostAdapter.getAe2Level(); }
	@Override public BlockPos productivebeesgenesis$getAe2BlockPos() { return ae2HostAdapter.getAe2BlockPos(); }
	@Override public boolean productivebeesgenesis$isOutputPushEnabled() { return ae2HostAdapter.isOutputPushEnabled(); }
	@Override public boolean productivebeesgenesis$isFluidPushEnabled() { return ae2HostAdapter.isFluidPushEnabled(); }
	@Override public void productivebeesgenesis$injectAe2Energy() { ae2HostAdapter.injectAe2Energy(1); }
	@Override public void productivebeesgenesis$injectAe2Energy(int batchMultiplier) {
		ae2HostAdapter.injectAe2Energy(batchMultiplier);
	}
	@Override public boolean productivebeesgenesis$getPreferAppliedFluxOverAeEnergy(
	) {
		return ae2HostAdapter.getPreferAppliedFluxOverAeEnergy();
	}
	@Override public boolean productivebeesgenesis$isAeNativeEnergyInputEnabled() {
		return ae2HostAdapter.isAeNativeEnergyInputEnabled();
	}
	@Override public boolean productivebeesgenesis$isAeItemOutputEnabled(
	) {
		return ae2HostAdapter.isAeItemOutputEnabled();
	}
	@Override public boolean productivebeesgenesis$isAeFluidOutputEnabled(
	) {
		return ae2HostAdapter.isAeFluidOutputEnabled();
	}
	@Override public void productivebeesgenesis$setAeItemOutputEnabled(
		boolean enabled
	) {
		ae2HostAdapter.setAeItemOutputEnabled(enabled);
	}
	@Override public void productivebeesgenesis$setAeFluidOutputEnabled(
		boolean enabled
	) {
		ae2HostAdapter.setAeFluidOutputEnabled(enabled);
	}

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
	@Override public MachineEnergyContainer<?> energyContainer() {
		return ae2HostAdapter.getPbRecipeAdapter().energyContainer();
	}
	@Override public boolean hasCreativeUpgrade() { return upgradeHandler.hasCreativeUpgrade(); }

	@Override public int processes() { return ae2HostAdapter.getPbRecipeAdapter().processes(); }
	@Override public IInventorySlot inputSlot(int process) {
		return ae2HostAdapter.getPbRecipeAdapter().inputSlot(process);
	}
	@Override public IInventorySlot primaryOutputSlot(int process) {
		return ae2HostAdapter.getPbRecipeAdapter().primaryOutputSlot(process);
	}
	@Override public IInventorySlot secondaryOutputSlot(int process) {
		return ae2HostAdapter.getPbRecipeAdapter().secondaryOutputSlot(process);
	}
	@Override public IInventorySlot tertiaryOutputSlot(int process) {
		return ae2HostAdapter.getPbRecipeAdapter().tertiaryOutputSlot(process);
	}
	@Override public IExtendedFluidTank fluidOutputTank() { return ae2HostAdapter.getPbRecipeAdapter().fluidOutputTank(); }
	@Override public int baseTicksRequired() { return ae2HostAdapter.getPbRecipeAdapter().baseTicksRequired(); }
	@Override public boolean canFunction() { return ae2HostAdapter.getPbRecipeAdapter().canFunction(); }
	@Override public void setPbActiveState(boolean active, int process) {
		ae2HostAdapter.getPbRecipeAdapter().setPbActiveState(active, process);
	}
	@Override public int productivityModifier() { return ae2HostAdapter.getPbRecipeAdapter().productivityModifier(); }
	@Override public int operationsPerTick() { return ae2HostAdapter.getPbRecipeAdapter().operationsPerTick(); }
	@Override public int getTicksForBase(int baseTime) {
		return ae2HostAdapter.getPbRecipeAdapter().getTicksForBase(baseTime);
	}
	@Override public boolean containsSmeltingInput(ItemStack input) {
		return ae2HostAdapter.getPbRecipeAdapter().containsSmeltingInput(input);
	}
	@Override public boolean productivebeesgenesis$hasOutputItems() { return ae2HostAdapter.hasOutputItems(); }
	@Override public void productivebeesgenesis$updateOutputSlotFlags() { ae2HostAdapter.updateOutputSlotFlags(); }
	@Override public void productivebeesgenesis$beginOutputBatch() { ae2HostAdapter.beginOutputBatch(); }
	@Override public void productivebeesgenesis$endOutputBatch(int process) { ae2HostAdapter.endOutputBatch(process); }
	@Override public void productivebeesgenesis$onProcessActivated(
		int process
	) {
		ae2HostAdapter.onProcessActivated(process);
	}
	@Override public void productivebeesgenesis$onProcessDeactivated(
		int process
	) {
		ae2HostAdapter.onProcessDeactivated(process);
	}
	@Override public boolean productivebeesgenesis$hasActiveProcess() { return ae2HostAdapter.hasActiveProcess(); }

	// ===== IMekApiaryTile — 供 Ejector Mixin 读取蜂箱输出槽状态 =====

	@Override public long productivebeesgenesis$outputContentsVersion() { return outputBuffer.getOutputVersion(); }

	/**
	 * JDTE {@code CoalescedAcceleratedMachine} 合并接口委托（仅 JDTE 加载时经 Mixin 生效）。
	 * <br/>
	 * accumulate：仅入账虚拟 tick 银行；flush：强制执行一次完整批量（能量 + super + 蜜蜂生产）。
	 */
	public void productivebeesgenesis$accumulateAcceleratedTicks(int ticks) {
		tickHandler.accumulateAcceleratedTicks(ticks);
	}

	/** JDTE 合并接口 flush 委托（见 {@link #productivebeesgenesis$accumulateAcceleratedTicks}） */
	public void productivebeesgenesis$flushAcceleratedTicks() {
		tickHandler.flushAcceleratedTicks();
		if (tickHandler.handledLastInvocation()) {
			productivebeesgenesis$runPostProductionAe2();
		}
	}
	@Override public boolean productivebeesgenesis$outputSlotsFull(
	) {
		return slotManager != null && slotManager.isOutputFull();
	}
	@Override public long productivebeesgenesis$outputItemCount(
	) {
		return slotManager != null ? slotManager.outputItemCount() : 0L;
	}
}
