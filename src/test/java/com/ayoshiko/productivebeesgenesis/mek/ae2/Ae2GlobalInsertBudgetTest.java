package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2GlobalInsertBudgetTest {

	@Test
	void slowOperationThresholdIsStrictAndSharedByInputAndOutputScheduling() {
		assertFalse(Ae2GlobalInsertBudget.isSlowOperation(500_000L));
		assertTrue(Ae2GlobalInsertBudget.isSlowOperation(500_001L));
	}
}
