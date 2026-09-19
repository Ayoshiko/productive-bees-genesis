package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 私有候选域：按步验证引用，只有全部检查通过才签发不可变 checkpoint。 */
final class NetworkRestoreState {
	final LedgerCheckpoint.RestoreBuilder ledger = new LedgerCheckpoint.RestoreBuilder();
	final SnapshotRecords<Integer, LedgerCheckpoint.Pending> transactions = new SnapshotRecords<>(Integer::compare);
	final SnapshotRecords<Integer, TransferStaging.View> transfers = new SnapshotRecords<>(Integer::compare);
	final SnapshotRecords<ProductPolicyRegistry.Discovery, Boolean> discoveries = new SnapshotRecords<>(
			Comparator.comparing(ProductPolicyRegistry.Discovery::adapterId).thenComparing(value -> value.key().orderingKey()));
	final SnapshotRecords<Integer, MemberCapabilitySnapshot> members = new SnapshotRecords<>(Integer::compare);
	final SnapshotRecords<Integer, VirtualLaneState> lanes = new SnapshotRecords<>(Integer::compare);
	private final Map<UUID, MemberCapabilitySnapshot> byMember = new ConcurrentHashMap<>();
	private final Set<MemberCapabilitySnapshot.Origin> positions = ConcurrentHashMap.newKeySet();
	private final Set<UUID> transferIds = ConcurrentHashMap.newKeySet();
	private record LaneId(UUID member, int index) { }
	private final Set<LaneId> laneIds = ConcurrentHashMap.newKeySet();
	NetworkIdentity identity;
	long revision, policyRevision, ledgerRevision;
	CheckpointSchema.SchedulerState scheduler;
	private int phase;
	private Iterator<?> checks;
	private boolean complete;
	final com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines.Builder owned = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines.Builder();
	com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines ownedSnapshot;
	void add(String list, Object value) {
		switch (list) {
			case "ownership" -> owned.add((com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord) value);
			case "balances" -> { var entry = (CheckpointSchema.AmountEntry) value; ledger.balance(entry.key(), entry.amount()); }
			case "transactions" -> { var pending = (LedgerCheckpoint.Pending) value; ledger.transaction(pending); transactions.put(transactions.size(), pending); }
			case "transfers" -> {
				var transfer = (TransferStaging.View) value;
				if (!transferIds.add(transfer.id()) || transfer.phase() == TransferStaging.Phase.COMPLETE
						|| transfer.phase() == TransferStaging.Phase.HELD && transfer.held().isZero()) throw new IllegalArgumentException("Invalid pending transfer");
				transfers.put(transfers.size(), transfer);
			}
			case "discoveries" -> {
				var discovery = (ProductPolicyRegistry.Discovery) value;
				if (discoveries.get(discovery) != null) throw new IllegalArgumentException("Duplicate discovery");
				discoveries.put(discovery, true);
			}
			case "members" -> {
				var member = (MemberCapabilitySnapshot) value;
				if (byMember.putIfAbsent(member.memberId(), member) != null || !positions.add(member.origin())) throw new IllegalArgumentException("Duplicate member or position");
				members.put(members.size(), member);
			}
			case "lanes" -> {
				var lane = (VirtualLaneState) value;
				if (!laneIds.add(new LaneId(lane.memberId(), lane.laneIndex()))) throw new IllegalArgumentException("Duplicate lane");
				lanes.put(lanes.size(), lane);
			}
			default -> throw new IllegalArgumentException("Unknown domain list " + list);
		}
	}
	boolean validateStep() {
		if (complete) return true;
		switch (phase) {
			case 0 -> { if (ledger.validateStep()) { phase++; checks = transactions.valuesSnapshot().iterator(); } }
			case 1 -> {
				if (checks.hasNext()) {
					if (((LedgerCheckpoint.Pending) checks.next()).policyRevision() > policyRevision) throw new IllegalArgumentException("Future transaction policy");
				} else { phase++; checks = members.valuesSnapshot().iterator(); }
			}
			case 2 -> {
				if (checks.hasNext()) {
					if (!((MemberCapabilitySnapshot) checks.next()).origin().dimension().equals(identity.origin().dimension())) throw new IllegalArgumentException("Member dimension mismatch");
				} else { phase++; checks = lanes.valuesSnapshot().iterator(); }
			}
			case 3 -> {
				if (checks.hasNext()) { var lane = (VirtualLaneState) checks.next(); NetworkMemberRecords.validateLane(byMember.get(lane.memberId()), lane); }
				else { phase++; checks = null; }
			}
			case 4 -> { if (scheduler.builder().validateStep()) { ownedSnapshot = owned.finish(); checks = ownedSnapshot.values().iterator(); phase++; } }
			case 5 -> {
				if (checks.hasNext()) {
					var record = (com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord) checks.next();
					if (!record.claim().network().equals(identity.networkId()) || !record.claim().origin().dimension().equals(identity.origin().dimension())) throw new IllegalArgumentException("Foreign ownership");
				} else complete = true;
			}
			default -> throw new IllegalStateException("Invalid validation phase");
		}
		return complete;
	}
	NetworkCheckpoint finish() {
		if (!complete) throw new IllegalStateException("Incomplete domain validation");
		return NetworkCheckpoint.restore(this);
	}
	SchedulerCheckpoint schedulerCheckpoint() { return scheduler.builder().finish(scheduler.mode(), scheduler.cursor(), scheduler.used()); }
}
