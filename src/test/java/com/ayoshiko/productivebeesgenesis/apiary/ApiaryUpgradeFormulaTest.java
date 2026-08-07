package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApiaryUpgradeFormulaTest {

	@Test
	void fullMekSpeedAndTimeUpgradesMatchLegacyTwentySixTicks() {
		float mekMultiplier = ApiaryUpgradeHandler.computeMekSpeedTimeMultiplier(32, 32, 10.0f);
		int effectiveTimeUpgrades = 8 + 8 * 2;
		float combinedMultiplier = mekMultiplier / (1.0f + 0.15f * effectiveTimeUpgrades);

		assertEquals(0.1f, mekMultiplier, 0.000_001f);
		assertEquals(26, Math.round(1_200 * combinedMultiplier));
	}
}
