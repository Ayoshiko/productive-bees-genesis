package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2OutputPusherTest {

	@Test
	void mergesTwoOrMoreSlotsToReduceNetworkInsertCalls() {
		assertFalse(Ae2OutputMergePolicy.shouldMergeEntries(1));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(2));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(3));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(4));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(8));
	}
}
