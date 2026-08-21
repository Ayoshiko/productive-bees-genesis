package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2EnergyMathTest {

	@Test
	void clampsTwoSourcesToRemainingCapacityWithoutOverflow() {
		Ae2EnergyMath.InjectionResult result = Ae2EnergyMath.apply(
				Long.MAX_VALUE - 10L, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

		assertEquals(Long.MAX_VALUE, result.energy());
		assertEquals(10L, result.injected());
	}

	@Test
	void lowWaterHysteresisAvoidsRepeatedTopUps() {
		assertTrue(Ae2EnergyMath.belowLowWater(1_000L, 2_000L, 1_000L));
		assertTrue(Ae2EnergyMath.belowLowWater(1_999L, 2_000L, 1_000L));
		assertFalse(Ae2EnergyMath.belowLowWater(2_001L, 4_000L, 1_000L));
		assertFalse(Ae2EnergyMath.belowLowWater(0L, 0L, 1_000L));
		assertFalse(Ae2EnergyMath.belowLowWater(0L, 2_000L, 0L));
	}

	@Test
	void highWaterTargetKeepsFourBatchesWithoutOverflow() {
		assertEquals(4_000L, Ae2EnergyMath.highWaterTarget(10_000L, 1_000L));
		assertEquals(2_000L, Ae2EnergyMath.highWaterTarget(2_000L, 1_000L));
		assertEquals(Long.MAX_VALUE, Ae2EnergyMath.highWaterTarget(Long.MAX_VALUE, Long.MAX_VALUE));
		assertEquals(0L, Ae2EnergyMath.highWaterTarget(10_000L, 0L));
	}

	@Test
	void highWaterRequestIsNotTruncatedAtFormer64MfeBudget() {
		long target = Ae2EnergyMath.highWaterTarget(2_000_000_000L, 200_000_000L);

		assertEquals(800_000_000L, target);
		assertTrue(target > 64_000_000L);
	}
	@Test
	void handlesNonZeroEnergyAndOversizedBridgeResults() {
		Ae2EnergyMath.InjectionResult result = Ae2EnergyMath.apply(25L, 100L, 90L, 90L);

		assertEquals(100L, result.energy());
		assertEquals(75L, result.injected());
		assertEquals(75L, Ae2EnergyMath.clampExtracted(Long.MAX_VALUE, 75L));
	}

	@Test
	void clampsFloatingPointAeConversionToRequestedFe() {
		assertEquals(Long.MAX_VALUE,
				Ae2EnergyMath.aeToFe((double) Long.MAX_VALUE / 2D, Long.MAX_VALUE, 2D));
		assertEquals(40L, Ae2EnergyMath.aeToFe(Double.POSITIVE_INFINITY, 40L, 2D));
		assertEquals(0L, Ae2EnergyMath.aeToFe(Double.NaN, 40L, 2D));
		assertEquals(20L, Ae2EnergyMath.aeToFe(10D, 40L, 2D));
	}

	@Test
	void rejectsInvalidCapacityAndNegativeExtraction() {
		assertEquals(0L, Ae2EnergyMath.remainingCapacity(100L, 100L));
		assertEquals(0L, Ae2EnergyMath.remainingCapacity(-1L, 0L));
		assertEquals(0L, Ae2EnergyMath.clampExtracted(-1L, 100L));
	}
}
