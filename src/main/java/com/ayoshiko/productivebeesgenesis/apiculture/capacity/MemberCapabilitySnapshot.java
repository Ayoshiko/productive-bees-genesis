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
		if (alternatives instanceof Alternatives checked) {
			if (!checked.isEmpty() && checked.beeWork != (beeSlots > 0)) throw new IllegalArgumentException("Work kind differs from member kind");
		} else {
			alternatives = List.copyOf(alternatives);
			if (alternatives.stream().map(WorkCapacity::work).distinct().count() != alternatives.size()) throw new IllegalArgumentException("Duplicate work capability");
			for (WorkCapacity capacity : alternatives) {
				if ((capacity.work().kind() == WorkKey.Kind.BEE_CYCLE) != (beeSlots > 0)) throw new IllegalArgumentException("Work kind differs from member kind");
			}
		}
	}

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
		private boolean beeWork;
		public void add(WorkCapacity value) {
			boolean bee = value.work().kind() == WorkKey.Kind.BEE_CYCLE;
			if (byWork.get(value.work()) != null || !values.isEmpty() && bee != beeWork) throw new IllegalArgumentException("Duplicate or mixed work capability");
			beeWork = bee; byWork.put(value.work(), value); values.put(values.size(), value);
		}
		public List<WorkCapacity> finish() { return new Alternatives(values.valuesSnapshot(), byWork.snapshot(), beeWork); }
	}
	private static final class Alternatives extends java.util.AbstractList<WorkCapacity> {
		private final List<WorkCapacity> values;
		private final java.util.Map<WorkKey, WorkCapacity> byWork;
		private final boolean beeWork;
		Alternatives(List<WorkCapacity> values, java.util.Map<WorkKey, WorkCapacity> byWork, boolean beeWork) {
			this.values = values; this.byWork = byWork; this.beeWork = beeWork;
		}
		@Override public WorkCapacity get(int index) { return values.get(index); }
		@Override public int size() { return values.size(); }
		@Override public java.util.Iterator<WorkCapacity> iterator() { return values.iterator(); }
	}
}
