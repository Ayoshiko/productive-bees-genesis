package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.Map;
import java.util.Objects;

/** 一个进程对指定作业的满载能力；多个配方能力是替代用途，不是额外进程。 */
public record WorkCapacity(WorkKey work, int operationsPerCycle, int cycleTicks,
		long energyPerOperation, long fullLaneEnergyPerTick, double outputMultiplier, double stabilityBonus,
		Map<String, Integer> effects) {
	public WorkCapacity {
		Objects.requireNonNull(work);
		if (operationsPerCycle <= 0 || cycleTicks <= 0 || energyPerOperation < 0 || fullLaneEnergyPerTick < 0
				|| !Double.isFinite(outputMultiplier) || outputMultiplier <= 0
				|| !Double.isFinite(stabilityBonus) || stabilityBonus < 0 || stabilityBonus > 1) {
			throw new IllegalArgumentException("Invalid lane capability");
		}
		if (!(effects instanceof Effects)) {
			effects = Map.copyOf(effects);
			if (effects.values().stream().anyMatch(count -> count < 0)) throw new IllegalArgumentException("Negative upgrade count");
		}
	}
	/** 只允许逐项验证后签发的效果根绕过重复扫描。 */
	public static final class EffectsBuilder {
		private final com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<String, Integer> values =
				new com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<>(String::compareTo);
		public void add(String key, int value) {
			if (value < 0 || values.get(key) != null) throw new IllegalArgumentException("Duplicate or negative upgrade count");
			values.put(key, value);
		}
		public Map<String, Integer> finish() { return new Effects(values.snapshot()); }
	}
	private static final class Effects extends java.util.AbstractMap<String, Integer> {
		private final Map<String, Integer> values;
		Effects(Map<String, Integer> values) { this.values = values; }
		@Override public java.util.Set<Entry<String, Integer>> entrySet() { return values.entrySet(); }
		@Override public Integer get(Object key) { return values.get(key); }
		@Override public int size() { return values.size(); }
	}

	public ExactRate ratePerLane() { return ExactRate.of(operationsPerCycle, cycleTicks); }
}
