package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WeightedAllocationTest {

	@Test
	void invalidFloatingPointWeightsFallBackInConstantTime() {
		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
			assertNull(WeightedAllocationMath.allocate(
					Integer.MAX_VALUE, new double[] {Double.NaN, 1.0D, 1.0D}));
			assertNull(WeightedAllocationMath.allocate(
					Integer.MAX_VALUE, new double[] {Double.POSITIVE_INFINITY, 1.0D, 1.0D}));
		});
	}

	@Test
	void maximumTotalRemainsExactForFiniteWeights() {
		int[] allocation = WeightedAllocationMath.allocate(
				Integer.MAX_VALUE, new double[] {4.0D, 2.0D, 1.0D});
		assertEquals(Integer.MAX_VALUE, sum(allocation));
	}

	private static long sum(int[] allocation) {
		long sum = 0L;
		for (int amount : allocation) sum += amount;
		return sum;
	}
}
