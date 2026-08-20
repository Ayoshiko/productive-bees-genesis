package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

	@Test
	void networkExtractCapKeepsFivePercentReserve() {
		// 常规存量：保留 5%（1,000,000 → 可抽 950,000）
		assertEquals(950_000L, Ae2EnergyMath.networkExtractCap(1_000_000L));
		// 大额存量饱和安全
		assertEquals(Long.MAX_VALUE - Long.MAX_VALUE / 20,
				Ae2EnergyMath.networkExtractCap(Long.MAX_VALUE));
		// 微量存量：保留量向下取整为 0，全量可抽（无保留意义）
		assertEquals(19L, Ae2EnergyMath.networkExtractCap(19L));
		assertEquals(1L, Ae2EnergyMath.networkExtractCap(1L));
		// 非正值短路
		assertEquals(0L, Ae2EnergyMath.networkExtractCap(0L));
		assertEquals(0L, Ae2EnergyMath.networkExtractCap(-5L));
	}
}
