package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.mek.*;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerLevel;

/** 两种已准入成员的物理规则入口；交换与所有权提交仍由同一个服务负责。 */
record MemberUpgradeTarget(TileEntityMekanism machine) {
	static MemberUpgradeTarget find(ServerLevel level, NetworkSavedData authority, NetworkDirectory directory, OwnedMachineRecord record) {
		TileEntityMekanism tile = record.bees() != null
				? ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class)
				: ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class);
		return tile == null || tile.getClass() != TileEntityMekApiary.class && tile.getClass() != TileEntityMekCentrifuge.class
				? null : new MemberUpgradeTarget(tile);
	}
	static long revision(OwnedMachineRecord record) { return record.bees() != null ? record.bees().revision() : record.centrifuge().revision(); }
	static long energy(OwnedMachineRecord record) { return record.bees() != null ? record.bees().energy() : record.centrifuge().energy(); }
	boolean supports(Upgrade upgrade) { return machine.getComponent().supports(upgrade); }
	boolean supports(PbUpgradeType upgrade) { return machine instanceof TileEntityMekCentrifuge centrifuge && centrifuge.isPbUpgradeSupported(upgrade); }
	int limit(PbUpgradeType upgrade) { return machine instanceof TileEntityMekCentrifuge centrifuge ? centrifuge.getPbUpgradeLimit(upgrade) : 0; }
	void validate(AssetImage image) {
		if (machine instanceof TileEntityMekApiary hive) StaticApiaryAdapter.validateUpgrades(hive, image);
		else StaticCentrifugeAdapter.validateUpgrades((TileEntityMekCentrifuge) machine, image);
	}
	long capacity(int installed) {
		return machine instanceof TileEntityMekApiary hive ? StaticApiaryAdapter.energyCapacity(hive, installed)
				: StaticCentrifugeAdapter.energyCapacity((TileEntityMekCentrifuge) machine, installed);
	}
}
