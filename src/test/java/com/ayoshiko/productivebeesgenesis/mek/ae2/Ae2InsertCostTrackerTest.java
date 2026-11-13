package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** 网络更换后拒绝旧网络迟到成本，避免影响新网络供料。 */
class Ae2InsertCostTrackerTest {
	@Test
	void networkReplacementClearsOldCostAndRejectsLateSamples() {
		var tracker = new Ae2InsertCostTracker();
		var oldNetwork = new Object();
		var nextNetwork = new Object();
		tracker.useNetwork(oldNetwork);
		tracker.recordForNetwork(oldNetwork, 200, 6_000_000);
		assertEquals(1, tracker.keyQuota(32));
		tracker.useNetwork(nextNetwork);
		tracker.recordForNetwork(oldNetwork, 200, 6_000_000);
		assertEquals(32, tracker.keyQuota(32));
		assertFalse(tracker.isExhausted(201));
		assertEquals(0, tracker.successDelayNanos());
	}
}
