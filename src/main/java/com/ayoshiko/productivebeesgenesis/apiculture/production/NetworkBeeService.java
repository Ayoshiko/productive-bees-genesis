package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.DynamicBeeAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionRecipeIndex.RecipesUnavailableException;
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
		BeeMemberState state;
		try {
			state = StaticApiaryAdapter.compile(level, hive, record, current.policyRevision(), capabilityRevision);
		} catch (RecipesUnavailableException exception) {
			return false;
		}
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
		if (ticks > 0 && !record.bees().bee(slot).enabled()) return BeeWorkExecutor.Status.DISABLED;
		if (ticks > 0 && (!StaticApiaryAdapter.currentPlan(level, hive, record.bees().bee(slot)) || recipeRevision != current.policyRevision())) return BeeWorkExecutor.Status.STALE_PLAN;
		var bee = record.bees().bee(slot); BeeWorkExecutor.Cycle cycle = null;
		if (ticks > 0 && bee.drained()) {
			if (bee.progress() == 0) {
				try {
					cycle = StaticApiaryAdapter.cycle(hive, record, bee);
				} catch (RecipesUnavailableException exception) {
					return BeeWorkExecutor.Status.STALE_PLAN;
				}
			}
			else ticks = Math.min(ticks, bee.plan().cycleTicks() - bee.progress() % bee.plan().cycleTicks());
			if (bee.plan().requiresGeneration() || cycle != null && cycle.effects().enabled())
				ticks = Math.min(ticks, (cycle == null ? bee.plan().cycleTicks() : cycle.cycleTicks()) - bee.progress());
		}
		boolean flower = record.bees().feeding() != null && com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter.flower(
				record.bees().feeding(), slot, net.minecraft.resources.ResourceLocation.parse(record.bees().bee(slot).plan().beeType()), level.registryAccess());
		var context = new BeeWorkExecutor.Context(true, hive.canFunction() && ModConfig.SERVER.beeNetwork.enabled.get(), flower,
				recipeRevision, capabilityRevision, new BeeWorkConditions.Environment(level.dimensionType().hasFixedTime(), level.isNight(), level.isRaining(), level.isThundering()));
		if (record.bees().feeding() != null)
			context = DynamicBeeAdapter.prepare(level, bee, record.bees().feeding().slots(), ticks, cycle, context);
		long tick = level.getServer().getTickCount(), fee = ticks > 0 ? maintenanceFee : 0;
		long due = authority.maintenanceDue(tick, fee);
		if (due > 0 && (!record.bees().networkPowered() || current.energy().stored() < due)) return BeeWorkExecutor.Status.ENERGY;
		var result = BeeWorkExecutor.advance(record.bees(), slot, beeRevision, context, ticks, samplingBudget, record.bees().networkPowered() ? current.energy().stored() - due : record.bees().energy(), cycle);
		if (!simulate && result.status() == BeeWorkExecutor.Status.READY) {
			// publish 的 revision 即为脏状态；常规生产随世界保存，不逐蜂启动整域写盘。
			authority.publishMaintainedWork(current, current.applyBeeWork(member, result), tick, fee);
			var committed = authority.checkpoint();
			var owner = committed.ownedMachines().get(member);
			var started = owner.bees().bee(slot);
			if (bee.generation() == null && started.generation() != null && started.generation().phase() == BeeGeneration.Phase.STARTED) {
				var generated = DynamicBeeAdapter.generate(level, hive.getBlockPos(), started);
				var latest = authority.checkpoint();
				var live = latest.ownedMachines().get(member);
				if (live == null || live.bees() == null || !DynamicBeeAdapter.sameRequest(started, live.bees().bee(slot)))
					throw new IllegalStateException("Bee generation owner changed during its external call");
				authority.publish(latest.applyBeeWork(member, BeeWorkExecutor.resolveGeneration(live.bees(), slot, generated)));
				RuntimeProductPolicies.recordGenerated(level, generated);
			}
			RuntimeProductPolicies.recordProduced(level, authority.checkpoint().ownedMachines().get(member).bees().bee(slot));
		}
		return result.status();
	}
	public boolean setEnabled(ServerLevel level, UUID member, int slot, UUID beeId,
			BeeMemberState.RosterVersion expectedRoster, boolean enabled, boolean simulate) {
		if (!level.getServer().isSameThread() || slot < 0 || beeId == null || expectedRoster == null) return false;
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.bees() == null || slot >= record.bees().slotCount() || record.bees().rosterVersion() != expectedRoster || member(level, record) == null) return false;
		var bee = record.bees().bees().stream().filter(value -> value.slot() == slot).findFirst().orElse(null);
		if (bee == null || !bee.id().equals(beeId)) return false;
		var next = record.bees().withBeeEnabled(slot, beeId, enabled);
		if (!simulate && next != record.bees()) authority.publish(current.withOwnership(record.withBees(next)));
		return true;
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
