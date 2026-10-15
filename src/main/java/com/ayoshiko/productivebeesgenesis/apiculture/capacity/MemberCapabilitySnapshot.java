package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 不持有世界或可变升级；laneCount 为两类工作位总数，蜂位占前段，离心占后段。 */
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
		var layout = MemberWorkLayout.fromTotal(beeSlots, laneCount);
		if (revision < 0 || machineId == null || machineId.isBlank() || laneCount <= 0)
			throw new IllegalArgumentException("Invalid member capability");
		if (alternatives instanceof Alternatives checked) {
			for (var kind : checked.kinds)
				if (layout.lanes(kind) == 0) throw new IllegalArgumentException("Work kind has no member lanes");
		} else {
			alternatives = List.copyOf(alternatives);
			if (alternatives.stream().map(WorkCapacity::work).distinct().count() != alternatives.size())
				throw new IllegalArgumentException("Duplicate work capability");
			for (WorkCapacity capacity : alternatives)
				if (layout.lanes(capacity.work().kind()) == 0) throw new IllegalArgumentException("Work kind has no member lanes");
		}
	}

	public MemberWorkLayout layout() { return MemberWorkLayout.fromTotal(beeSlots, laneCount); }
	public int feedingSlots() { return beeSlots; }
	public boolean online() { return availability == Availability.ONLINE; }

	public WorkCapacity requireCapacity(WorkKey work) {
		if (alternatives instanceof Alternatives checked) {
			var value = checked.byWork.get(work);
			if (value == null) throw new IllegalArgumentException("Member cannot perform this work");
			return value;
		}
		return alternatives.stream().filter(capacity -> capacity.work().equals(work)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Member cannot perform this work"));
	}
	public static final class AlternativesBuilder {
		private final com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<Integer, WorkCapacity> values =
				new com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<>(Integer::compare);
		private final com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<WorkKey, WorkCapacity> byWork =
				new com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<>(java.util.Comparator.comparing(WorkKey::kind)
						.thenComparing(WorkKey::id).thenComparingLong(WorkKey::recipeRevision).thenComparing(WorkKey::contextKey));
		private final java.util.EnumSet<WorkKey.Kind> kinds = java.util.EnumSet.noneOf(WorkKey.Kind.class);
		public void add(WorkCapacity value) {
			if (byWork.get(value.work()) != null) throw new IllegalArgumentException("Duplicate work capability");
			kinds.add(value.work().kind()); byWork.put(value.work(), value); values.put(values.size(), value);
		}
		public List<WorkCapacity> finish() { return new Alternatives(values.valuesSnapshot(), byWork.snapshot(), java.util.Set.copyOf(kinds)); }
	}
	private static final class Alternatives extends java.util.AbstractList<WorkCapacity> {
		private final List<WorkCapacity> values;
		private final java.util.Map<WorkKey, WorkCapacity> byWork;
		private final java.util.Set<WorkKey.Kind> kinds;
		Alternatives(List<WorkCapacity> values, java.util.Map<WorkKey, WorkCapacity> byWork, java.util.Set<WorkKey.Kind> kinds) {
			this.values = values; this.byWork = byWork; this.kinds = kinds;
		}
		@Override public WorkCapacity get(int index) { return values.get(index); }
		@Override public int size() { return values.size(); }
		@Override public java.util.Iterator<WorkCapacity> iterator() { return values.iterator(); }
	}
}
