package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeProductionSampling;

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
		return CentrifugeProductionSampling.adjustedChance(recipeChance, stabilityBonus);
	}
}
