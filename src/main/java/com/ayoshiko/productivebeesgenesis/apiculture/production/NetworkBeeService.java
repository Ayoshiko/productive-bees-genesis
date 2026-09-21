package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 服务器蜂工作事务；每次提交重新校验加载、身份和预期版本，调度器不直接改写进度。 */
public final class NetworkBeeService {
	private final NetworkSavedData authority;
	private final NetworkDirectory directory;
	public NetworkBeeService(NetworkSavedData authority, NetworkDirectory directory) { this.authority = authority; this.directory = directory; }
	public boolean activate(ServerLevel level, UUID member, long expectedCheckpoint, long capabilityRevision) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (!ModConfig.SERVER.beeNetwork.enabled.get() || current.revision() != expectedCheckpoint || record == null || record.bees() != null) return false;
		var hive = member(level, record); if (hive == null) return false;
		var state = StaticApiaryAdapter.compile(level, hive, record, current.policyRevision(), capabilityRevision);
		authority.publish(current.withOwnership(record.withBees(state))); directory.requestSave(authority); return true;
	}
	public BeeWorkExecutor.Status advance(ServerLevel level, UUID member, int slot, long beeRevision,
			long recipeRevision, long capabilityRevision, int ticks, int samplingBudget, boolean simulate) {
		return advance(level, member, slot, beeRevision, recipeRevision, capabilityRevision, ticks, samplingBudget, simulate, 0);
	}
	public BeeWorkExecutor.Status advance(ServerLevel level, UUID member, int slot, long beeRevision,
			long recipeRevision, long capabilityRevision, int ticks, int samplingBudget, boolean simulate, long maintenanceFee) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.bees() == null) return BeeWorkExecutor.Status.DISABLED;
		var hive = member(level, record); if (hive == null) return BeeWorkExecutor.Status.UNLOADED;
		if (ticks > 0 && (!StaticApiaryAdapter.currentPlan(level, hive, record.bees().bee(slot)) || recipeRevision != current.policyRevision())) return BeeWorkExecutor.Status.STALE_PLAN;
		boolean flower = record.bees().feeding() != null && com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter.flower(
				record.bees().feeding(), slot, net.minecraft.resources.ResourceLocation.parse(record.bees().bee(slot).plan().beeType()), level.registryAccess());
		var context = new BeeWorkExecutor.Context(true, hive.canFunction() && ModConfig.SERVER.beeNetwork.enabled.get(), flower,
				recipeRevision, capabilityRevision, new BeeWorkConditions.Environment(level.dimensionType().hasFixedTime(), level.isNight(), level.isRaining(), level.isThundering()));
		long tick = level.getServer().getTickCount(), fee = ticks > 0 ? maintenanceFee : 0;
		long due = authority.maintenanceDue(tick, fee);
		if (due > 0 && (!record.bees().networkPowered() || current.energy().stored() < due)) return BeeWorkExecutor.Status.ENERGY;
		var result = BeeWorkExecutor.advance(record.bees(), slot, beeRevision, context, ticks, samplingBudget, record.bees().networkPowered() ? current.energy().stored() - due : record.bees().energy());
		if (!simulate && result.status() == BeeWorkExecutor.Status.READY) {
			// publish 的 revision 即为脏状态；常规生产随世界保存，不逐蜂启动整域写盘。
			authority.publishMaintainedWork(current, current.applyBeeWork(member, result), tick, fee);
		}
		return result.status();
	}
	public boolean settle(ServerLevel level, UUID member, int slot, long beeRevision) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || member(level, record) == null) return false;
		var next = current.settleBee(member, slot, beeRevision);
		if (next == current) return false;
		authority.publish(next); return true;
	}
	TileEntityMekApiary member(ServerLevel level, OwnedMachineRecord record) {
		return ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class);
	}
}
