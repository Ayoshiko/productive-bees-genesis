package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntime;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import net.minecraft.core.BlockPos;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.DynamicBeeAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 每次只执行一个蜂位；复合工作、FE 与结果均由同一网络检查点提交。 */
public final class CombinedNetworkService {
	public static boolean activate(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, OwnedMachineRecord record) {
		var machine = MachineNetworkAccess.member(level, data, directory, record);
		if (machine == null) return false;
		var before = data.checkpoint();
		var source = new MachineOwnershipEndpoint(machine).decode(record.assets());
		var next = before.activateCombined(record.claim().member(), source);
		if (next == before) return false;
		data.publish(next); directory.requestSave(data); return true;
	}
	public static NetworkRuntime.Status bee(ServerLevel level, NetworkSavedData data, NetworkDirectory directory,
			OwnedMachineRecord record, int slot, boolean running) {
		if (MachineNetworkAccess.member(level, data, directory, record) == null) return NetworkRuntime.Status.UNAVAILABLE;
		var before = data.checkpoint(); var work = record.combined(); var bee = work.bee(slot);
		var member = record.claim().member();
		if (bee.hasFrozen()) { data.publish(before.settleCombinedBee(member, slot, bee.revision())); return NetworkRuntime.Status.SETTLING; }
		int ticks = bee.pendingCycles() > 0 ? 0 : 1;
		if (ticks > 0 && (!running || !bee.enabled())) return NetworkRuntime.Status.STOPPED;
		boolean current = ticks == 0 || StaticApiaryAdapter.currentPlan(level, bee);
		if (!current) return NetworkRuntime.Status.STALE_PLAN;
		boolean flower = ticks == 0 || StaticFeedingAdapter.flower(work.feeding(), slot, ResourceLocation.parse(bee.plan().beeType()), level.registryAccess());
		var context = new BeeWorkExecutor.Context(true, running, flower, bee.plan().recipeRevision(), bee.plan().capabilityRevision(),
				new BeeWorkConditions.Environment(level.dimensionType().hasFixedTime(), level.isNight(), level.isRaining(), level.isThundering()));
		var cycle = ticks > 0 && bee.progress() == 0 ? StaticApiaryAdapter.cycle(MachineUpgradeProfiles.apiary(work.upgrades()), bee, level) : null;
		context = DynamicBeeAdapter.prepare(level, bee, work.feeding(), ticks, cycle, context);
		long tick = level.getServer().getTickCount(), fee = ticks == 0 ? 0 : ModConfig.SERVER.beeNetwork.maintenanceFe.get();
		long due = data.maintenanceDue(tick, fee);
		if (before.energy().stored() < due) return NetworkRuntime.Status.ENERGY;
		var result = BeeWorkExecutor.advanceBee(bee, bee.revision(), context, ticks, 1, before.energy().stored() - due, cycle);
		if (result.status() == BeeWorkExecutor.Status.READY) {
			data.publishMaintainedWork(before, before.applyCombinedBee(member, result), tick, fee);
			var next = data.checkpoint(); var updated = next.ownedMachines().get(member).combined().bee(slot);
			if (bee.generation() == null && updated.generation() != null && updated.generation().phase() == BeeGeneration.Phase.STARTED) {
				var origin = record.claim().origin();
				var generated = DynamicBeeAdapter.generate(level, new BlockPos(origin.x(), origin.y(), origin.z()), updated);
				next = data.checkpoint();
				var currentBee = next.ownedMachines().get(member).combined().bee(slot);
				if (!DynamicBeeAdapter.sameRequest(updated, currentBee)) throw new IllegalStateException("Combined bee generation owner changed");
				data.publish(next.applyCombinedBee(member, BeeWorkExecutor.resolveGeneration(currentBee, generated)));
				RuntimeProductPolicies.recordGenerated(level, generated);
				next = data.checkpoint();
				updated = next.ownedMachines().get(member).combined().bee(slot);
			}
			if (updated.hasFrozen()) data.publish(next.settleCombinedBee(member, slot, updated.revision()));
			return ticks == 0 ? NetworkRuntime.Status.SETTLING : NetworkRuntime.Status.RUNNING;
		}
		return switch (result.status()) {
			case ENERGY -> NetworkRuntime.Status.ENERGY;
			case FLOWER -> NetworkRuntime.Status.FLOWER;
			case ENVIRONMENT -> NetworkRuntime.Status.ENVIRONMENT;
			case STALE_PLAN -> NetworkRuntime.Status.STALE_PLAN;
			case DISABLED -> NetworkRuntime.Status.STOPPED;
			case UNLOADED -> NetworkRuntime.Status.UNAVAILABLE;
			case GENERATION_UNKNOWN -> NetworkRuntime.Status.FAILED;
			default -> NetworkRuntime.Status.SETTLING;
		};
	}
	private CombinedNetworkService() { }
}
