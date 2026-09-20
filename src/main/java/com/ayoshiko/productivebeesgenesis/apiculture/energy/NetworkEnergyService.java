package com.ayoshiko.productivebeesgenesis.apiculture.energy;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 激活后的成员 FE 纳入统一账户；不从已清空的物理机器再读取或提取能量。 */
public final class NetworkEnergyService {
	public static boolean migrate(ServerLevel level, NetworkSavedData authority, NetworkDirectory directory,
			UUID member, long expectedCheckpoint, boolean simulate) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (!com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.beeNetwork.enabled.get() || current.revision() != expectedCheckpoint || record == null) return false;
		var tile = record.bees() != null ? ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class)
				: record.centrifuge() != null ? ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class) : null;
		if (tile == null) return false;
		var next = current.migrateEnergy(member); if (next == current) return false;
		if (!simulate) { authority.publish(next); directory.requestSave(authority); }
		return true;
	}
	private NetworkEnergyService() { }
}
