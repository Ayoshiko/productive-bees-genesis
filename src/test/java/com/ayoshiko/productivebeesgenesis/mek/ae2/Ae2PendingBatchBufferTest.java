package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2PendingBatchBufferTest {

	@Test
	void windowMaturesAtConfiguredIntervalAndResetDefersNextScan() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();

		for (int tick = 1; tick < Ae2PendingBatchBuffer.RIPE_TICKS; tick++) {
			buffer.tick();
			assertFalse(buffer.isWindowRipe());
		}

		buffer.tick();
		assertTrue(buffer.isWindowRipe());

		buffer.reset();
		assertFalse(buffer.isWindowRipe());
	}

	@Test
	void localTankDrainFlushesImmediatelyWhenDirectAeOutputIsDisabled() {
		assertTrue(Ae2FluidFlushPolicy.shouldFlush(false, false, 1_000L, 1_000L));
		assertFalse(Ae2FluidFlushPolicy.shouldFlush(true, false, 1_000L, 1_000L));
		assertTrue(Ae2FluidFlushPolicy.shouldFlush(true, true, 1_000L, 1_000L));
	}
}
