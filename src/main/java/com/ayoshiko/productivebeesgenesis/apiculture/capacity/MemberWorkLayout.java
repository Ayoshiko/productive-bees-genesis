package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.Objects;

/** 蜂位和离心进程互不借用；存档总索引保持旧单类型布局，任务使用类型和局部槽号。 */
public record MemberWorkLayout(int beeSlots, int centrifugeLanes) {
	public static final MemberWorkLayout EMPTY = new MemberWorkLayout(0, 0);

	public record Slot(WorkKey.Kind kind, int index) {
		public Slot {
			Objects.requireNonNull(kind);
			if (index < 0) throw new IllegalArgumentException("Negative work slot");
		}
	}

	public MemberWorkLayout {
		if (beeSlots < 0 || centrifugeLanes < 0 || (long) beeSlots + centrifugeLanes > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Invalid member work layout");
	}

	public static MemberWorkLayout fromTotal(int beeSlots, int totalLanes) {
		if (beeSlots < 0 || totalLanes < beeSlots) throw new IllegalArgumentException("Invalid total work lanes");
		return new MemberWorkLayout(beeSlots, totalLanes - beeSlots);
	}

	public int totalLanes() { return beeSlots + centrifugeLanes; }
	public int lanes(WorkKey.Kind kind) {
		return switch (kind) {
			case BEE_CYCLE -> beeSlots;
			case CENTRIFUGE_RECIPE -> centrifugeLanes;
		};
	}
	public boolean contains(Slot slot) { return slot.index() < lanes(slot.kind()); }
	public Slot slot(int ordinal) {
		if (ordinal < 0 || ordinal >= totalLanes()) throw new IllegalArgumentException("Work ordinal out of range");
		return ordinal < beeSlots ? new Slot(WorkKey.Kind.BEE_CYCLE, ordinal)
				: new Slot(WorkKey.Kind.CENTRIFUGE_RECIPE, ordinal - beeSlots);
	}
	public int ordinal(Slot slot) {
		if (!contains(slot)) throw new IllegalArgumentException("Work slot out of range");
		return slot.index() + (slot.kind() == WorkKey.Kind.BEE_CYCLE ? 0 : beeSlots);
	}
	public boolean containsOrdinal(WorkKey.Kind kind, int ordinal) {
		return ordinal >= 0 && ordinal < totalLanes() && slot(ordinal).kind() == kind;
	}
}
