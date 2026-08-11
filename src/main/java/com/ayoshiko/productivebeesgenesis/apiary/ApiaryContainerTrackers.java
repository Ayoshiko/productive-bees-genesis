package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;

/**
 * 机械蜂箱容器同步器注册（纯静态，无状态）
 * <br/>
 * 从 {@link TileEntityMekApiary} 拆分而来，职责（SRP）：把蜂箱内部状态
 * （PB 升级、选中槽位、AE2 per-tile 开关、直连开关）注册为容器 tracker，
 * 保证客户端与服务端同步一致。
 */
final class ApiaryContainerTrackers {

	private ApiaryContainerTrackers() {
	}

	/** 注册全部蜂箱 tracker — super.addContainerTrackers 已由调用方执行 */
	static void addTrackers(TileEntityMekApiary tile, MekanismContainer container) {
		// PB 升级数量（按类型）与安装进度
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (type.isBuiltin()) continue;
			container.track(SyncableInt.create(
					() -> tile.pbUpgradeHandler().getPbUpgradeCount(type),
					count -> tile.pbUpgradeHandler().setClientUpgradeCount(type, count)));
		}
		container.track(SyncableInt.create(
				tile.pbUpgradeHandler()::getInstallTicks, tile.pbUpgradeHandler()::setClientUpgradeTicks));
		// 选中蜜蜂槽位（Bug 9）
		container.track(SyncableInt.create(
				tile::getSelectedBeeSlot, tile::setClientSelectedBeeSlot));
		// per-tile AE2 输出开关同步（无条件添加避免客户端/服务端 tracker 数量不一致）
		container.track(SyncableBoolean.create(
				tile.ae2HostAdapter()::isAeItemOutputEnabled,
				tile.ae2HostAdapter()::setAeItemOutputEnabled));
		container.track(SyncableBoolean.create(
				tile.ae2HostAdapter()::isAeFluidOutputEnabled,
				tile.ae2HostAdapter()::setAeFluidOutputEnabled));
		container.track(SyncableBoolean.create(
				tile::isDirectEjectEnabled,
				tile::setDirectEjectEnabled));
		container.track(SyncableBoolean.create(
				tile::isDirectAeOutputEnabled,
				tile::setDirectAeOutputEnabled));
	}
}
