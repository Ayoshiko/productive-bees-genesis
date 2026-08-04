package com.ayoshiko.productivebeesgenesis.mek;

/** Shared probability math for Productive Bees centrifuge outputs. */
final class PbOutputChance {

	private PbOutputChance() {
	}

	static double stabilityBonus(int installedCount, double chanceIncrease) {
		if (installedCount < 0 || !Double.isFinite(chanceIncrease) || chanceIncrease <= 0.0D) {
			return 0.0D;
		}
		return Math.min(1.0D, (installedCount + 1.0D) * chanceIncrease);
	}

	static double adjustedChance(float recipeChance, double stabilityBonus) {
		if (Float.isNaN(recipeChance)) {
			return 0.0D;
		}
		return Math.max(0.0D, Math.min(1.0D, recipeChance + stabilityBonus));
	}
}
