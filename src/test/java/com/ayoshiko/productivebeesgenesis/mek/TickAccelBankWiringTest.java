package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the JDTE Time Accelerator virtual-tick bank wiring.
 * <p>
 * JDTE 0.5.9-alpha1 accelerates our machines through the {@code CoalescedAcceleratedMachine} path
 * ({@code accumulateAcceleratedTicks} credits virtual ticks into the {@link TickAccelTracker} held by the
 * tile's AE2 state holder). The machine's production tick must consume that exact tracker; using a different
 * tracker instance silently drops all credited ticks (the apiary bug fixed before the stable release).
 */
class TickAccelBankWiringTest {

	@Test
	void creditedTicksAreConsumedAsBatchMultiplier() {
		TickAccelTracker shared = new TickAccelTracker();

		// JDTE coalesced path: one or more scheduler batches credit the shared bank.
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

	@Test
	void unacceleratedTickUsesFastPathAndClearsItsSingleCredit() {
		TickAccelTracker tracker = new TickAccelTracker();
		tracker.addVirtualTicks(1);

		assertEquals(1, tracker.takeBatchTicksForGameTick(0L));
		assertEquals(0L, tracker.getPendingVirtualTicks());
	}

	@Test
	void defaultBatchCeilingIs1024() {
		assertEquals(1024, TickAccelTracker.getMaxBatchTicks());
	}

	@Test
	void jdte1024BatchConsumesOnlyOneBoundedChunkPerGameTick() {
		TickAccelTracker tracker = new TickAccelTracker();
		tracker.addVirtualTicks(4_096);

		assertEquals(1_024, tracker.takeBatchTicks(TickAccelTracker.getMaxBatchTicks()));
		assertEquals(3_072L, tracker.getPendingVirtualTicks());
	}

	@Test
	void jdt256RepeatedTickerCallsRunThePipelineOnce() {
		TickBatchSkipState state = new TickBatchSkipState();

		assertTrue(state.tryBeginGameTick(100L));
		for (int call = 1; call < 256; call++) {
			assertFalse(state.tryBeginGameTick(100L));
		}
		assertTrue(state.tryBeginGameTick(101L));
	}

	@Test
	void gameTickGateReportsTheSelectedProcessor() {
		TickBatchSkipState state = new TickBatchSkipState();

		assertFalse(state.wasHandledForGameTick(42L));
		assertTrue(state.tryBeginGameTick(42L));
		assertTrue(state.wasHandledForGameTick(42L));
		assertFalse(state.tryBeginGameTick(42L));
	}

	@Test
	void refreshIntervalHandlesSentinelAndGameTimeRollback() {
		assertTrue(TickAccelTracker.isIntervalElapsed(0L, Long.MIN_VALUE, 100L));
		assertFalse(TickAccelTracker.isIntervalElapsed(150L, 100L, 100L));
		assertTrue(TickAccelTracker.isIntervalElapsed(200L, 100L, 100L));
		assertTrue(TickAccelTracker.isIntervalElapsed(10L, 1_000L, 100L));
	}
}
