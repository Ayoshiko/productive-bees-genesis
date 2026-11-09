package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeeProductionSamplingTest {
	private static final Random NO_RANDOM = new Random() {
		@Override
		protected int next(int bits) {
			throw new AssertionError("Deterministic result consumed randomness");
		}
	};

	@Test
	void guaranteedOutputsRetainLongQuantitiesAndPerStackGenes() {
		assertEquals(4L * Integer.MAX_VALUE, BeeProductionSampling.sampleOutput(NO_RANDOM, Integer.MAX_VALUE, 1, 1, 1, 0, 3));
		assertEquals(600, BeeProductionSampling.sampleOutput(NO_RANDOM, 100, 2, 2, 1, 0, 3));
		assertEquals(1000, BeeProductionSampling.sampleOutput(NO_RANDOM, 100, 3, 3, 1, 0, 3));
		long mixed = BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 1, 0, 0)
				+ BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 1, 0, 3);
		assertEquals(50, mixed);
	}

	@Test
	void disabledOutputsAndStabilityKeepExistingSemantics() {
		assertEquals(0, BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 0, 1, 3));
		assertEquals(0, BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, Float.NaN, 1, 3));
		assertEquals(40, BeeProductionSampling.sampleOutput(NO_RANDOM, 10, 1, 1, 0.5F, 0.5F, 3));
		assertThrows(IllegalArgumentException.class, () -> BeeProductionSampling.sampleOutput(NO_RANDOM, -1, 1, 1, 1, 0, 0));
	}

	@Test
	void longSamplingSegmentsStayExactAndRejectUnrepresentableResults() {
		long rolls = 1L + Integer.MAX_VALUE;
		assertEquals(rolls * 4, BeeProductionSampling.sampleOutput(NO_RANDOM, rolls, 1, 1, 1, 0, 3));
		assertEquals(rolls, BeeProductionSampling.sampleGeneAdjustedSum(NO_RANDOM, 1, 1, rolls, 0));
		assertThrows(ArithmeticException.class,
				() -> BeeProductionSampling.sampleOutput(NO_RANDOM, Long.MAX_VALUE, 1, 1, 1, 0, 3));
	}

	@Test
	void finiteProbabilityAndGeneMassMatchesEveryPossibleTwoRollOutcome() {
		Map<Long, Integer> actual = new HashMap<>();
		for (int path = 0; path < 16; path++) {
			int decisions = path;
			RandomGenerator random = new RandomGenerator() {
				private int cursor;

				private int nextBit() {
					assertTrue(cursor < 4, "Finite scenario exceeded four decisions");
					int bit = (decisions >>> cursor) & 1;
					cursor++;
					return bit;
				}

				@Override
				public double nextDouble() {
					return nextBit() == 0 ? 0.25 : 0.75;
				}

				@Override
				public long nextLong(long bound) {
					assertEquals(2L, bound);
					return nextBit();
				}

				@Override
				public long nextLong() {
					throw new AssertionError("Unexpected unbounded random draw");
				}

				@Override
				public float nextFloat() {
					throw new AssertionError("Maintenance binomial sampling uses double decisions");
				}

				@Override
				public double nextGaussian() {
					throw new AssertionError("Approximation entered the finite distribution");
				}
			};
			long value = BeeProductionSampling.sampleOutput(random, 2, 2, 3, 0.5F, 0, 3);
			actual.merge(value, 1, Integer::sum);
		}
		// 每轮的零／六／十件概率为 1/2、1/4、1/4；两轮卷积覆盖全部结果。
		assertEquals(Map.of(0L, 4, 6L, 4, 10L, 4, 12L, 1, 16L, 2, 20L, 1), actual);
	}

	@Test
	void recursiveGeneSamplingCannotProduceImpossiblePerStackTotals() {
		int count = 33;
		long value = BeeProductionSampling.sampleOutput(new Random(42L), count, 2, 3, 1, 0, 3);
		assertTrue(value >= count * 6L && value <= count * 10L);
		assertEquals(0L, (value - count * 6L) % 4L);
	}

	@Test
	void suppliedRandomStateIsRepeatableWithoutGlobalRandomness() {
		var first = new Random(13);
		var second = new Random(13);
		for (int count : new int[]{1, 20, 33, 1000}) {
			assertEquals(BeeProductionSampling.sampleOutput(first, count, 1, 7, 0.4F, 0.1F, 2),
					BeeProductionSampling.sampleOutput(second, count, 1, 7, 0.4F, 0.1F, 2));
		}
		assertEquals(first.nextLong(), second.nextLong());
	}
}
