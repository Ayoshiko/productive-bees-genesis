package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;

import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CentrifugeDispatchScopeTest {
	@BeforeEach
	@AfterEach
	void reset() {
		CentrifugeDispatchScope.reset();
	}

	@Test
	void sameTickIsBoundedAndRejectedAttemptsDoNotWrapCounter() {
		assertEquals(256, fillTick(1, 1.0));
		for (int i = 0; i < 20_000; i++) {
			assertTrue(CentrifugeDispatchScope.externalPushOverBudget(1, 1.0));
		}
		assertEquals(512, fillTick(2, 1.0));
	}

	@Test
	void lowDemandDoesNotGrowBudget() {
		for (long tick = 1; tick <= 100; tick++) {
			assertFalse(CentrifugeDispatchScope.externalPushOverBudget(tick, 1.0));
		}
		assertEquals(256, CentrifugeDispatchScope.budgetForTest());
	}

	@Test
	void idleGapDoesNotTreatOldSaturationAsCurrentDemand() {
		assertEquals(256, fillTick(1, 1.0));
		assertEquals(256, fillTick(100, 1.0));
	}

	@Test
	void hysteresisDeadZoneKeepsSaturatedBudget() {
		assertEquals(256, fillTick(1, 1.0));
		assertEquals(512, fillTick(2, 1.0));
		assertEquals(512, fillTick(3, 0.70));
		assertEquals(512, fillTick(4, 0.65));
		assertEquals(1024, fillTick(5, 0.75));
	}

	@Test
	void firstDecayWorksAndCooldownPreventsRepeatedCollapse() {
		fillTick(1, 1.0);
		fillTick(2, 1.0);
		assertEquals(1024, fillTick(3, 1.0));
		assertEquals(768, fillTick(4, 0.60));
		assertEquals(768, fillTick(5, 0.60));
		assertEquals(768, fillTick(6, 0.60));
		assertEquals(768, fillTick(7, 0.60));
		assertEquals(576, fillTick(8, 0.60));
	}

	@Test
	void recoveryAfterDecayUsesAdditiveGrowth() {
		fillTick(1, 1.0);
		fillTick(2, 1.0);
		fillTick(3, 1.0);
		assertEquals(768, fillTick(4, 0.60));
		assertEquals(1280, fillTick(5, 1.0));
	}

	@Test
	void persistentLagRetainsProgressFloor() {
		for (long tick = 1; tick <= 100; tick++) {
			assertEquals(256, fillTick(tick, 0.1));
		}
	}

	@Test
	void healthySaturationStopsAtOverflowGuard() {
		for (long tick = 1; tick <= 15; tick++) fillTick(tick, 1.0);
		assertEquals(CentrifugeDispatchScope.MAX_BUDGET, fillTick(16, 1.0));
	}

	@Test
	void invalidFeedbackDoesNotGrowOrDecay() {
		fillTick(1, 1.0);
		assertEquals(256, fillTick(2, Double.NaN));
		assertEquals(256, fillTick(3, Double.POSITIVE_INFINITY));
		assertEquals(256, fillTick(4, Double.NEGATIVE_INFINITY));
	}

	@Test
	void clockRollbackAndExplicitResetReleaseOldBudget() {
		fillTick(100, 1.0);
		fillTick(101, 1.0);
		assertEquals(256, fillTick(1, 1.0));
		CentrifugeDispatchScope.reset();
		assertEquals(256, fillTick(1, 1.0));
	}

	@Test
	void unsetClockDoesNotCreatePermanentExhaustion() {
		for (int i = 0; i < 20_000; i++) {
			assertFalse(CentrifugeDispatchScope.externalPushOverBudget(ServerTickClock.UNSET, 1.0));
		}
		assertEquals(256, fillTick(1, 1.0));
	}

	private static int fillTick(long tick, double factor) {
		int accepted = 0;
		while (!CentrifugeDispatchScope.externalPushOverBudget(tick, factor)) {
			if (++accepted > CentrifugeDispatchScope.MAX_BUDGET) fail("Unbounded dispatch");
		}
		return accepted;
	}
}
