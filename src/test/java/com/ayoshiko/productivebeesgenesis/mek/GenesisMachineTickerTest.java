package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenesisMachineTickerTest {

	@ParameterizedTest
	@ValueSource(ints = {1, 256, 1024})
	void repeatedTicksConserveCreditsWithOneMaintenanceCallPerRealTick(int multiplier) {
		TickAccelTracker tracker = new TickAccelTracker();
		long consumed = 0;
		int maintenance = 0;
		for (long tick = 0; tick < 200; tick++) {
			for (int call = 0; call < multiplier; call++) {
				if (tracker.beginMachineTick(tick)) {
					maintenance++;
					tracker.onTick(tick);
					consumed += tracker.takeBatchTicks(1024);
				}
			}
			assertEquals(multiplier, tracker.getRawCallCount());
			assertEquals((tick + 1) * multiplier, consumed + tracker.getPendingVirtualTicks());
		}
		assertEquals(200, maintenance);
		tracker.beginMachineTick(200);
		tracker.onTick(200);
		consumed += tracker.takeBatchTicks(1024);
		assertEquals(200L * multiplier + 1, consumed);
		assertEquals(0, tracker.getPendingVirtualTicks());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void jdteFlushOrderDoesNotSuppressMaintenanceOrDoubleSpend(boolean flushFirst) {
		TickAccelTracker tracker = new TickAccelTracker();
		TickBatchSkipState processing = new TickBatchSkipState();
		long consumed = 0;
		for (long tick = 0; tick < 200; tick++) {
			tracker.addVirtualTicks(1024);
			if (flushFirst && processing.tryBeginGameTick(tick)) consumed += tracker.takeBatchTicks(1024);
			assertTrue(tracker.beginMachineTick(tick));
			tracker.onTick(tick);
			if (processing.tryBeginGameTick(tick)) consumed += tracker.takeBatchTicks(1024);
			if (!flushFirst && processing.tryBeginGameTick(tick)) consumed += tracker.takeBatchTicks(1024);
			assertEquals(1, tracker.getRawCallCount());
			assertEquals((tick + 1) * 1025, consumed + tracker.getPendingVirtualTicks());
		}
		assertEquals(200L * 1024, consumed);
		assertEquals(200, tracker.getPendingVirtualTicks());
	}

	@Test
	void resetRollbackAndDifferentMachinesHaveIndependentMaintenance() {
		TickAccelTracker first = new TickAccelTracker();
		TickAccelTracker second = new TickAccelTracker();
		assertTrue(first.beginMachineTick(100));
		first.onTick(100);
		assertFalse(first.beginMachineTick(100));
		assertTrue(second.beginMachineTick(100));
		assertTrue(first.beginMachineTick(1));
		first.reset();
		assertTrue(first.beginMachineTick(1));
		assertEquals(0, first.getPendingVirtualTicks());
	}
}
