package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.container.sync.SyncableItemStack;
import mekanism.common.inventory.container.sync.SyncableLong;
import mekanism.common.inventory.slot.BasicInventorySlot;

/**
 * 机械蜂箱容器同步器注册（纯静态，无状态）
 * <br/>
 * 从 {@link TileEntityMekApiary} 拆分而来，职责（SRP）：把蜂箱内部状态
 * （PB 升级、选中槽位、AE2 per-tile 开关、直连开关、喂食槽逐格禁用）注册为容器 tracker，
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
		container.track(SyncableBoolean.create(
				tile::isCentrifugePriorityEnabled,
				tile::setCentrifugePriorityEnabled));
		// 产物直通开关（无条件添加，保持客户端/服务端 tracker 数量一致）
		container.track(SyncableBoolean.create(
				tile::isDirectContainerOutputEnabled,
				tile::setDirectContainerOutputEnabled));
		// 喂食槽转化开关（无条件添加，保持客户端/服务端 tracker 数量一致）
		container.track(SyncableBoolean.create(
				tile::isFeederConversionEnabled,
				tile::setFeederConversionEnabled));
		// 喂食槽逐格禁用位掩码：60 槽最多 1 个 long，按槽位数注册 ceil(N/64) 个 tracker，
		// 比"每格一个 SyncableBoolean"少两个数量级的 tracker，且 MEK 只在值变化时发包。
		// 字数量由 getDisabledWordCount 统一计算，客户端与服务端读同一个 tile 配置，必然一致。
		FeederSlotManager feederManager = tile.getFeederSlotManager();
		int disabledWords = feederManager.getDisabledWordCount();
		for (int word = 0; word < disabledWords; word++) {
			int wordIndex = word;
			container.track(SyncableLong.create(
					() -> feederManager.getDisabledWord(wordIndex),
					value -> feederManager.setDisabledWord(wordIndex, value)));
		}
		// Physical pages stay synchronized while the GUI is open. The visible proxy slots alone
		// cannot observe changes made to a hidden page, which otherwise leaves stale client stacks.
		for (BasicInventorySlot outputSlot : tile.getOutputSlots()) {
			container.track(SyncableItemStack.create(
					outputSlot::getStack, outputSlot::setStackUnchecked));
		}
	}
}
