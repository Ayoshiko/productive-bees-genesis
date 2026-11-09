package com.ayoshiko.productivebeesgenesis.mek;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MekUpgradeSupportTest {

	@Test
	void vanillaFractionalTicksRetainTheirTimeMultiplier() {
		assertEquals(0.1f, MekUpgradeSupport.speedTimeMultiplier(100.0D, 1_000), 0.000_001f);
		assertEquals(0.000_1f, MekUpgradeSupport.speedTimeMultiplier(0.1D, 1_000), 0.000_000_01f);
	}

	@Test
	void unleashedOperationEncodingRestoresTimeInsteadOfDiscardingTheUpgrade() {
		assertEquals(0.000_1f, MekUpgradeSupport.speedTimeMultiplier(-10.0D, 1_000), 0.000_000_01f);
		float maximum = MekUpgradeSupport.speedTimeMultiplier(-Integer.MAX_VALUE, Integer.MAX_VALUE);
		assertTrue(maximum > 0.0f);
		assertTrue(maximum < 0.000_000_001f);
	}

	@Test
	void invalidOrSlowerTicksDoNotCreateInvalidProductionDurations() {
		for (double ticks : new double[]{0.0D, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertEquals(1.0f, MekUpgradeSupport.speedTimeMultiplier(ticks, 1_000));
		}
		assertEquals(1.0f, MekUpgradeSupport.speedTimeMultiplier(2_000.0D, 1_000));
		assertEquals(1.0f, MekUpgradeSupport.speedTimeMultiplier(1.0D, 0));
	}
}
