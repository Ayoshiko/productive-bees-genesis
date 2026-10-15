package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbApiaryUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbCentrifugeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.mek.*;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerLevel;

/** 两种已准入成员的物理规则入口；交换与所有权提交仍由同一个服务负责。 */
record MemberUpgradeTarget(TileEntityMekanism machine, ManagedBeeAccess combined, boolean apiary) {
	static MemberUpgradeTarget find(ServerLevel level, NetworkSavedData authority, NetworkDirectory directory, OwnedMachineRecord record) {
		return find(level, authority, directory, record, record.bees() != null || record.combined() != null);
	}
	static MemberUpgradeTarget find(ServerLevel level, NetworkSavedData authority, NetworkDirectory directory, OwnedMachineRecord record, boolean apiary) {
		if (record.combined() != null) {
			var access = ManagedBeeAccess.capture(level, authority, record);
			return access == null ? null : new MemberUpgradeTarget(null, access, apiary);
		}
		TileEntityMekanism tile = record.bees() != null
				? ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class)
				: ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekanism.class);
		return tile == null || !MachineAssetStore.supports(tile)
				? null : new MemberUpgradeTarget(tile, null, tile instanceof TileEntityMekApiary);
	}
	static long revision(OwnedMachineRecord record) { return record.combined() != null ? record.combined().revision() : record.bees() != null ? record.bees().revision() : record.centrifuge().revision(); }
	static long energy(OwnedMachineRecord record) { return record.combined() != null ? record.combined().energy() : record.bees() != null ? record.bees().energy() : record.centrifuge().energy(); }
	boolean supports(Upgrade upgrade) {
		if (combined != null) return upgrade == Upgrade.SPEED || upgrade == Upgrade.ENERGY;
		return machine.getComponent().supports(upgrade) && (!upgrade.name().equals("STACK")
				|| machine instanceof AbstractMekCentrifugeFactory && StaticCentrifugeAdapter.supports(machine));
	}
	boolean supports(PbUpgradeType upgrade) {
		if (combined != null) return (apiary ? com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades.APIARY_TYPES
				: com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades.CENTRIFUGE_TYPES).contains(upgrade);
		return machine instanceof TileEntityMekApiary hive
				? PbApiaryUpgradeCounts.supported(upgrade) && hive.isPbUpgradeSupported(upgrade)
				: PbCentrifugeUpgradeCounts.supported(upgrade) && ((IPbUpgradeProvider) machine).isPbUpgradeSupported(upgrade);
	}
	int limit(PbUpgradeType upgrade) {
		if (combined != null) return com.ayoshiko.productivebeesgenesis.multiblock.world.MachineUpgradeProfiles.limit(slot(null, upgrade));
		return machine instanceof TileEntityMekApiary hive ? hive.getPbUpgradeLimit(upgrade) : ((IPbUpgradeProvider) machine).getPbUpgradeLimit(upgrade);
	}
	java.util.Map<PbUpgradeType, Integer> pbCounts(AssetImage image) {
		if (combined != null) return combined.record().combined().upgrades().pbCounts(apiary);
		var extra = image.copy().getCompound("extra");
		return machine instanceof TileEntityMekApiary ? PbApiaryUpgradeCounts.read(extra) : PbCentrifugeUpgradeCounts.read(extra);
	}
	void validate(AssetImage image) {
		if (combined != null) return;
		if (machine instanceof TileEntityMekApiary hive) StaticApiaryAdapter.validateUpgrades(hive, image);
		else StaticCentrifugeAdapter.validateUpgrades(machine, image);
	}
	com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity capability(AssetImage image) {
		return machine instanceof TileEntityMekApiary hive ? StaticApiaryAdapter.upgradeCapacity(hive, image)
				: StaticCentrifugeAdapter.upgradeCapacity(machine, image);
	}
	boolean current(ServerLevel level, NetworkSavedData authority, NetworkDirectory directory, OwnedMachineRecord record) {
		return combined != null ? combined.current() : equals(find(level, authority, directory, record));
	}
	int slot(Upgrade nativeType, PbUpgradeType pb) {
		return pb == null ? (apiary ? 0 : 2) + (nativeType == Upgrade.ENERGY ? 1 : 0)
				: com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades.slot(apiary, pb);
	}
	int installed(OwnedMachineRecord record, Upgrade nativeType, PbUpgradeType pb) {
		return record.combined() != null ? record.combined().upgrades().count(slot(nativeType, pb))
				: pb != null ? pbCounts(record.assets()).getOrDefault(pb, 0)
				: com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(nativeType, 0);
	}
	com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity capability(OwnedMachineRecord record) {
		return record.combined() == null ? capability(record.assets())
				: com.ayoshiko.productivebeesgenesis.multiblock.world.MachineUpgradeProfiles.capacity(record.combined().upgrades(), apiary, record.combined().energyCapacity());
	}
	NetworkCheckpoint change(NetworkCheckpoint root, OwnedMachineRecord record, Upgrade upgrade, PbUpgradeType pb, int delta) {
		if (record.combined() != null) {
			var change = record.combined().exchangeUpgrade(slot(upgrade, pb), delta);
			if (delta > 0) {
				var next = change.apply(record.combined());
				com.ayoshiko.productivebeesgenesis.multiblock.world.MachineUpgradeProfiles.apiary(next.upgrades());
				com.ayoshiko.productivebeesgenesis.multiblock.world.MachineUpgradeProfiles.centrifuge(next.upgrades());
			}
			return root.exchangeCombined(record.claim().member(), change);
		}
		var change = pb != null ? MemberUpgradeChange.pb(record, pb, delta, limit(pb))
				: upgrade == Upgrade.ENERGY ? MemberUpgradeChange.energy(record, delta, capacity(installed(record, upgrade, null) + delta))
				: upgrade.name().equals("STACK") ? MemberUpgradeChange.stack(record, upgrade, delta) : MemberUpgradeChange.speed(record, delta);
		validate(change.candidate().assets()); return root.exchangeUpgrade(change);
	}
	long capacity(int installed) {
		return machine instanceof TileEntityMekApiary hive ? StaticApiaryAdapter.energyCapacity(hive, installed)
				: StaticCentrifugeAdapter.energyCapacity(machine, installed);
	}
}
