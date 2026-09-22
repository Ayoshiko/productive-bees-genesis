package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.RuntimeProcessingMatch;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

/** 一成员一续查游标；每步检查一个库存键或有限保留条目，已有作业不经过新配方搜索。 */
final class CentrifugeRuntimeStep {
	private static final ReservePolicy DEFAULT_RESERVES = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
	private static final class Search {
		ProductKey after, input;
		int ruleOffset, examined, remaining = -1;
		SchedulerCheckpoint scheduler;
		ProcessingRule rule;
		ReserveAllowanceScan reserve;
		LedgerCheckpoint ledger;
		boolean reserved;
		void restart(SchedulerCheckpoint next) { scheduler = next; ruleOffset = examined = 0; remaining = -1; input = null; reserve = null; ledger = null; rule = null; reserved = false; }
	}
	private final Map<UUID, Search> searches = new ConcurrentHashMap<>();
	private RuntimeProcessingRules rules;
	private ProductPolicyRegistry policy;
	private long recipeEpoch = -1;
	void forget(UUID member) { searches.remove(member); }
	NetworkRuntime.Status run(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, UUID member, boolean running) {
		var current = data.checkpoint(); var record = current.ownedMachines().get(member);
		var tile = ManagedProductionAccess.member(level, data, directory, record, TileEntityMekCentrifuge.class);
		if (tile == null) return NetworkRuntime.Status.UNAVAILABLE;
		if (record.centrifuge() != null && !record.centrifuge().drained()) {
			var search = searches.get(member); if (search != null) search.restart(current.scheduler());
			return work(level, data, directory, member, running && tile.canFunction());
		}
		if (!running || !tile.canFunction()) {
			var search = searches.get(member); if (search != null) search.restart(current.scheduler());
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
		var stock = index.view(); var search = searches.computeIfAbsent(member, ignored -> new Search());
		if (search.scheduler != current.scheduler()) search.restart(current.scheduler());
		if (search.ruleOffset >= rules.size()) { var status = search.reserved ? NetworkRuntime.Status.RESERVED : NetworkRuntime.Status.NO_INPUT; search.restart(current.scheduler()); return status; }
		if (search.input == null) {
			search.rule = rules.rule((rules.first(current.scheduler()) + search.ruleOffset) % rules.size());
			var exact = search.rule != null && search.rule.selector() instanceof ProcessingRule.Match match && match.matcher().mode() == ProductMatcher.Mode.EXACT ? match.matcher().template() : null;
			if (search.remaining < 0) { search.remaining = exact == null ? stock.size() : 1; search.examined = 0; }
			if (search.examined >= search.remaining) { search.ruleOffset++; search.remaining = -1; return NetworkRuntime.Status.PREPARING; }
			var key = exact == null ? stock.nextKey(search.after) : exact; if (key == null) key = stock.nextKey(null);
			search.examined++; search.after = key;
			if (key == null) return NetworkRuntime.Status.NO_INPUT;
			if (stock.amount(key).isZero() || !RuntimeProcessingMatch.accepts(search.rule, key) || !policy.evaluate(key).allowed()) return NetworkRuntime.Status.PREPARING;
			search.input = key;
		}
		var service = new NetworkCentrifugeService(data, directory, policy);
		CentrifugeLaneAllocator.Candidate candidate;
		try {
			if (record.centrifuge() == null) {
				if (!service.activate(level, member, current.revision(), search.input)) return NetworkRuntime.Status.UNAVAILABLE;
				return NetworkRuntime.Status.PREPARING;
			}
			candidate = service.candidate(level, member, search.input);
		} catch (IllegalArgumentException unsupported) { nextInput(search); return NetworkRuntime.Status.UNSUPPORTED; }
		if (candidate == null) return NetworkRuntime.Status.UNAVAILABLE;
		if (!record.centrifuge().networkPowered()) {
			return NetworkEnergyService.migrate(level, data, directory, member, current.revision(), false) ? NetworkRuntime.Status.PREPARING : NetworkRuntime.Status.ENERGY;
		}
		var reserves = search.rule == null ? DEFAULT_RESERVES : search.rule.reserves();
		if (search.reserve == null || search.ledger != current.ledger()) { search.ledger = current.ledger(); search.reserve = new ReserveAllowanceScan(stock, reserves, search.input); }
		// 固定小步检查；默认无保留可在一次名额内完成，复杂层不会无界扫描。
		boolean ready = false; for (int i = 0; i < 8 && !ready; i++) ready = search.reserve.step();
		if (!ready) return NetworkRuntime.Status.PREPARING;
		var permit = search.reserve.permit();
		if (permit.amount().isZero()) { search.reserved = true; nextInput(search); return NetworkRuntime.Status.PREPARING; }
		int limit = search.rule == null ? Integer.MAX_VALUE : search.rule.batchLimit();
		if (!service.assignReserved(level, candidate, rules.claim(current.scheduler(), search.rule), reserves, permit, limit, level.random.nextLong()))
			return NetworkRuntime.Status.ENERGY;
		search.restart(data.checkpoint().scheduler());
		return NetworkRuntime.Status.PREPARING;
	}
	private static void nextInput(Search search) { search.input = null; search.reserve = null; search.ledger = null; }
	private static NetworkRuntime.Status work(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, UUID member, boolean running) {
		var state = data.checkpoint().ownedMachines().get(member).centrifuge(); var job = state.jobs().get(0);
		boolean advanced = false;
		var service = new NetworkCentrifugeService(data, directory, new ProductPolicyRegistry(new ProductPolicySnapshot(data.checkpoint().policyRevision(), List.of(), List.of())));
		if (!job.paid()) {
			if (!running) return NetworkRuntime.Status.STOPPED;
			if (!state.networkPowered() && !NetworkEnergyService.migrate(level, data, directory, member, data.checkpoint().revision(), false)) return NetworkRuntime.Status.ENERGY;
			state = data.checkpoint().ownedMachines().get(member).centrifuge();
			if (!service.work(level, member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false,
					com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.beeNetwork.maintenanceFe.get())) return NetworkRuntime.Status.ENERGY;
			advanced = true;
			job = data.checkpoint().ownedMachines().get(member).centrifuge().jobs().get(0);
		}
		if (job.paid()) {
			state = data.checkpoint().ownedMachines().get(member).centrifuge();
			if (!job.sampled()) service.work(level, member, state.revision(), NetworkCentrifugeService.Action.FREEZE, 0, false);
			state = data.checkpoint().ownedMachines().get(member).centrifuge();
			service.work(level, member, state.revision(), NetworkCentrifugeService.Action.SETTLE, 0, false);
		}
		return advanced ? NetworkRuntime.Status.RUNNING : NetworkRuntime.Status.SETTLING;
	}
}
