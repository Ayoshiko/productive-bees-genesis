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

		assertEquals(128, sampleCount);
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

		assertEquals(128, sampleCount);
		assertEquals((long) productionCount, totalWeight);
	}
}
