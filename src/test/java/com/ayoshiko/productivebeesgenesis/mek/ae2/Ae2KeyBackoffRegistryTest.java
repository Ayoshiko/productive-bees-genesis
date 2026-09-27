package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2KeyBackoffRegistryTest {

	@Test
	void distinctFailuresCannotGrowWithoutBoundAndExpiredKeysAreReleased() {
		Ae2KeyBackoffRegistry<Integer> registry = new Ae2KeyBackoffRegistry<>();
		long now = 100_000L;
		for (int i = 0; i < 10_000; i++) {
			registry.recordFailure(i, now);
			assertTrue(registry.shouldSkip(i, now));
			assertTrue(registry.size() <= Ae2KeyBackoffRegistry.MAX_ENTRIES);
		}
		assertEquals(Ae2KeyBackoffRegistry.MAX_ENTRIES, registry.size());
		registry.recordFailure(10_000, now + 60_000_000_000L);
		assertEquals(1, registry.size());
		registry.clear();
		assertEquals(0, registry.size());
	}

	@Test
	void failureParksOnlyTheRejectedKey() {
		Ae2KeyBackoffRegistry<String> registry = new Ae2KeyBackoffRegistry<>();
		long now = 50_000L;
		registry.recordFailure("rejected", now);

		assertTrue(registry.shouldSkip("rejected", now));
		assertFalse(registry.shouldSkip("unrelated", now));

		registry.recordSuccess("rejected");
		assertFalse(registry.shouldSkip("rejected", now));
	}

	@Test
	void clearWakesAllKeysAfterNetworkChange() {
		Ae2KeyBackoffRegistry<String> registry = new Ae2KeyBackoffRegistry<>();
		long now = 75_000L;
		registry.recordFailure("blocked", now);
		assertTrue(registry.shouldSkip("blocked", now));

		registry.clear();
		assertFalse(registry.shouldSkip("blocked", now));
	}
}
