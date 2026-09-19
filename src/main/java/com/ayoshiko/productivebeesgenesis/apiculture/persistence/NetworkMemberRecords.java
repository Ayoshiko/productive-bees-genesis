package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅维护已交付能力记录的交叉约束；不判断真实拓扑或授予机器执行所有权。 */
final class NetworkMemberRecords {
	private record LaneId(UUID member, int index) { }
	private final String dimension;
	private final SnapshotRecords<UUID, MemberCapabilitySnapshot> members = new SnapshotRecords<>(Comparator.naturalOrder());
	private final SnapshotRecords<LaneId, VirtualLaneState> lanes = new SnapshotRecords<>(
			Comparator.comparing(LaneId::member).thenComparingInt(LaneId::index));
	private final Map<MemberCapabilitySnapshot.Origin, UUID> positions = new ConcurrentHashMap<>();
	private final Map<UUID, Map<Integer, VirtualLaneState>> byMember = new ConcurrentHashMap<>();
	NetworkMemberRecords(String dimension) { this.dimension = dimension; }
	void putMember(MemberCapabilitySnapshot member) {
		Objects.requireNonNull(member);
		var previous = members.get(member.memberId());
		if (previous != null && (member.revision() < previous.revision() || member.revision() == previous.revision()
				&& (member.beeSlots() != previous.beeSlots() || member.laneCount() != previous.laneCount()
						|| !member.machineId().equals(previous.machineId()) || !member.alternatives().equals(previous.alternatives())))) {
			throw new IllegalArgumentException("Capability changes require a newer member revision");
		}
		var occupying = positions.get(member.origin());
		if (!dimension.equals(member.origin().dimension()) || occupying != null && !occupying.equals(member.memberId())) {
			throw new IllegalArgumentException("Conflicting member dimension or position");
		}
		// 能力变更只核对该成员仍在制的通道，不能把校验延迟到下一次保存。
		for (var lane : byMember.getOrDefault(member.memberId(), Map.of()).values()) validateLane(member, lane);
		if (previous != null) positions.remove(previous.origin());
		members.put(member.memberId(), member); positions.put(member.origin(), member.memberId());
	}
	void removeMember(UUID id) {
		Objects.requireNonNull(id);
		if (byMember.containsKey(id)) throw new IllegalStateException("Resolve active lanes before removing their member");
		var previous = members.get(id);
		if (previous != null) { positions.remove(previous.origin()); members.remove(id); }
	}
	void putLane(VirtualLaneState lane) {
		Objects.requireNonNull(lane); validateLane(members.get(lane.memberId()), lane);
		lanes.put(new LaneId(lane.memberId(), lane.laneIndex()), lane);
		byMember.computeIfAbsent(lane.memberId(), ignored -> new ConcurrentHashMap<>()).put(lane.laneIndex(), lane);
	}
	void removeLane(UUID member, int index) {
		if (index < 0) throw new IllegalArgumentException("Negative lane index");
		lanes.remove(new LaneId(Objects.requireNonNull(member), index));
		var owned = byMember.get(member);
		if (owned != null) { owned.remove(index); if (owned.isEmpty()) byMember.remove(member); }
	}
	static void validateLane(MemberCapabilitySnapshot member, VirtualLaneState lane) {
		if (member == null || lane.laneIndex() >= member.laneCount() || lane.capabilityRevision() > member.revision()) {
			throw new IllegalArgumentException("Orphaned lane or future capability");
		}
		if (lane.capabilityRevision() == member.revision() && !member.requireCapacity(lane.capability().work()).equals(lane.capability())) {
			throw new IllegalArgumentException("Lane differs from its capability revision");
		}
	}
	List<MemberCapabilitySnapshot> members() { return members.valuesSnapshot(); }
	List<VirtualLaneState> lanes() { return lanes.valuesSnapshot(); }
}
