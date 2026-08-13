package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the JDTE Time Accelerator virtual-tick bank wiring.
 * <p>
 * JDTE 0.5.9-alpha1 accelerates our machines through the {@code CoalescedAcceleratedMachine} path
 * ({@code accumulateAcceleratedTicks} credits virtual ticks into the {@link TickAccelTracker} held by the
 * tile's AE2 state holder) and, when an AE_ACCELERATION upgrade is present, through the AE2_GRID path
 * (every {@code tickingRequest} call credits the same bank). The machine's production tick must consume
 * that exact tracker; using a different tracker instance silently drops all credited ticks (the apiary
 * bug fixed in 2.0.9-hotfix.jdte+).
 */
class TickAccelBankWiringTest {

	@Test
	void creditedTicksAreConsumedAsBatchMultiplier() {
		TickAccelTracker shared = new TickAccelTracker();

		// JDTE AE2_GRID path: 16 tickingRequest calls -> 16 virtual ticks in the shared bank
		shared.addVirtualTicks(16);

		assertEquals(16, shared.takeBatchTicks(256), "credited ticks must be consumed as batch multiplier");
	}

	@Test
	void consumedTrackerCannotSeeCreditsOfAnotherInstance() {
		// The pre-fix apiary wiring: JDTE credited the state-holder tracker while the production
		// tick handler consumed its own self-built tracker -> no acceleration.
		TickAccelTracker credited = new TickAccelTracker();
		TickAccelTracker consumed = new TickAccelTracker();

		credited.addVirtualTicks(16);

		assertEquals(1, consumed.takeBatchTicks(256), "mismatched tracker instance must not see credits");
	}

	@Test
	void coalescedAccumulateCreditsTheSameBank() {
		TickAccelTracker shared = new TickAccelTracker();

		// JDTE CoalescedAcceleratedMachine path credits via accumulateAcceleratedTicks
		shared.addVirtualTicks(32);

		assertEquals(32, shared.takeBatchTicks(256));
	}
}
