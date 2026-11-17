package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeeProductivityGeneTest {

	@Test
	void totalsAboveIntLimitRemainExact() {
		assertEquals(4_096_000_000L, BeeProduceBatchSampler.sampleGeneAdjustedSum(
				new java.util.Random(1L), 1_000_000, 1_000_000, 4_096, BeeProductivityGene.NORMAL));
		assertEquals(4_294_967_296L, BeeProduceBatchSampler.sampleRollCount(
				new java.util.Random(1L), 2_147_483_648L, 2.0F));
	}

	@Test
	void veryHighVariableBatchStaysOnPossiblePerStackTotals() {
		long total = BeeProduceBatchSampler.sampleGeneAdjustedSum(
				new java.util.Random(42L), 2, 3, 33, BeeProductivityGene.VERY_HIGH);
		assertTrue(total >= 198 && total <= 330);
		assertEquals(0, (total - 198) % 4);
	}

	@Test
	void wideVariablePaidBatchAggregatesWithoutPerEventRandomWork() {
		class CountingRandom extends java.util.Random {
			int calls;

			CountingRandom() {
				super(6L);
			}

			@Override
			public long nextLong(long bound) {
				if (++calls > 10_000) throw new AssertionError("Wide batch reverted to per-event sampling");
				return super.nextLong(bound);
			}
		}
		var random = new CountingRandom();
		long events = 500_000;
		long total = BeeProduceBatchSampler.sampleGeneAdjustedSum(
				random, 1_000, 100_000, events, BeeProductivityGene.VERY_HIGH);
		long lower = 1_000 + Math.round((double) (2.2F * 1_000));
		long upper = 100_000 + Math.round((double) (2.2F * 100_000));
		assertTrue(total >= lower * events && total <= upper * events);
		assertTrue(random.calls < 10_000);
	}

}
