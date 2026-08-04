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
}
