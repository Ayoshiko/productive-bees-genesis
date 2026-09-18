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
		effects = Map.copyOf(effects);
		if (effects.values().stream().anyMatch(count -> count < 0)) throw new IllegalArgumentException("Negative upgrade count");
	}

	public ExactRate ratePerLane() { return ExactRate.of(operationsPerCycle, cycleTicks); }
}
