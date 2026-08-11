package com.ayoshiko.productivebeesgenesis.mek;

/** Pure formulas that keep Mekanism Extras creative and stack effects separate. */
final class MekExtrasUpgradeSemantics {

	private MekExtrasUpgradeSemantics() {
	}

	static int processingTicks(boolean creativeInstalled, int baseTime, double timeMultiplier) {
		return creativeInstalled ? 0 : Math.max(1, (int) Math.floor(baseTime * timeMultiplier));
	}

	static long energyPerTick(boolean creativeInstalled, long normalEnergyPerTick) {
		return creativeInstalled ? 0L : Math.max(0L, normalEnergyPerTick);
	}

	static int operationsPerTick(boolean creativeInstalled, int stackOperations,
			int speedAdjustedOperations) {
		return creativeInstalled ? Math.max(1, stackOperations) : Math.max(1, speedAdjustedOperations);
	}
}
