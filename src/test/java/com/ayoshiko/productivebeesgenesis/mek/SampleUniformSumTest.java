package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class SampleUniformSumTest {

	@Test
	void fixedExtremeRangeSaturatesWithoutOverflow() {
		assertEquals(Long.MAX_VALUE, SampleUniformSum.sample(
				ThreadLocalRandom.current(), Integer.MAX_VALUE, Integer.MAX_VALUE,
				Long.MAX_VALUE, Integer.MAX_VALUE));
	}

	@Test
	void singleSampleSupportsTheFullPositiveIntRange() {
		for (int i = 0; i < 100; i++) {
			int sampled = SampleUniformSum.sampleSingle(
					ThreadLocalRandom.current(), 0, Integer.MAX_VALUE);
			assertTrue(sampled >= 0);
		}
	}

	@Test
	void invalidRangesAndMultipliersProduceNothing() {
		assertEquals(0L, SampleUniformSum.sample(ThreadLocalRandom.current(), 2, 1, 10L, 1));
		assertEquals(0L, SampleUniformSum.sample(ThreadLocalRandom.current(), 0, 1, 10L, 0));
	}
}
