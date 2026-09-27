package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemEjectionBudgetTest {

	@Test
	void fastLargeBatchesKeepAllTheirAttempts() {
		ItemEjectionBudget budget = new ItemEjectionBudget();
		for (int i = 0; i < 100; i++) {
			assertTrue(budget.canAttempt(10, true));
			budget.record(10, true, 50_000L);
		}
	}

	@Test
	void slowSuccessfulInsertsAreBoundedEvenWithoutRejection() {
		ItemEjectionBudget budget = new ItemEjectionBudget();
		for (int i = 0; i < 3; i++) {
			assertTrue(budget.canAttempt(10, true));
			budget.record(10, true, 800_000L);
		}
		for (int retry = 0; retry < 256; retry++) assertFalse(budget.canAttempt(10, true));
		assertTrue(budget.canAttempt(11, true));
	}

	@Test
	void outputSlotsAndDirectProductionBothMakeProgressInEitherOrder() {
		for (boolean firstIsDirect : new boolean[]{true, false}) {
			ItemEjectionBudget budget = new ItemEjectionBudget();
			for (long tick = 0; tick < 20; tick++) {
				assertTrue(budget.canAttempt(tick, firstIsDirect));
				budget.record(tick, firstIsDirect, 20_000_000L);
				assertFalse(budget.canAttempt(tick, firstIsDirect));
				assertTrue(budget.canAttempt(tick, !firstIsDirect));
				budget.record(tick, !firstIsDirect, 20_000_000L);
				assertFalse(budget.canAttempt(tick, !firstIsDirect));
			}
		}
	}

	@Test
	void slowTargetDoesNotThrottleOtherOutputFacesOrMachines() {
		ItemEjectionBudget slow = new ItemEjectionBudget();
		ItemEjectionBudget fast = new ItemEjectionBudget();
		slow.record(10, true, 30_000_000L);
		assertFalse(slow.canAttempt(10, true));
		for (int i = 0; i < 38; i++) {
			assertTrue(fast.canAttempt(10, true));
			fast.record(10, true, 50_000L);
		}
	}

	@Test
	void targetRecoveryRestoresLargerBatches() {
		ItemEjectionBudget budget = new ItemEjectionBudget();
		budget.record(10, true, 3_000_000L);
		for (long tick = 11; tick < 60; tick++) budget.record(tick, true, 50_000L);
		for (int i = 0; i < 100; i++) {
			assertTrue(budget.canAttempt(60, true));
			budget.record(60, true, 50_000L);
		}
	}

	@Test
	void accumulatedCostCannotOverflowIntoAvailableBudget() {
		ItemEjectionBudget budget = new ItemEjectionBudget();
		budget.record(10, true, Long.MAX_VALUE);
		budget.record(10, true, Long.MAX_VALUE);
		assertFalse(budget.canAttempt(10, true));
	}
}
