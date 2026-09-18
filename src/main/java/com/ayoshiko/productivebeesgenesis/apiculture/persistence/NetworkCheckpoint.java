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
public record NetworkCheckpoint(NetworkIdentity identity, long revision, long policyRevision,
		LedgerCheckpoint ledger, List<TransferStaging.View> transfers, Set<ProductPolicyRegistry.Discovery> discoveries,
		List<MemberCapabilitySnapshot> members, List<VirtualLaneState> lanes, SchedulerCheckpoint scheduler) {
	public NetworkCheckpoint {
		Objects.requireNonNull(identity); Objects.requireNonNull(ledger); Objects.requireNonNull(scheduler);
		if (revision < 0 || policyRevision < 0) throw new IllegalArgumentException("Negative checkpoint revision");
		transfers = List.copyOf(transfers); discoveries = Set.copyOf(discoveries); members = List.copyOf(members); lanes = List.copyOf(lanes);
		Map<UUID, MemberCapabilitySnapshot> owners = new ConcurrentHashMap<>();
		var positions = ConcurrentHashMap.newKeySet();
		for (var member : members) {
			if (owners.putIfAbsent(member.memberId(), member) != null || !positions.add(member.origin())
					|| !member.origin().dimension().equals(identity.origin().dimension())) throw new IllegalArgumentException("Conflicting member identity or position");
		}
		var laneIds = ConcurrentHashMap.<String>newKeySet();
		for (var lane : lanes) {
			var member = owners.get(lane.memberId());
			if (member == null || lane.laneIndex() >= member.laneCount() || lane.capabilityRevision() > member.revision()
					|| !laneIds.add(lane.memberId() + ":" + lane.laneIndex())) throw new IllegalArgumentException("Orphaned or duplicate lane");
			if (lane.capabilityRevision() == member.revision() && !member.requireCapacity(lane.capability().work()).equals(lane.capability())) {
				throw new IllegalArgumentException("Lane differs from its capability revision");
			}
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
	public static NetworkCheckpoint empty(NetworkIdentity identity) {
		return new NetworkCheckpoint(identity, 0, 0, new LedgerCheckpoint(0, Map.of(), List.of()), List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
	}
}
