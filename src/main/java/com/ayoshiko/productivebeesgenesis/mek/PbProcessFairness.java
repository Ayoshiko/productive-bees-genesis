package com.ayoshiko.productivebeesgenesis.mek;

/** Pure fairness math for sharing the factory energy buffer between active lanes. */
final class PbProcessFairness {

	private PbProcessFairness() {
	}

	static long energyBudget(long availableEnergy, int remainingLanes) {
		if (availableEnergy <= 0L || remainingLanes <= 0) return 0L;
		long quotient = availableEnergy / remainingLanes;
		return availableEnergy % remainingLanes == 0L ? quotient : quotient + 1L;
	}
}
