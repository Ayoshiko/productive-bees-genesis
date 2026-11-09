package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceProgressPlanTest {
	@Test void capacityChargesOnlyWorkThatCanFinish() {
		assertEquals(new ResourceProgressPlan(15, 2, 0), ResourceProgressPlan.plan(5, 10, 256, 2));
		assertEquals(new ResourceProgressPlan(20, 2, 0), ResourceProgressPlan.plan(0, 10, 25, 2));
		assertEquals(new ResourceProgressPlan(0, 0, 5), ResourceProgressPlan.plan(5, 10, 256, 0));
	}

	@Test void speedChangeDrainsAlreadyPaidProgressWithoutChargingItAgain() {
		assertEquals(new ResourceProgressPlan(0, 2, 75), ResourceProgressPlan.plan(95, 10, 0, 2));
		assertEquals(new ResourceProgressPlan(0, 7, 5), ResourceProgressPlan.plan(75, 10, 0, 100));
	}

	@Test void incompleteWorkAndIntegerBoundaryRemainExact() {
		assertEquals(new ResourceProgressPlan(3, 0, 8), ResourceProgressPlan.plan(5, 10, 3, 1));
		assertEquals(new ResourceProgressPlan(Integer.MAX_VALUE, 4294967294L, 0),
				ResourceProgressPlan.plan(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, Long.MAX_VALUE));
		assertEquals(new ResourceProgressPlan(Integer.MAX_VALUE, 2, 0),
				ResourceProgressPlan.plan(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));
	}

	@Test void acceptedWorkConservesTicksAcrossCapacitiesAndCycleLengths() {
		for (int stored : new int[]{0, 1, 9, 40, 1000, Integer.MAX_VALUE}) {
			for (int required : new int[]{1, 10, 97, Integer.MAX_VALUE}) {
				for (int allocated : new int[]{0, 1, 256, Integer.MAX_VALUE}) {
					for (long capacity : new long[]{0, 1, 4, Long.MAX_VALUE}) {
						var plan = ResourceProgressPlan.plan(stored, required, allocated, capacity);
						assertTrue(plan.advancedTicks() >= 0 && plan.advancedTicks() <= allocated);
						assertTrue(plan.completedCycles() >= 0 && plan.completedCycles() <= capacity);
						assertTrue(plan.remainingTicks() >= 0);
						assertEquals((long) stored + plan.advancedTicks(),
								plan.completedCycles() * required + plan.remainingTicks());
					}
				}
			}
		}
	}

	@Test void invalidWorkIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> ResourceProgressPlan.plan(-1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> ResourceProgressPlan.plan(0, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> ResourceProgressPlan.plan(0, 1, -1, 1));
		assertThrows(IllegalArgumentException.class, () -> ResourceProgressPlan.plan(0, 1, 1, -1));
	}
}
