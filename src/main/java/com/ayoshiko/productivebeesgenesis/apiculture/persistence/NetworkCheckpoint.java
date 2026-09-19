package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 同一服务端边界捕获的 P1 权威状态；加载后成员资格仍须由拓扑重新验证。 */
public final class NetworkCheckpoint {
	private final NetworkIdentity identity;
	private final long revision;
	private final long policyRevision;
	private final LedgerCheckpoint ledger;
	private final List<TransferStaging.View> transfers;
	private final Set<ProductPolicyRegistry.Discovery> discoveries;
	private final List<MemberCapabilitySnapshot> members;
	private final List<VirtualLaneState> lanes;
	private final SchedulerCheckpoint scheduler;
	public NetworkCheckpoint(NetworkIdentity identity, long revision, long policyRevision,
			LedgerCheckpoint ledger, List<TransferStaging.View> transfers, Set<ProductPolicyRegistry.Discovery> discoveries,
			List<MemberCapabilitySnapshot> members, List<VirtualLaneState> lanes, SchedulerCheckpoint scheduler) {
		this(identity, revision, policyRevision, ledger, List.copyOf(transfers), Set.copyOf(discoveries),
				List.copyOf(members), List.copyOf(lanes), scheduler, false);
	}
	private NetworkCheckpoint(NetworkIdentity identity, long revision, long policyRevision,
			LedgerCheckpoint ledger, List<TransferStaging.View> transfers, Set<ProductPolicyRegistry.Discovery> discoveries,
			List<MemberCapabilitySnapshot> members, List<VirtualLaneState> lanes, SchedulerCheckpoint scheduler, boolean captured) {
		this.identity = Objects.requireNonNull(identity); this.ledger = Objects.requireNonNull(ledger);
		this.scheduler = Objects.requireNonNull(scheduler); this.revision = revision; this.policyRevision = policyRevision;
		this.transfers = transfers; this.discoveries = discoveries; this.members = members; this.lanes = lanes;
		if (revision < 0 || policyRevision < 0) throw new IllegalArgumentException("Negative checkpoint revision");
		if (!captured) validate();
	}
	private void validate() {
		Map<UUID, MemberCapabilitySnapshot> owners = new ConcurrentHashMap<>();
		var positions = ConcurrentHashMap.newKeySet();
		for (var member : members) {
			if (owners.putIfAbsent(member.memberId(), member) != null || !positions.add(member.origin())
					|| !member.origin().dimension().equals(identity.origin().dimension())) throw new IllegalArgumentException("Conflicting member identity or position");
		}
		var laneIds = ConcurrentHashMap.<String>newKeySet();
		for (var lane : lanes) {
			var member = owners.get(lane.memberId());
			NetworkMemberRecords.validateLane(member, lane);
			if (!laneIds.add(lane.memberId() + ":" + lane.laneIndex())) throw new IllegalArgumentException("Duplicate lane");
		}
		for (var transaction : ledger.transactions()) {
			if (transaction.policyRevision() > policyRevision) throw new IllegalArgumentException("Transaction refers to a future policy");
		}
		var transfersIds = ConcurrentHashMap.<UUID>newKeySet();
		for (var transfer : transfers) {
			if (!transfersIds.add(transfer.id()) || transfer.phase() == TransferStaging.Phase.COMPLETE
					|| transfer.phase() == TransferStaging.Phase.HELD && transfer.held().isZero()) throw new IllegalArgumentException("Invalid pending transfer identity or state");
		}
	}
	/** 只有绑定在同一线程与账本上的来源可跳过再次整域校验。 */
	static NetworkCheckpoint capture(NetworkCheckpointSource source, long revision) {
		return new NetworkCheckpoint(source.identity(), revision, source.policyRevision(), source.ledger(), source.transfers(),
				source.discoveries(), source.members(), source.lanes(), source.scheduler(), true);
	}
	public NetworkIdentity identity() { return identity; }
	public long revision() { return revision; }
	public long policyRevision() { return policyRevision; }
	public LedgerCheckpoint ledger() { return ledger; }
	public List<TransferStaging.View> transfers() { return transfers; }
	public Set<ProductPolicyRegistry.Discovery> discoveries() { return discoveries; }
	public List<MemberCapabilitySnapshot> members() { return members; }
	public List<VirtualLaneState> lanes() { return lanes; }
	public SchedulerCheckpoint scheduler() { return scheduler; }
	@Override public boolean equals(Object other) {
		return this == other || other instanceof NetworkCheckpoint checkpoint && revision == checkpoint.revision
				&& policyRevision == checkpoint.policyRevision && identity.equals(checkpoint.identity) && ledger.equals(checkpoint.ledger)
				&& transfers.equals(checkpoint.transfers) && discoveries.equals(checkpoint.discoveries) && members.equals(checkpoint.members)
				&& lanes.equals(checkpoint.lanes) && scheduler.equals(checkpoint.scheduler);
	}
	@Override public int hashCode() { return Objects.hash(identity, revision, policyRevision, ledger, transfers, discoveries, members, lanes, scheduler); }
	@Override public String toString() {
		return "NetworkCheckpoint[network=" + identity.networkId() + ", revision=" + revision + ", policy=" + policyRevision + ", " + ledger + "]";
	}
	public static NetworkCheckpoint empty(NetworkIdentity identity) {
		return new NetworkCheckpoint(identity, 0, 0, new LedgerCheckpoint(0, Map.of(), List.of()), List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
	}
}
