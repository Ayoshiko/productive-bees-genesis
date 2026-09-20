package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeeProductionSamplingTest {
	private static final Random NO_RANDOM = new Random() {
		@Override protected int next(int bits) { throw new AssertionError("Deterministic result consumed randomness"); }
	};
	@Test void guaranteedOutputsRetainLongQuantitiesAndPerStackGenes() {
		assertEquals(4L * Integer.MAX_VALUE, BeeProductionSampling.sampleOutput(NO_RANDOM, Integer.MAX_VALUE, 1, 1, 1, 0, 3));
		assertEquals(600, BeeProductionSampling.sampleOutput(NO_RANDOM, 100, 2, 2, 1, 0, 3));
		assertEquals(1000, BeeProductionSampling.sampleOutput(NO_RANDOM, 100, 3, 3, 1, 0, 3));
		long mixed = BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 1, 0, 0)
				+ BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 1, 0, 3);
		assertEquals(50, mixed);
	}
	@Test void disabledOutputsAndStabilityKeepExistingSemantics() {
		assertEquals(0, BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 0, 1, 3));
		assertEquals(0, BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, Float.NaN, 1, 3));
		assertEquals(40, BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 0.5F, 0.5F, 3));
		assertThrows(IllegalArgumentException.class, () -> BeeProductionSampling.sampleOutput(NO_RANDOM, -1, 1, 1, 1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> BeeProductionSampling.sampleGeneAdjustedSum(NO_RANDOM, 1, 1, 1L + Integer.MAX_VALUE, 0));
	}
	@Test void declaredBinomialModesKeepMeanVarianceAndSupport() {
		checkDistribution(20, 0.35F);
		checkDistribution(1000, 0.001F);
		checkDistribution(1000, 0.35F);
	}
	private static void checkDistribution(int rolls, float chance) {
		var random = new Random(0xD130000L + rolls);
		int samples = 30_000;
		double sum = 0, squares = 0;
		for (int i = 0; i < samples; i++) {
			long value = BeeProductionSampling.sampleOutput(random, rolls, 1, 1, chance, 0, 0);
			assertTrue(value >= 0 && value <= rolls);
			sum += value; squares += (double) value * value;
		}
		double mean = sum / samples, variance = squares / samples - mean * mean;
		double expectedMean = rolls * (double) chance;
		double expectedVariance = rolls * (double) chance * (1 - chance);
		assertEquals(expectedMean, mean, 6 * Math.sqrt(expectedVariance / samples));
		assertEquals(expectedVariance, variance, expectedVariance * 0.08);
	}
	@Test void uniformGeneSamplingRetainsPerStackRoundingAtSmallAndLargeSizes() {
		for (int count : new int[]{1, 2, 32, 33, 1000}) {
			var random = new Random(0xD13L + count);
			double sum = 0;
			for (int i = 0; i < 10_000; i++) {
				long value = BeeProductionSampling.sampleOutput(random, count, 2, 3, 1, 0, 3);
				assertTrue(value >= count * 6L && value <= count * 10L);
				sum += value;
			}
			assertEquals(count * 8.0, sum / 10_000, Math.max(0.1, count * 0.015));
		}
	}
	@Test void suppliedRandomStateIsRepeatableWithoutGlobalRandomness() {
		var first = new Random(13); var second = new Random(13);
		for (int count : new int[]{1, 20, 33, 1000}) {
			assertEquals(BeeProductionSampling.sampleOutput(first, count, 1, 7, 0.4F, 0.1F, 2),
					BeeProductionSampling.sampleOutput(second, count, 1, 7, 0.4F, 0.1F, 2));
		}
		assertEquals(first.nextLong(), second.nextLong());
	}
}
