package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 不持有世界、机器或可变升级对象；离线快照保留槽位所有权但不贡献在线能力。 */
public record MemberCapabilitySnapshot(UUID memberId, long revision, String machineId,
		Origin origin, Availability availability, int beeSlots, int laneCount,
		List<WorkCapacity> alternatives) {
	public enum Availability { ONLINE, OFFLINE, REDSTONE_PAUSED }
	public record Origin(String dimension, int x, int y, int z) {
		public Origin {
			if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing dimension");
		}
	}

	public MemberCapabilitySnapshot {
		Objects.requireNonNull(memberId);
		Objects.requireNonNull(origin);
		Objects.requireNonNull(availability);
		if (revision < 0 || machineId == null || machineId.isBlank() || beeSlots < 0 || laneCount <= 0
				|| (beeSlots > 0 && laneCount != beeSlots)) throw new IllegalArgumentException("Invalid member capability");
		alternatives = List.copyOf(alternatives);
		if (alternatives.stream().map(WorkCapacity::work).distinct().count() != alternatives.size()) {
			throw new IllegalArgumentException("Duplicate work capability");
		}
		for (WorkCapacity capacity : alternatives) {
			boolean beeWork = capacity.work().kind() == WorkKey.Kind.BEE_CYCLE;
			if (beeWork != (beeSlots > 0)) throw new IllegalArgumentException("Work kind differs from member kind");
		}
	}

	public int feedingSlots() { return beeSlots; }
	public boolean online() { return availability == Availability.ONLINE; }

	public WorkCapacity requireCapacity(WorkKey work) {
		return alternatives.stream().filter(capacity -> capacity.work().equals(work)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Member cannot perform this work"));
	}
}
