package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInstallHandler;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;

import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableInt;

/**
 * 工厂版离心机 PB 升级委托 — 封装三个工厂类的 PB 升级公共逻辑
 * <br/>
 * 因 Java 单继承限制，ME/EME 工厂无法继承 {@link AbstractMekCentrifugeFactory}，
 * 通过组合本类复用 PB 升级处理器的初始化、NBT 持久化、容器同步和倍率计算逻辑，
 * 消除三个工厂约 90 行重复代码。工厂类通过委托调用本类方法。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：仅管理 PB 升级状态，不涉及配方处理或槽位布局</li>
 *   <li>依赖倒置：通过 {@link IMekCentrifugePbUpgradeHost} 接口访问宿主，不依赖具体类</li>
 *   <li>开闭原则：新增工厂类型时只需创建本类实例，不修改本类</li>
 * </ul>
 * <p>
 * 线程安全：方块实体在服务端单线程执行；客户端通过 SyncableInt 同步，
 * {@link MekCentrifugePbUpgradeHandler} 内部使用 AtomicIntegerArray 保证可见性。
 */
public class FactoryPbUpgradeDelegate implements IPbUpgradeProvider, ICentrifugePbUpgradeAccess {

	/** PB 升级处理器 — 管理安装/卸载/数量/倍率 */
	private final MekCentrifugePbUpgradeHandler pbUpgradeHandler;

	/** PB 原版安装桥接器 — 使 PB 原版潜影右键安装委托给自定义升级系统 */
	private final PbUpgradeInstallHandler pbUpgradeInstallHandler;

	/** 工厂方块实体引用 — 用于 getBlockPos() 委托 */
	private final BlockEntity tile;

	/**
	 * 构造工厂版 PB 升级委托
	 *
	 * @param tile 工厂方块实体（必须同时是 BlockEntity 和 IMekCentrifugePbUpgradeHost）
	 */
	public FactoryPbUpgradeDelegate(BlockEntity tile) {
		this.tile = tile;
		this.pbUpgradeHandler = new MekCentrifugePbUpgradeHandler((IMekCentrifugePbUpgradeHost) tile);
		this.pbUpgradeInstallHandler = new PbUpgradeInstallHandler(tile, pbUpgradeHandler::installPbUpgrade);
	}

	// ===== PB 升级输入处理 =====

	/** 处理 PB 升级输入槽的自动安装 — 在工厂 onUpdateServer 中调用 */
	public void processPbUpgradeInput() {
		pbUpgradeHandler.processPbUpgradeInput();
	}

	// ===== IPbUpgradeProvider 实现 =====

	@Override
	public int getPbUpgradeInstalledCount(PbUpgradeType type) {
		return pbUpgradeHandler.getInstalledCount(type);
	}

	/**
	 * 获取PB升级数量映射的只读视图 — 供配置卡复制使用
	 */
	@Override
	public java.util.Map<PbUpgradeType, Integer> getPbUpgradeCounts() {
		return pbUpgradeHandler.getPbUpgradeCounts();
	}

	@Override
	public int getPbUpgradeLimit(PbUpgradeType type) {
		return pbUpgradeHandler.getLimit(type);
	}

	@Override
	public float getClientInstallingProgress() {
		return pbUpgradeHandler.getClientInstallingProgress();
	}

	@Override
	public float getClientUninstallingProgress() {
		return pbUpgradeHandler.getClientUninstallingProgress();
	}

	@Override
	public boolean isPbUpgradeSupported(PbUpgradeType type) {
		return pbUpgradeHandler.isSupported(type);
	}

	@Override
	public BlockPos getBlockPos() {
		return tile.getBlockPos();
	}

	// ===== 槽位访问（供 Container 创建虚拟槽） =====

	/** {@inheritDoc} — 委托给内部 handler */
	@Override
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeInputSlot() {
		return pbUpgradeHandler.getInputSlot();
	}

	/** {@inheritDoc} — 委托给内部 handler */
	@Override
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeOutputSlot() {
		return pbUpgradeHandler.getOutputSlot();
	}

	/** {@inheritDoc} — 委托给内部 handler，仅恢复升级数量（不含槽位） */
	@Override
	public void loadCounts(@NotNull CompoundTag nbt) {
		pbUpgradeHandler.loadCounts(nbt);
	}

	// ===== 卸载 API（供网络包调用） =====

	/** 卸载指定类型的 PB 升级到输出槽 */
	public boolean extractPbUpgradeByType(PbUpgradeType type) {
		return pbUpgradeHandler.extractPbUpgradeByType(type);
	}

	/**
	 * 卸载 PB 升级 — 参照蜂箱版 {@link com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler#removePbUpgrade}
	 * <br/>
	 * 直接从 pbUpgradeCounts 扣减数量并生成对应数量的物品栈，调用方负责消费返回值。
	 * 供 clearAllPbUpgrades 使用，避免依赖输出槽空间。
	 *
	 * @param type      升级类型
	 * @param removeAll true 移除全部，false 移除一个
	 * @return 移除的物品栈列表（每项 1 个），空列表表示未移除
	 */
	@NotNull
	public java.util.List<net.minecraft.world.item.ItemStack> removePbUpgrade(PbUpgradeType type, boolean removeAll) {
		return pbUpgradeHandler.removePbUpgrade(type, removeAll);
	}

	/**
	 * 安装一个 PB 升级 — 供配置卡粘贴调用
	 * @param type 升级类型
	 * @return true 安装成功
	 */
	public boolean installPbUpgrade(PbUpgradeType type) {
		return pbUpgradeHandler.installPbUpgrade(type);
	}

	/**
	 * 批量安装 PB 升级 — shift+右键时一次填满到上限
	 * <br/>
	 * 委托给 {@link MekCentrifugePbUpgradeHandler#installPbUpgradeBulk}，
	 * 由 Mixin 拦截 PB 原版 {@code AbstractUpgradeItem.useOn} 后调用。
	 *
	 * @param type         升级类型
	 * @param maxAvailable 手上持有的最大数量（{@code stack.getCount()}）
	 * @return 实际安装数量（0 表示未安装）
	 */
	public int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		return pbUpgradeHandler.installPbUpgradeBulk(type, maxAvailable);
	}

	// ===== 原版安装桥接 =====

	/** 获取 PB 原版安装桥接器 — 供 IUpgradeableBlockEntity.getUpgradeHandler 返回 */
	@NotNull
	public PbUpgradeInstallHandler getInstallHandler() {
		return pbUpgradeInstallHandler;
	}

	// ===== 倍率计算（供工厂 productivityModifier/getTicksForBase 调用） =====

	/** 获取生产力倍率 — 影响配方产出数量 */
	public float getProductivityMultiplier() {
		return pbUpgradeHandler.getProductivityMultiplier();
	}

	/** 获取时间倍率 — 影响配方处理速度（越小越快） */
	public float getTimeMultiplier() {
		return pbUpgradeHandler.getTimeMultiplier();
	}

	// ===== 容器同步 =====

	/**
	 * 添加 PB 升级数量和安装进度的容器 tracker
	 * <br/>
	 * 在工厂 addContainerTrackers 中调用，同步所有非内置升级类型的数量和安装计数器。
	 */
	public void addContainerTrackers(MekanismContainer container) {
		// 枚举顺序:PRODUCTIVITY=0, PRODUCTIVITY_2=1, PRODUCTIVITY_3=2, PRODUCTIVITY_4=3,
		// TIME=4, TIME_2=5, GENE_SAMPLER=6, BLOCK=7, SIMULATION=8(内置,跳过)
		int idx = 0;
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (type.isBuiltin()) continue;
			final PbUpgradeType t = type;
			container.track(SyncableInt.create(
					() -> pbUpgradeHandler.getInstalledCount(t),
					count -> pbUpgradeHandler.setClientUpgradeCount(t, count)));
			idx++;
		}
		container.track(SyncableInt.create(
				pbUpgradeHandler::getInstallTicks, pbUpgradeHandler::setClientInstallTicks));
	}

	// ===== NBT 持久化 =====

	/** 保存 PB 升级数量和槽位到 NBT */
	public void save(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		pbUpgradeHandler.saveCounts(nbt);
		pbUpgradeHandler.saveSlots(nbt, provider);
	}

	/**
	 * 从 NBT 加载 PB 升级数量和槽位
	 * <br/>
	 * 修复 v14 loadSlots/loadCounts 顺序：必须先 loadSlots 恢复槽位,再 loadCounts 恢复数量。
	 * 原理:loadCounts 内部 applyCountWithLimit 在数量超过配置上限时,会将超出部分注入输出槽。
	 * 若 loadSlots 未先执行,输出槽为空,注入的超出部分会被后续 loadSlots 覆盖,导致升级物品凭空消失。
	 */
	public void load(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		pbUpgradeHandler.loadSlots(nbt, provider);
		pbUpgradeHandler.loadCounts(nbt);
	}
}
