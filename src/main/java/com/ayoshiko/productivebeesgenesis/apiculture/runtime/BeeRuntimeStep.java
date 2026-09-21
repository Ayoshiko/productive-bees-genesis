package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 一次调度至多接纳一个真实 tick；既有冻结结果优先交付，不能以等待时长补产。 */
final class BeeRuntimeStep {
	static NetworkRuntime.Status run(ServerLevel level, NetworkSavedData data, NetworkDirectory directory,
			UUID member, int slot, boolean running) {
		var record = data.checkpoint().ownedMachines().get(member);
		if (record == null || record.bees() == null) return NetworkRuntime.Status.UNAVAILABLE;
		var state = record.bees(); var bee = state.bees().stream().filter(value -> value.slot() == slot).findFirst().orElse(null);
		if (bee == null) return NetworkRuntime.Status.UNAVAILABLE;
		var service = new NetworkBeeService(data, directory);
		if (!bee.frozen().isZero()) {
			return service.settle(level, member, slot, bee.revision()) ? NetworkRuntime.Status.SETTLING : NetworkRuntime.Status.UNAVAILABLE;
		}
		int ticks = bee.pendingCycles() > 0 ? 0 : 1;
		if (ticks > 0) {
			if (!running) return NetworkRuntime.Status.STOPPED;
			if (!state.networkPowered()) {
				if (!NetworkEnergyService.migrate(level, data, directory, member, data.checkpoint().revision(), false)) return NetworkRuntime.Status.ENERGY;
				state = data.checkpoint().ownedMachines().get(member).bees(); bee = state.bee(slot);
			}
		}
		var result = service.advance(level, member, slot, bee.revision(), data.checkpoint().policyRevision(), bee.plan().capabilityRevision(), ticks, 1, false);
		if (result == BeeWorkExecutor.Status.READY) {
			var updated = data.checkpoint().ownedMachines().get(member).bees().bee(slot);
			if (!updated.frozen().isZero()) service.settle(level, member, slot, updated.revision());
			return ticks == 0 ? NetworkRuntime.Status.SETTLING : NetworkRuntime.Status.RUNNING;
		}
		return switch (result) {
			case ENERGY -> NetworkRuntime.Status.ENERGY;
			case FLOWER -> NetworkRuntime.Status.FLOWER;
			case ENVIRONMENT -> NetworkRuntime.Status.ENVIRONMENT;
			case STALE_PLAN -> NetworkRuntime.Status.STALE_PLAN;
			case DISABLED -> NetworkRuntime.Status.STOPPED;
			case UNLOADED -> NetworkRuntime.Status.UNAVAILABLE;
			default -> NetworkRuntime.Status.SETTLING;
		};
	}
	private BeeRuntimeStep() { }
}
