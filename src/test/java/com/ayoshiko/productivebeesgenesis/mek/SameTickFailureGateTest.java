package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SameTickFailureGateTest {

	@Test
	void skipsOnlyTheFailedStateInTheSameGameTick() {
		SameTickFailureGate gate = new SameTickFailureGate();

		assertFalse(gate.shouldSkip(100L, 5L));
		gate.recordFailure(100L, 5L);
		assertTrue(gate.shouldSkip(100L, 5L));
		assertFalse(gate.shouldSkip(100L, 6L));
		assertFalse(gate.shouldSkip(101L, 5L));
	}

	@Test
	void successfulWorkClearsTheFailure() {
		SameTickFailureGate gate = new SameTickFailureGate();

		gate.recordFailure(42L, 9L);
		gate.clear();
		assertFalse(gate.shouldSkip(42L, 9L));
	}
}
