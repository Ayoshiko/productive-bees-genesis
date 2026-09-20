package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.mek.BatchProbabilitySampler;
import com.ayoshiko.productivebeesgenesis.mek.SampleUniformSum;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CentrifugeProductionSamplingTest {
	@Test
	void fixedSeedsPreserveLegacyCountsAndRandomSequence() {
		int[][] ranges = {{1, 1}, {0, 4}, {3, 19}, {-1, -2}, {0, Integer.MAX_VALUE}};
		for (int seed = 0; seed < 64; seed++) {
			var actualRandom = new Random(seed);
			var referenceRandom = new Random(seed);
			for (int rolls : new int[]{1, 2, 3, 30, 31, 256, 65_536, Integer.MAX_VALUE}) {
				for (float chance : new float[]{0, 0.031F, 0.5F, 0.999F, 1, Float.NaN}) {
					for (float stability : new float[]{0, 0.25F}) {
						for (var range : ranges) {
							int modifier = seed % 2 == 0 ? 4 : Integer.MAX_VALUE;
							long base = CentrifugeProductionSampling.sampleOutput(actualRandom,
									rolls, range[0], range[1], chance, stability);
							long actual = SaturatingMath.saturatingMultiply(base, modifier);
							assertEquals(legacy(referenceRandom, rolls, range[0], range[1], chance, stability, modifier), actual);
							assertEquals(referenceRandom.nextLong(), actualRandom.nextLong(), "random order changed");
						}
					}
				}
			}
		}
	}

	@Test
	void exactMultiplierIsAppliedAfterBoundedSampling() {
		long base = CentrifugeProductionSampling.sampleOutput(new Random(15), Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 0);
		assertEquals((long) Integer.MAX_VALUE * Integer.MAX_VALUE, base);
		assertEquals(BigInteger.valueOf(Integer.MAX_VALUE).pow(3),
				ProductAmount.of(base).multiply(Integer.MAX_VALUE).exact());
		assertEquals(Long.MAX_VALUE, SaturatingMath.saturatingMultiply(base, Integer.MAX_VALUE));
	}

	@Test
	void batchGuaranteeIsDeliberatelyDifferentFromIndependentBernoulliTrials() {
		for (int seed = 0; seed < 100; seed++) {
			var random = new Random(seed);
			assertEquals(5, CentrifugeProductionSampling.sampleOutput(random, 10, 1, 1, 0.5F, 0));
			assertEquals(new Random(seed).nextLong(), random.nextLong(), "integer expectation needs no randomness");
		}
	}

	@Test
	void singleZeroChanceStillConsumesItsLegacyFloatButCertainOutputDoesNot() {
		var actual = new Random(15);
		var expected = new Random(15);
		assertEquals(0, CentrifugeProductionSampling.sampleOutput(actual, 1, 3, 3, 0, 0));
		expected.nextFloat();
		assertEquals(expected.nextLong(), actual.nextLong());
		assertEquals(3, CentrifugeProductionSampling.sampleOutput(actual, 1, 3, 3, 1, 0));
		assertEquals(expected.nextLong(), actual.nextLong());
	}

	@Test
	void noWorkDoesNotConsumeRandomnessAndNegativeWorkIsRejected() {
		var random = new Random(15);
		assertEquals(0, CentrifugeProductionSampling.sampleOutput(random, 0, 0, 8, 0.5F, 0));
		assertEquals(new Random(15).nextLong(), random.nextLong());
		assertThrows(IllegalArgumentException.class,
				() -> CentrifugeProductionSampling.sampleOutput(random, -1, 0, 8, 0.5F, 0));
	}

	// 抽取前的聚合顺序作为回归基线；底层概率工具未在 D15a 改动。
	private static long legacy(Random random, int rolls, int minimum, int maximum,
			float chance, float stability, int modifier) {
		float adjusted = Float.isNaN(chance) ? 0 : (float) Math.max(0, Math.min(1, chance + (double) stability));
		int min = Math.max(0, minimum), max = Math.max(min, maximum);
		if (rolls == 1) {
			if (adjusted < 1 && random.nextFloat() >= adjusted) return 0;
			return SaturatingMath.saturatingMultiply(SampleUniformSum.sampleSingle(random, min, max), modifier);
		}
		if (adjusted >= 1) return SampleUniformSum.sample(random, min, max, rolls, modifier);
		long successes = BatchProbabilitySampler.sampleBinomialWithGuarantee(random, rolls, adjusted);
		return successes <= 0 ? 0 : SampleUniformSum.sample(random, min, max, successes, modifier);
	}
}
