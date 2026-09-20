package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeeProgressPlanTest {
	@Test void allocatedSegmentsPreserveElapsedWorkAndEnergy() {
		var random = new Random(0xD13BEE);
		for (int scenario = 0; scenario < 100; scenario++) {
			int period = 1 + random.nextInt(1200), stack = 1 + random.nextInt(16);
			int remainder = random.nextInt(period), initial = remainder;
			long elapsed = 0, cycles = 0, production = 0, paid = 0;
			for (int segment = 0; segment < 100; segment++) {
				int ticks = random.nextInt(257);
				var plan = BeeProgressPlan.plan(remainder, ticks, period, 37, stack);
				remainder = plan.remainingTicks(); elapsed += ticks; cycles += plan.completedCycles();
				production += plan.productionCycles(); paid += plan.energyCost();
				assertEquals(initial + elapsed, cycles * period + remainder);
				assertEquals(cycles * stack, production);
				assertEquals(elapsed * 37, paid);
			}
		}
	}
	@Test void pausedWorkDoesNotSettleOldProgressAfterAnUpgrade() {
		assertEquals(new BeeProgressPlan(1, 590, 0, 0, 0), BeeProgressPlan.plan(590, 0, 1, 50, 16));
		var resumed = BeeProgressPlan.plan(590, 10, 300, 50, 16);
		assertEquals(2, resumed.completedCycles()); assertEquals(32, resumed.productionCycles());
		assertEquals(500, resumed.energyCost()); assertEquals(0, resumed.remainingTicks());
	}
	@Test void plansRetainMoreCyclesThanPhysicalIntCountersCanRepresent() {
		var plan = BeeProgressPlan.plan(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 0, Integer.MAX_VALUE);
		assertEquals(4_294_967_294L, plan.completedCycles());
		assertEquals(9_223_372_028_264_841_218L, plan.productionCycles());
	}
	@Test void periodAndInvalidInputHaveExplicitBoundaries() {
		assertEquals(300, BeeProgressPlan.cycleTicks(1200, 600, 0.25F, false));
		assertEquals(600, BeeProgressPlan.cycleTicks(0, 600, Float.NaN, false));
		assertEquals(1, BeeProgressPlan.cycleTicks(1200, 600, Float.MAX_VALUE, true));
		assertEquals(Integer.MAX_VALUE, BeeProgressPlan.cycleTicks(1200, 600, Float.MAX_VALUE, false));
		assertThrows(IllegalArgumentException.class, () -> BeeProgressPlan.plan(-1, 1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> BeeProgressPlan.plan(0, -1, 1, 1, 1));
		assertThrows(ArithmeticException.class, () -> BeeProgressPlan.plan(0, 2, 1, Long.MAX_VALUE, 1));
	}
}
