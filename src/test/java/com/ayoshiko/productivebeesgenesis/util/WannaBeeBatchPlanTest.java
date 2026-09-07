package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WannaBeeBatchPlanTest {

	@Test
	void ordinaryBatchUsesOneIndependentSamplePerProduction() {
		assertEquals(9, WannaBeeBatchPlan.sampleCount(9));
		for (int i = 0; i < 9; i++) {
			assertEquals(1, WannaBeeBatchPlan.weightAt(9, i));
		}
	}

	@Test
	void acceleratedBatchKeepsBoundedSamplesWithoutLosingWeight() {
		int productionCount = 1_000;
		int sampleCount = WannaBeeBatchPlan.sampleCount(productionCount);
		int totalWeight = 0;
		for (int i = 0; i < sampleCount; i++) {
			totalWeight += WannaBeeBatchPlan.weightAt(productionCount, i);
		}

		assertEquals(16, sampleCount);
		assertEquals(productionCount, totalWeight);
	}

	@Test
	void maximumBatchKeepsExactWeightWithoutOverflow() {
		int productionCount = Integer.MAX_VALUE;
		int sampleCount = WannaBeeBatchPlan.sampleCount(productionCount);
		long totalWeight = 0;
		for (int i = 0; i < sampleCount; i++) {
			totalWeight += WannaBeeBatchPlan.weightAt(productionCount, i);
		}

		assertEquals(16, sampleCount);
		assertEquals((long) productionCount, totalWeight);
	}

	@Test
	void batchesAtOrBelowBudgetRemainExact() {
		assertEquals(16, WannaBeeBatchPlan.sampleCount(16));
		assertEquals(16, WannaBeeBatchPlan.sampleCount(17));
		for (int i = 0; i < 16; i++) {
			assertEquals(1, WannaBeeBatchPlan.weightAt(16, i));
		}
	}

	@Test
	void mixedLevelsShareOneGroupBudget() {
		int[] samples = WannaBeeBatchPlan.allocateSampleCounts(new int[] {256, 128, 64, 32});
		int total = 0;
		for (int sample : samples) total += sample;
		assertEquals(16, total);
		for (int sample : samples) {
			org.junit.jupiter.api.Assertions.assertTrue(sample > 0);
		}
	}

	@Test
	void allocatedWeightsRemainExactPerLevel() {
		int[] rollCounts = {100, 37, 5};
		int[] samples = WannaBeeBatchPlan.allocateSampleCounts(rollCounts);
		for (int level = 0; level < rollCounts.length; level++) {
			int represented = 0;
			for (int i = 0; i < samples[level]; i++) {
				represented += WannaBeeBatchPlan.weightAt(rollCounts[level], samples[level], i);
			}
			assertEquals(rollCounts[level], represented);
		}
	}
}
