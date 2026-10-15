package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkDirectory;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 服务端离心事务；显式调用和自动调度复用相同的真实成员校验。 */
public final class NetworkCentrifugeService {
	public enum Action { ADVANCE, FREEZE, SETTLE, CANCEL }
	private final NetworkSavedData authority;
	private final NetworkDirectory directory;
	private final ProductPolicyRegistry policy;
	private final long recipeEpoch;
	public NetworkCentrifugeService(NetworkSavedData authority, NetworkDirectory directory, ProductPolicyRegistry policy) {
		this.authority = authority; this.directory = directory; this.policy = policy;
		recipeEpoch = ProductiveBeesGenesis.RECIPE_VERSION.get();
	}
	public boolean activate(ServerLevel level, UUID member, long expectedCheckpoint, ProductKey input) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (!enabled() || current.revision() != expectedCheckpoint || current.policyRevision() != policy.snapshot().revision()
				|| record == null || record.centrifugeState() != null) return false;
		var tile = ManagedProductionAccess.member(level, authority, directory, record, mekanism.common.tile.base.TileEntityMekanism.class);
		if (tile == null) return false;
		StaticCentrifugeAdapter.compile(level, tile, record, policy, input, 0);
		if (authority.checkpoint() != current) return false;
		var assets = record.assets().copy();
		var state = new CentrifugeWorkState(member, 0, record.workLayout().centrifugeLanes(), assets.getLong("energy"), assets.getLong("energyCapacity"), Map.of());
		authority.publish(current.withOwnership(record.withCentrifuge(state))); directory.requestSave(authority); return true;
	}
	/** 保留已有显式探针／调用方的首进程入口；正式调度使用带 lane 的重载。 */
	public CentrifugeLaneAllocator.Candidate candidate(ServerLevel level, UUID member, ProductKey input) {
		return candidate(level, member, 0, input);
	}
	public CentrifugeLaneAllocator.Candidate candidate(ServerLevel level, UUID member, int lane, ProductKey input) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (!enabled() || current.policyRevision() != policy.snapshot().revision() || record == null
				|| record.centrifugeState() == null || lane < 0 || lane >= record.centrifugeState().laneCount()
				|| record.centrifugeState().jobs().containsKey(lane)) return null;
		if (!available(level, record, true)) return null;
		var plan = StaticCentrifugeAdapter.compile(level, policy, input, record.centrifugeState().revision(), profile(level, member));
		return new CentrifugeLaneAllocator.Candidate(member, lane, record.centrifugeState().revision(), plan);
	}
	/** 候选检索按完整能力分组；每次分配仍重新校验实际成员、lane 和权威版本。 */
	public StaticCentrifugeAdapter.Profile profile(ServerLevel level, UUID member) {
		var record = authority.checkpoint().ownedMachines().get(member);
		if (record == null || !available(level, record, true)) throw new IllegalArgumentException("Unavailable centrifuge member");
		return record.combined() == null
				? StaticCentrifugeAdapter.profile(level, ManagedProductionAccess.member(level, authority, directory, record, mekanism.common.tile.base.TileEntityMekanism.class), record)
				: com.ayoshiko.productivebeesgenesis.multiblock.world.MachineUpgradeProfiles.centrifuge(record.combined().upgrades());
	}
	public boolean assign(ServerLevel level, CentrifugeLaneAllocator.Selection selection, long seed, boolean simulate) {
		var current = authority.checkpoint();
		if (selection == null || !selection.matches(current)) return false;
		var offered = selection.candidate(); var fresh = candidate(level, offered.member(), offered.lane(), offered.plan().input());
		if (!offered.equals(fresh) || authority.checkpoint() != current) return false;
		if (simulate) return true;
		var next = CentrifugeLaneAllocator.commit(current, policy, selection, seed);
		if (next == current) return false;
		authority.publish(next); return true;
	}
	public boolean assignReserved(ServerLevel level, CentrifugeLaneAllocator.Candidate offered,
			com.ayoshiko.productivebeesgenesis.apiculture.policy.RuntimeProcessingRules.Claim claim,
			com.ayoshiko.productivebeesgenesis.apiculture.policy.ReservePolicy reserves,
			com.ayoshiko.productivebeesgenesis.apiculture.policy.ReserveAllowanceScan.Permit permit, int limit, long seed) {
		var current = authority.checkpoint();
		if (offered == null || claim == null || !claim.matches(current.scheduler()) || !offered.equals(candidate(level, offered.member(), offered.lane(), offered.plan().input()))
				|| authority.checkpoint() != current) return false;
		if (!com.ayoshiko.productivebeesgenesis.apiculture.compat.RuntimeProcessingMatch.accepts(claim.rule(), offered.plan().input())) return false;
		if (claim.rule() != null && (claim.rule().reserves() != reserves || limit > claim.rule().batchLimit())) return false;
		if (claim.rule() == null && (!current.scheduler().rules().isEmpty() || !reserves.global().equals(com.ayoshiko.productivebeesgenesis.apiculture.policy.ReservePolicy.Layer.NONE)
				|| !reserves.rule().equals(com.ayoshiko.productivebeesgenesis.apiculture.policy.ReservePolicy.Layer.NONE))) return false;
		var state = current.ownedMachines().get(offered.member()).centrifugeState();
		if (!state.networkPowered()) return false;
		var work = CentrifugeWorkTransaction.assignReserved(state, current.ledger(), policy, offered.lane(), offered.plan(), limit, seed,
				state.networkPowered() ? current.energy().stored() : state.energy(), reserves, permit);
		if (work == null) return false;
		var next = current.applyCentrifuge(offered.member(), work, claim); if (next == current) return false;
		authority.publish(next); return true;
	}
	/** 旧配方已付费结果可冻结／结算；推进始终沿用固定计划，只额外检查在线和红石状态。 */
	public boolean work(ServerLevel level, UUID member, long expectedRevision, Action action, int ticks, boolean simulate) {
		return work(level, member, expectedRevision, action, ticks, simulate, 0);
	}
	public boolean work(ServerLevel level, UUID member, long expectedRevision, Action action, int ticks, boolean simulate, long maintenanceFee) {
		return work(level, member, 0, expectedRevision, action, ticks, simulate, maintenanceFee);
	}
	public boolean work(ServerLevel level, UUID member, int lane, long expectedRevision, Action action, int ticks, boolean simulate) {
		return work(level, member, lane, expectedRevision, action, ticks, simulate, 0);
	}
	public boolean work(ServerLevel level, UUID member, int lane, long expectedRevision, Action action, int ticks, boolean simulate, long maintenanceFee) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.centrifugeState() == null || record.centrifugeState().revision() != expectedRevision
				|| lane < 0 || lane >= record.centrifugeState().laneCount()) return false;
		if (!available(level, record, false)) return false;
		var state = record.centrifugeState();
		if (simulate && action == Action.FREEZE) {
			var job = state.jobs().get(lane); return job != null && job.paid() && !job.sampled();
		}
		long tick = level.getServer().getTickCount(), fee = action == Action.ADVANCE ? maintenanceFee : 0;
		long due = authority.maintenanceDue(tick, fee);
		if (due > 0 && (!state.networkPowered() || current.energy().stored() < due)) return false;
		var transaction = switch (action) {
			case ADVANCE -> CentrifugeWorkTransaction.advance(state, current.ledger(), lane, ticks, true, available(level, record, true) && ModConfig.SERVER.beeNetwork.enabled.get(), state.networkPowered() ? current.energy().stored() - due : state.energy());
			case FREEZE -> CentrifugeWorkTransaction.freeze(state, current.ledger(), lane);
			case SETTLE -> CentrifugeWorkTransaction.settle(state, current.ledger(), lane, current.policyRevision());
			case CANCEL -> CentrifugeWorkTransaction.cancel(state, current.ledger(), lane, current.policyRevision());
		};
		if (transaction == null) return false;
		if (simulate) return true;
		var next = current.applyCentrifuge(member, transaction);
		if (next == current) return false;
		authority.publishMaintainedWork(current, next, tick, fee); return true;
	}
	private boolean available(ServerLevel level, com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord record, boolean running) {
		if (record.combined() != null) return com.ayoshiko.productivebeesgenesis.multiblock.world.MachineNetworkAccess.member(level, authority, directory, record) != null;
		var tile = ManagedProductionAccess.member(level, authority, directory, record, mekanism.common.tile.base.TileEntityMekanism.class);
		return tile != null && (!running || tile.canFunction());
	}
	private boolean enabled() { return ModConfig.SERVER.beeNetwork.enabled.get() && recipeEpoch == ProductiveBeesGenesis.RECIPE_VERSION.get(); }
}
