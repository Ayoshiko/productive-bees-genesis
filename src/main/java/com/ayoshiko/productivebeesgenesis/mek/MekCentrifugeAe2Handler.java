package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Collections;
import java.util.List;

import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import net.minecraft.nbt.CompoundTag;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;

/**
 * 基础MEK离心机AE2集成处理器
 * <br/>
 * 从 {@link TileEntityMekCentrifuge} 抽取的 AE2 相关逻辑，包括：
 * <ul>
 *   <li>持有 {@link MekAe2LifecycleHandler} 实例（网格节点、AEItemKey 缓存、待连接标志）</li>
 *   <li>生命周期委托（加载/移除/区块卸载/连接）</li>
 *   <li>per-tile AE2 输出/输入开关切换</li>
 *   <li>AE2 状态的 NBT 持久化</li>
 *   <li>AE2 相关容器同步器注册</li>
 *   <li>输入槽列表供 AE2 拉取器使用</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：只管理 AE2 集成，不涉及配方处理或槽位管理</li>
 *   <li>依赖倒置：持有 {@link TileEntityMekCentrifuge} 引用访问父类字段和回调</li>
 * </ul>
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
class MekCentrifugeAe2Handler {

	/** 所属方块实体引用 */
	private final TileEntityMekCentrifuge tile;

	/** AE2 生命周期处理器 — 封装网格节点、AEItemKey 缓存、待连接标志和复用缓冲区 */
	private final MekAe2LifecycleHandler lifecycleHandler = new MekAe2LifecycleHandler();

	MekCentrifugeAe2Handler(TileEntityMekCentrifuge tile) {
		this.tile = tile;
	}

	/** 获取生命周期处理器 — 供接口 default 方法委托使用 */
	MekAe2LifecycleHandler getLifecycleHandler() {
		return lifecycleHandler;
	}

	/** 获取 AE2 状态持有者 — 供 saveHandler 和外部访问 per-tile 状态 */
	Ae2OutputStateHolder getStateHolder() {
		return lifecycleHandler.getStateHolder();
	}

	// ===== 生命周期委托 =====

	/** 方块实体加载完成时准备 AE2 网格节点（不接入网格，避免区块加载递归栈溢出） */
	void handleLoad() {
		lifecycleHandler.handleLoad(tile);
	}

	/** 方块被移除时销毁 AE2 网格节点并清空状态 */
	void handleRemove() {
		lifecycleHandler.handleRemove(tile);
	}

	/** 区块卸载时销毁 AE2 网格节点（幂等，与 handleRemove 重复调用安全） */
	void handleChunkUnload() {
		lifecycleHandler.handleChunkUnload(tile);
	}

	/** 尝试连接 AE2 网格节点到网络（每 tick 调用，内部幂等） */
	void tryConnectNode() {
		lifecycleHandler.tryConnectNode(tile);
	}

	// ===== NBT 持久化 =====

	/** 保存 AE2 网格节点 NBT */
	void saveNodeNBT(CompoundTag nbt) {
		lifecycleHandler.saveNodeNBT(tile, nbt);
	}

	/** 加载 AE2 网格节点 NBT */
	void loadNodeNBT(CompoundTag nbt) {
		lifecycleHandler.loadNodeNBT(tile, nbt);
	}

	/** 保存 per-tile AE2 输出/输入开关状态到 NBT */
	void savePerTileState(CompoundTag nbt) {
		getStateHolder().savePerTileState(nbt);
	}

	/** 从 NBT 加载 per-tile AE2 输出/输入开关状态 */
	void loadPerTileState(CompoundTag nbt) {
		getStateHolder().loadPerTileState(nbt);
	}

	// ===== per-tile 开关切换 =====

	/** 切换 per-tile AE2 物品输出开关（供网络包 handler 调用） */
	void toggleAeItemOutput() {
		Ae2OutputStateHolder holder = getStateHolder();
		holder.setAeItemOutputEnabled(!holder.isAeItemOutputEnabled());
		tile.markForSave();
	}

	/** 切换 per-tile AE2 流体输出开关（供网络包 handler 调用） */
	void toggleAeFluidOutput() {
		Ae2OutputStateHolder holder = getStateHolder();
		holder.setAeFluidOutputEnabled(!holder.isAeFluidOutputEnabled());
		tile.markForSave();
	}

	/** 切换 per-tile AE2 输入拉取开关（供网络包 handler 调用） */
	void toggleAeItemInput() {
		getStateHolder().toggleAeItemInputEnabled();
		tile.markForSave();
	}

	/** 切换 per-tile AE2 输入 NBT 忽略开关（供网络包 handler 调用） */
	void toggleAeInputNbtIgnore() {
		getStateHolder().toggleAeInputNbtIgnore();
		tile.markForSave();
	}

	// ===== 输入槽访问 =====

	/**
	 * 获取用于拉取的输入槽列表 — 基础离心机只有1个输入槽
	 * <br/>
	 * 拉取器按顺序填充，槽满则跳到下一个。基础机固定返回单元素列表。
	 */
	List<IInventorySlot> getInputSlotsForPull() {
		return Collections.singletonList(tile.accessor().productivebeesgenesis$getInputSlot());
	}

	// ===== 容器同步器注册 =====

	/**
	 * 注册 AE2 相关的容器同步器
	 * <br/>
	 * 同步 per-tile AE2 输出/输入开关和输入过滤模式，供 GUI 按钮实时反映状态。
	 * 无条件添加避免客户端/服务端 tracker 数量不一致。
	 */
	void addAe2Trackers(MekanismContainer container) {
		Ae2OutputStateHolder holder = getStateHolder();
		// per-tile AE2 输出开关同步
		container.track(SyncableBoolean.create(
				holder::isAeItemOutputEnabled,
				holder::setAeItemOutputEnabled));
		container.track(SyncableBoolean.create(
				holder::isAeFluidOutputEnabled,
				holder::setAeFluidOutputEnabled));
		// per-tile AE2 输入拉取开关同步
		container.track(SyncableBoolean.create(
				holder::isAeItemInputEnabled,
				holder::setAeItemInputEnabled));
		container.track(SyncableBoolean.create(
				holder::isAeInputNbtIgnore,
				holder::setAeInputNbtIgnore));
		// per-tile 离心机熔炉配方兼容开关同步（供 GUI 按钮实时反映状态）
		container.track(SyncableBoolean.create(
				holder::isSmeltingCompatEnabled,
				holder::setSmeltingCompatEnabled));
		container.track(SyncableBoolean.create(
				holder::isCentrifugeDirectAeOutputEnabled,
				holder::setCentrifugeDirectAeOutputEnabled));
		// per-tile AE2 输入过滤模式同步（ordinal：0=DISABLED, 1=WHITELIST, 2=BLACKLIST）
		// 供 GUI 按钮实时反映模式切换；条目列表由 SyncAeInputFilterEntriesPayload 单独推送
		container.track(SyncableInt.create(
				() -> holder.getOrCreateInputFilter().getFilterMode().ordinal(),
				v -> holder.getOrCreateInputFilter().setFilterMode(Ae2InputFilter.FilterMode.values()[v])));
	}
}
