package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PbOutputChanceTest {

	@Test
	void sevenDefaultStabilityUpgradesGuaranteeZeroChanceOutput() {
		double bonus = PbOutputChance.stabilityBonus(7, 0.15D);

		assertEquals(1.0D, bonus);
		assertEquals(1.0D, PbOutputChance.adjustedChance(0.0F, bonus));
	}

	@Test
	void stabilityUsesConfiguredChanceIncrease() {
		double bonus = PbOutputChance.stabilityBonus(2, 0.2D);

		assertEquals(0.6D, bonus, 1.0E-12D);
		assertEquals(0.85D, PbOutputChance.adjustedChance(0.25F, bonus), 1.0E-12D);
	}

	@Test
	void adjustedChanceIsClamped() {
		assertEquals(1.0D, PbOutputChance.adjustedChance(0.9F, 0.3D));
		assertEquals(0.0D, PbOutputChance.adjustedChance(-0.5F, 0.1D));
	}
}
