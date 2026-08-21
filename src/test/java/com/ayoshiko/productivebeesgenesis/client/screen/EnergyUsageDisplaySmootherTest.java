package com.ayoshiko.productivebeesgenesis.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EnergyUsageDisplaySmootherTest {

	@Test
	void rapidAlternationKeepsLastStableDisplay() {
		AtomicLong sample = new AtomicLong(30_720_000L);
		AtomicLong now = new AtomicLong();
		EnergyUsageDisplaySmoother smoother = new EnergyUsageDisplaySmoother(
				sample::get, now::get, 750L);

		assertEquals(30_720_000L, smoother.getAsLong());
		for (int i = 0; i < 20; i++) {
			sample.set((i & 1) == 0 ? 18_720_000L : 30_720_000L);
			now.addAndGet(100L);
			assertEquals(30_720_000L, smoother.getAsLong());
		}
	}

	@Test
	void stableValueCommitsAfterHoldWindow() {
		AtomicLong sample = new AtomicLong(30_720_000L);
		AtomicLong now = new AtomicLong();
		EnergyUsageDisplaySmoother smoother = new EnergyUsageDisplaySmoother(
				sample::get, now::get, 750L);

		assertEquals(30_720_000L, smoother.getAsLong());
		sample.set(18_720_000L);
		assertEquals(30_720_000L, smoother.getAsLong());
		now.set(749L);
		assertEquals(30_720_000L, smoother.getAsLong());
		now.set(750L);
		assertEquals(18_720_000L, smoother.getAsLong());
	}
}