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
	void successfulExtractDelayUsesTheStorageHealthThresholdAndCannotOverflow() {
		assertEquals(0L, Ae2StorageHealth.successfulExtractDelay(0L));
		assertEquals(0L, Ae2StorageHealth.successfulExtractDelay(5_000_000L));
		assertEquals(10_000_002L, Ae2StorageHealth.successfulExtractDelay(5_000_001L));
		assertEquals(16_000_000L, Ae2StorageHealth.successfulExtractDelay(8_000_000L));
		assertEquals(1_000_000_000L, Ae2StorageHealth.successfulExtractDelay(Long.MAX_VALUE));
	}

	@Test
	void repeatedSuccessfulSlowExtractsResetTheRejectedKeyWindow() {
		Ae2KeyBackoffRegistry<String> registry = new Ae2KeyBackoffRegistry<>();
		long now = 1_000_000_000L;
		for (int i = 0; i < 6; i++) {
			registry.recordFailure("comb", now);
			now += 2_000_000_000L;
		}
		long delay = Ae2StorageHealth.successfulExtractDelay(6_000_000L);
		for (int i = 0; i < 20; i++) {
			registry.recordSuccess("comb", now, delay);
			assertTrue(registry.shouldSkip("comb", now + 11_999_999L));
			assertFalse(registry.shouldSkip("comb", now + 12_000_000L));
			now += 100_000_000L;
		}
		registry.recordFailure("comb", now);
		assertTrue(registry.shouldSkip("comb", now + 37_499_999L));
		assertFalse(registry.shouldSkip("comb", now + 62_500_000L));
		registry.recordSuccess("comb", now, Ae2StorageHealth.successfulExtractDelay(500_000L));
		assertFalse(registry.shouldSkip("comb", now));
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
