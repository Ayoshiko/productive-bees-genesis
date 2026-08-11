package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameTickGateTest {

	@Test
	void admitsOnlyOneAcceleratedInvocationPerGameTick() {
		GameTickGate gate = new GameTickGate();

		assertTrue(gate.tryEnter(100L));
		assertFalse(gate.tryEnter(100L));
		assertFalse(gate.tryEnter(100L));
		assertTrue(gate.tryEnter(101L));
	}

	@Test
	void resetAllowsCurrentTickAgain() {
		GameTickGate gate = new GameTickGate();
		assertTrue(gate.tryEnter(42L));
		gate.reset();
		assertTrue(gate.tryEnter(42L));
	}
}
