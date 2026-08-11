package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PbVirtualTickPlanTest {

	@Test
	void accelerationAdvancesProgressForSingleInput() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(0, 256, 200, 256, 1, 40L, Long.MAX_VALUE);

		assertEquals(1, plan.completedOperations());
		assertEquals(0, plan.remainingProgress());
		assertEquals(200, plan.executedTicks());
		assertEquals(8_000L, plan.energyUsed());
	}

	@Test
	void accelerationCompletesMultipleCyclesInConstantTimePlan() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(50, 256, 100, 4, 20, 25L, Long.MAX_VALUE);

		assertEquals(12, plan.completedOperations());
		assertEquals(6, plan.remainingProgress());
		assertEquals(256, plan.executedTicks());
		assertEquals(25_600L, plan.energyUsed());
	}

	@Test
	void energyLimitPreservesPartialProgress() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(0, 256, 200, 4, 64, 100L, 20_000L);

		assertEquals(0, plan.completedOperations());
		assertEquals(50, plan.remainingProgress());
		assertEquals(50, plan.executedTicks());
		assertEquals(20_000L, plan.energyUsed());
	}

	@Test
	void stackParallelismDownscalesToAvailableEnergyInsteadOfStalling() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(
				0, 1, 1, 65_536, 1_000_000, 100L, 10_000L);

		assertEquals(100, plan.completedOperations());
		assertEquals(0, plan.remainingProgress());
		assertEquals(1, plan.executedTicks());
		assertEquals(10_000L, plan.energyUsed());
	}

	@Test
	void zeroTickCreativePlanCompletesEveryVirtualTick() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(0, 20, 0, 1, 20, 0L, 0L);

		assertEquals(20, plan.completedOperations());
		assertEquals(20, plan.executedTicks());
		assertEquals(0L, plan.energyUsed());
	}

@Test
	void hugeEnergyPerOperationSaturatesWithoutOverflow() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(
				0, 1, 1, 65_536, 65_536, Long.MAX_VALUE, Long.MAX_VALUE);

		assertEquals(1, plan.completedOperations());
		assertEquals(1, plan.executedTicks());
		assertEquals(Long.MAX_VALUE, plan.energyUsed());
	}

	@Test
	void hugeEnergyStackStaysWithinAvailableBudget() {
		long perOperation = Long.MAX_VALUE / 4L;
		long available = Long.MAX_VALUE / 2L;
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(
				0, 256, 100, 65_536, 100_000, perOperation, available);

		assertEquals(0, plan.completedOperations());
		assertEquals(1, plan.remainingProgress());
		assertEquals(1, plan.executedTicks());
		// Two operations at Long.MAX_VALUE/4 each cost Long.MAX_VALUE/2 - 1, which stays within the budget.
		assertEquals(2L * perOperation, plan.energyUsed());
		assertTrue(plan.energyUsed() <= available);
	}

	@Test
	void creativeStackPlanRetainsParallelismUnderAcceleration() {
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(0, 256, 0, 256, 65_536, 0L, 0L);

		assertEquals(65_536, plan.completedOperations());
		assertEquals(256, plan.executedTicks());
		assertEquals(0L, plan.energyUsed());
	}
}
