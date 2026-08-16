package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2KeyBackoffRegistryTest {

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
