package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreFrameBudgetTest {
	@Test void allMachinesShareOneBoundAndRepeatedDrawsDoNotSpendAgain() {
		var budget = new CoreFrameBudget<Object>();
		var first = new String("same");
		assertTrue(budget.allow(first));
		for (int i = 1; i < 16; i++) assertTrue(budget.allow(new String("same")));
		for (int i = 0; i < 256; i++) {
			assertFalse(budget.allow(new Object()));
			assertTrue(budget.allow(first));
			assertEquals(16, budget.retained());
		}
		assertFalse(budget.allow(null));
	}
	@Test void nextFrameAndLifecycleClearReleaseAllPriorIdentities() {
		var budget = new CoreFrameBudget<Object>();
		var first = new Object();
		for (int i = 0; i < 16; i++) assertTrue(budget.allow(new Object()));
		assertFalse(budget.allow(first));
		budget.clear();
		assertEquals(0, budget.retained());
		assertTrue(budget.allow(first));
		assertEquals(1, budget.retained());
	}
}
