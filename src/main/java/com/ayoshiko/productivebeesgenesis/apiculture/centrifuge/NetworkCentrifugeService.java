package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkDirectory;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 显式服务端开发入口，D16 前不注册 ticker；身份验证与领域候选提交分离。 */
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
				|| record == null || record.centrifuge() != null) return false;
		var tile = ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class);
		if (tile == null) return false;
		StaticCentrifugeAdapter.compile(level, tile, record, policy, input, 0);
		if (authority.checkpoint() != current) return false;
		var assets = record.assets().copy();
		var state = new CentrifugeWorkState(member, 0, 1, assets.getLong("energy"), assets.getLong("energyCapacity"), Map.of());
		authority.publish(current.withOwnership(record.withCentrifuge(state))); directory.requestSave(authority); return true;
	}
	public CentrifugeLaneAllocator.Candidate candidate(ServerLevel level, UUID member, ProductKey input) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (!enabled() || current.policyRevision() != policy.snapshot().revision() || record == null || record.centrifuge() == null) return null;
		var tile = ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class);
		if (tile == null || !tile.canFunction() || !record.centrifuge().drained()) return null;
		var plan = StaticCentrifugeAdapter.compile(level, tile, record, policy, input, 0);
		return new CentrifugeLaneAllocator.Candidate(member, 0, record.centrifuge().revision(), plan);
	}
	public boolean assign(ServerLevel level, CentrifugeLaneAllocator.Selection selection, long seed, boolean simulate) {
		var current = authority.checkpoint();
		if (selection == null || !selection.matches(current)) return false;
		var offered = selection.candidate(); var fresh = candidate(level, offered.member(), offered.plan().input());
		if (!offered.equals(fresh) || authority.checkpoint() != current) return false;
		if (simulate) return true;
		var next = CentrifugeLaneAllocator.commit(current, policy, selection, seed);
		if (next == current) return false;
		authority.publish(next); directory.requestSave(authority); return true;
	}
	/** 旧配方已付费结果可冻结／结算；推进始终沿用固定计划，只额外检查在线和红石状态。 */
	public boolean work(ServerLevel level, UUID member, long expectedRevision, Action action, int ticks, boolean simulate) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.centrifuge() == null || record.centrifuge().revision() != expectedRevision) return false;
		var tile = ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class);
		if (tile == null) return false;
		var state = record.centrifuge();
		if (simulate && action == Action.FREEZE) {
			var job = state.jobs().get(0); return job != null && job.paid() && !job.sampled();
		}
		var transaction = switch (action) {
			case ADVANCE -> CentrifugeWorkTransaction.advance(state, current.ledger(), 0, ticks, true, tile.canFunction() && ModConfig.SERVER.beeNetwork.enabled.get(), state.networkPowered() ? current.energy().stored() : state.energy());
			case FREEZE -> CentrifugeWorkTransaction.freeze(state, current.ledger(), 0);
			case SETTLE -> CentrifugeWorkTransaction.settle(state, current.ledger(), 0, current.policyRevision());
			case CANCEL -> CentrifugeWorkTransaction.cancel(state, current.ledger(), 0, current.policyRevision());
		};
		if (transaction == null) return false;
		if (simulate) return true;
		var next = current.applyCentrifuge(member, transaction);
		if (next == current) return false;
		authority.publish(next); directory.requestSave(authority); return true;
	}
	private boolean enabled() { return ModConfig.SERVER.beeNetwork.enabled.get() && recipeEpoch == ProductiveBeesGenesis.RECIPE_VERSION.get(); }
}
