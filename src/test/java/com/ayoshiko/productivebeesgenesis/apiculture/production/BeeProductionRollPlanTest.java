package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiary.BeeProduceBatchSampler;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeeProductionRollPlanTest {
	@Test void fractionalBoundaryPreservesSingleCycleBernoulli() {
		var plan = BeeProductionRollPlan.fromMultiplier(2.5f);
		assertEquals(BigInteger.TWO, plan.fixedRolls().exact());
		assertEquals(0.5, plan.extraChance());
		assertEquals(BigInteger.valueOf(3), plan.sampleCycle(0).exact());
		assertEquals(BigInteger.valueOf(3), plan.sampleCycle(Math.nextDown(0.5)).exact());
		assertEquals(BigInteger.TWO, plan.sampleCycle(0.5).exact());
		assertEquals(BigInteger.TWO, plan.sampleCycle(Math.nextDown(1.0)).exact());
		assertEquals(BigInteger.ZERO, BeeProductionRollPlan.fromMultiplier(0.5f).sampleCycle(0.5).exact());
		assertEquals(BigInteger.ONE, BeeProductionRollPlan.fromMultiplier(1).sampleCycle(0).exact());
		assertEquals(BigInteger.ONE, BeeProductionRollPlan.fromMultiplier(Float.MIN_VALUE).sampleCycle(0).exact());
		assertEquals(BigInteger.ZERO, BeeProductionRollPlan.fromMultiplier(Float.MIN_VALUE).sampleCycle((double) Float.MIN_VALUE).exact());
	}

	@Test void singleCyclesMatchExistingPhysicalRollSamplerForFixedSeeds() {
		float[] multipliers = { 1, 2.2f, 2.5f, 3, 3.6f, 10.6f, 21.8f, 71.28f, Math.nextDown(2f), Math.nextUp(2f) };
		for (float multiplier : multipliers) {
			var plan = BeeProductionRollPlan.fromMultiplier(multiplier);
			var expectedRandom = new Random(731_901);
			var actualRandom = new Random(731_901);
			for (int cycle = 0; cycle < 2_048; cycle++) {
				int expected = BeeProduceBatchSampler.sampleRollCount(expectedRandom, 1, multiplier);
				double draw = multiplier == Math.floor(multiplier) ? 0 : actualRandom.nextDouble();
				assertEquals(BigInteger.valueOf(expected), plan.sampleCycle(draw).exact(), "multiplier=" + multiplier);
			}
		}
	}

	@Test void genesApplyToEachOriginalStackBeforeRollAggregation() {
		var plan = BeeProductionRollPlan.fromMultiplier(2f);
		// 两轮一件、低生产力基因：各得两件，共四件；先合并两件再取整会错误地得到五件。
		assertEquals(BigInteger.valueOf(4), plan.guaranteedOutput(1, 1, 0).exact());
		assertEquals(5, BeeProductionSampling.adjustStackCount(2, 1));
		for (int gene = 0; gene <= 3; gene++) {
			for (int base = 1; base <= 64; base++) {
				int perStack = gene == 0 ? base : base == 1 ? 1 + gene
						: base + Math.round((1f / (gene + 2f) + (gene + 1f) / 2f) * base);
				assertEquals(BigInteger.valueOf(2L * perStack), plan.guaranteedOutput(base, gene, 0.75).exact());
			}
		}
	}

	@Test void wholeFloatRangePreservesExactBitsBeyondIntAndLong() {
		for (int exponent = 31; exponent <= 127; exponent++) {
			for (int mantissa : new int[] { 0, 1, 0x345678, 0x7fffff }) {
				float multiplier = Float.intBitsToFloat(((exponent + 127) << 23) | mantissa);
				BigInteger expected = BigInteger.valueOf((1 << 23) | mantissa).shiftLeft(exponent - 23);
				var plan = BeeProductionRollPlan.fromMultiplier(multiplier);
				assertEquals(expected, plan.sampleCycle(0).exact());
				assertEquals(expected, plan.sampleCycle(Math.nextDown(1.0)).exact());
				assertEquals(0, plan.extraChance());
				assertEquals(expected.multiply(BigInteger.valueOf(Integer.MAX_VALUE)),
						plan.guaranteedOutput(Integer.MAX_VALUE, 3, 0).exact());
			}
		}
		var belowLong = BeeProductionRollPlan.fromMultiplier(Math.nextDown(0x1.0p63f));
		assertTrue(belowLong.fixedRolls().fitsLong());
		assertFalse(BeeProductionRollPlan.fromMultiplier(0x1.0p63f).fixedRolls().fitsLong());
	}

	@Test void rejectedInputsAndRepeatedEvaluationDoNotChangeThePlan() {
		for (float multiplier : new float[] { 0, -0f, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
			assertThrows(IllegalArgumentException.class, () -> BeeProductionRollPlan.fromMultiplier(multiplier));
		var plan = BeeProductionRollPlan.fromMultiplier(2.5f);
		var before = plan.fixedRolls();
		for (double draw : new double[] { -Double.MIN_VALUE, 1, Double.NaN, Double.POSITIVE_INFINITY })
			assertThrows(IllegalArgumentException.class, () -> plan.sampleCycle(draw));
		assertThrows(IllegalArgumentException.class, () -> plan.guaranteedOutput(0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> plan.guaranteedOutput(1, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> plan.guaranteedOutput(1, 4, 0));
		assertEquals(plan.guaranteedOutput(3, 2, 0.25), plan.guaranteedOutput(3, 2, 0.25));
		assertSame(before, plan.fixedRolls());
	}

	@Test void deterministicDrawGridKeepsFractionalEventsAndPerStackRounding() {
		var plan = BeeProductionRollPlan.fromMultiplier(2.25f);
		BigInteger total = BigInteger.ZERO;
		for (int i = 0; i < 4_096; i++)
			total = total.add(plan.guaranteedOutput(1, 3, i / 4_096.0).exact());
		assertEquals(BigInteger.valueOf((2L * 4_096 + 1_024) * 4), total);
	}
}
