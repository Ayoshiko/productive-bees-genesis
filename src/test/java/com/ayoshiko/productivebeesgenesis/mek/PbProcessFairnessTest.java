package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PbProcessFairnessTest {

	@Test
	void dividesRemainingEnergyWithoutStarvingEarlyLanes() {
		assertEquals(34L, PbProcessFairness.energyBudget(100L, 3));
		assertEquals(1L, PbProcessFairness.energyBudget(2L, 3));
		assertEquals(100L, PbProcessFairness.energyBudget(100L, 1));
	}

	@Test
	void rejectsEmptyBudgets() {
		assertEquals(0L, PbProcessFairness.energyBudget(0L, 3));
		assertEquals(0L, PbProcessFairness.energyBudget(100L, 0));
		assertEquals(0L, PbProcessFairness.energyBudget(-1L, 2));
	}
}
