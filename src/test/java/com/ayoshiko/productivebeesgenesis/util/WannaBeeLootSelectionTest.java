package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

class WannaBeeLootSelectionTest {

	@Test
	void singleCandidateReceivesEveryRollWithoutRandomWork() {
		assertArrayEquals(new int[] {1_024},
				WannaBeeLootSelection.sampleCounts(RandomSource.create(1L), 1_024, 1));
	}

	@Test
	void repeatedCandidateIndexesKeepIndependentWeight() {
		int[] counts = WannaBeeLootSelection.sampleCounts(RandomSource.create(42L), 10_000, 3);
		assertEquals(10_000, counts[0] + counts[1] + counts[2]);
		for (int count : counts) {
			org.junit.jupiter.api.Assertions.assertTrue(count > 3_000 && count < 3_700);
		}
	}

	@Test
	void invalidArgumentsReturnEmptyOrZeroedCounts() {
		assertArrayEquals(new int[0], WannaBeeLootSelection.sampleCounts(null, 1, -1));
		assertArrayEquals(new int[] {0, 0},
				WannaBeeLootSelection.sampleCounts(RandomSource.create(1L), 0, 2));
	}
}
