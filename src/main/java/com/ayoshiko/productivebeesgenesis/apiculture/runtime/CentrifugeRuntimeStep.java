package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.RuntimeProcessingMatch;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 同能力成员共享候选续查；每步检查一个库存键或有限保留条目，已有作业不经过搜索。 */
final class CentrifugeRuntimeStep {
	private static final ReservePolicy DEFAULT_RESERVES = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
	private final CentrifugeInputSearches searches = new CentrifugeInputSearches();
	private RuntimeProcessingRules rules;
	private ProductPolicyRegistry policy;
	private long recipeEpoch = -1;
	void forget(UUID member) { searches.forget(member); }
	void clear() { searches.invalidate(); }
	NetworkRuntime.Status run(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, UUID member, int lane, boolean running) {
		var current = data.checkpoint(); var record = current.ownedMachines().get(member);
		var tile = ManagedProductionAccess.member(level, data, directory, record, mekanism.common.tile.base.TileEntityMekanism.class);
		if (record == null) { forget(member); return NetworkRuntime.Status.UNAVAILABLE; }
		boolean combined = record.combined() != null && com.ayoshiko.productivebeesgenesis.multiblock.world.MachineNetworkAccess.member(level, data, directory, record) != null;
		if (tile == null && !combined) { forget(member); return NetworkRuntime.Status.UNAVAILABLE; }
		boolean canFunction = combined || tile.canFunction();
		if (lane < 0 || lane >= record.workLayout().centrifugeLanes()) return NetworkRuntime.Status.UNAVAILABLE;
		if (record.centrifugeState() != null && record.centrifugeState().jobs().containsKey(lane)) {
			// 活跃进程不能重置其它空闲进程共用的候选游标。
			return work(level, data, directory, member, lane, running && canFunction);
		}
		if (!running || !canFunction) {
			forget(member);
			return NetworkRuntime.Status.STOPPED;
		}
		if (rules == null || !rules.matches(current.scheduler())) { rules = new RuntimeProcessingRules(current.scheduler()); searches.clear(); }
		if (!rules.ready() && !rules.step()) return NetworkRuntime.Status.PREPARING;
		if (!rules.supported()) return NetworkRuntime.Status.RULES_UNSUPPORTED;
		if (rules.size() == 0) return NetworkRuntime.Status.NO_INPUT;
		long epoch = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (policy == null || recipeEpoch != epoch || policy.snapshot().revision() != current.policyRevision()) {
			policy = null; searches.clear();
			var compiled = RuntimeProductPolicies.get(level, current.policyRevision());
			if (compiled != null) { policy = new ProductPolicyRegistry(compiled); recipeEpoch = epoch; }
			return NetworkRuntime.Status.PREPARING;
		}
		var index = data.processingStock(); if (!index.ready() && !index.step()) return NetworkRuntime.Status.PREPARING;
		var service = new NetworkCentrifugeService(data, directory, policy);
		CentrifugeInputSearches.Cursor search;
		try { search = searches.cursor(member, service.profile(level, member)); }
		catch (IllegalArgumentException unsupported) { forget(member); return NetworkRuntime.Status.UNSUPPORTED; }
		var stock = index.view();
		var selected = search.select(stock, rules, current.scheduler(), key -> RuntimeProcessingMatch.accepts(search.rule, key) && policy.evaluate(key).allowed());
		if (selected != CentrifugeInputSearches.Result.READY) return switch (selected) {
			case EMPTY -> NetworkRuntime.Status.NO_INPUT;
			case RESERVED -> NetworkRuntime.Status.RESERVED;
			default -> NetworkRuntime.Status.PREPARING;
		};
		CentrifugeLaneAllocator.Candidate candidate;
		try {
			if (record.centrifugeState() == null) {
				if (!service.activate(level, member, current.revision(), search.input)) return NetworkRuntime.Status.UNAVAILABLE;
				return NetworkRuntime.Status.PREPARING;
			}
			candidate = service.candidate(level, member, lane, search.input);
		} catch (IllegalArgumentException unsupported) { search.nextInput(); return NetworkRuntime.Status.UNSUPPORTED; }
		if (candidate == null) return NetworkRuntime.Status.UNAVAILABLE;
		if (!record.centrifugeState().networkPowered()) {
			return NetworkEnergyService.migrate(level, data, directory, member, current.revision(), false) ? NetworkRuntime.Status.PREPARING : NetworkRuntime.Status.ENERGY;
		}
		var reserves = search.rule == null ? DEFAULT_RESERVES : search.rule.reserves();
		if (search.reserve == null || search.ledger != current.ledger()) { search.ledger = current.ledger(); search.reserve = new ReserveAllowanceScan(stock, reserves, search.input); }
		// 固定小步检查；默认无保留可在一次名额内完成，复杂层不会无界扫描。
		boolean ready = false; for (int i = 0; i < 8 && !ready; i++) ready = search.reserve.step();
		if (!ready) return NetworkRuntime.Status.PREPARING;
		var permit = search.reserve.permit();
		if (permit.amount().isZero()) { search.reserved = true; search.nextInput(); return NetworkRuntime.Status.PREPARING; }
		int limit = search.rule == null ? Integer.MAX_VALUE : search.rule.batchLimit();
		if (!service.assignReserved(level, candidate, rules.claim(current.scheduler(), search.rule), reserves, permit, limit, level.random.nextLong()))
			return NetworkRuntime.Status.ENERGY;
		search.restart(data.checkpoint().scheduler());
		return NetworkRuntime.Status.PREPARING;
	}
	private static NetworkRuntime.Status work(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, UUID member, int lane, boolean running) {
		var state = data.checkpoint().ownedMachines().get(member).centrifugeState(); var job = state.jobs().get(lane);
		boolean advanced = false;
		var service = new NetworkCentrifugeService(data, directory, new ProductPolicyRegistry(new ProductPolicySnapshot(data.checkpoint().policyRevision(), List.of(), List.of())));
		if (!job.paid()) {
			if (!running) return NetworkRuntime.Status.STOPPED;
			if (!state.networkPowered() && !NetworkEnergyService.migrate(level, data, directory, member, data.checkpoint().revision(), false)) return NetworkRuntime.Status.ENERGY;
			state = data.checkpoint().ownedMachines().get(member).centrifugeState();
			if (!service.work(level, member, lane, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false,
					com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.beeNetwork.maintenanceFe.get())) return NetworkRuntime.Status.ENERGY;
			advanced = true;
			job = data.checkpoint().ownedMachines().get(member).centrifugeState().jobs().get(lane);
		}
		if (job.paid()) {
			state = data.checkpoint().ownedMachines().get(member).centrifugeState();
			if (!job.sampled()) service.work(level, member, lane, state.revision(), NetworkCentrifugeService.Action.FREEZE, 0, false);
			state = data.checkpoint().ownedMachines().get(member).centrifugeState();
			service.work(level, member, lane, state.revision(), NetworkCentrifugeService.Action.SETTLE, 0, false);
		}
		return advanced ? NetworkRuntime.Status.RUNNING : NetworkRuntime.Status.SETTLING;
	}
}
