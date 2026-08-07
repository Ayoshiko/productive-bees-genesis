package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2PushBackoffTest {

	@Test
	void failuresUseProgressiveWindowsAndSuccessResetsImmediately() {
		Ae2PushBackoff backoff = new Ae2PushBackoff();
		long start = 10_000L;

		backoff.recordFailure(start);
		assertTrue(backoff.shouldSkip(start + 49_999_999L));
		assertFalse(backoff.shouldSkip(start + 50_000_000L));

		long secondFailure = start + 50_000_000L;
		backoff.recordFailure(secondFailure);
		assertTrue(backoff.shouldSkip(secondFailure + 99_999_999L));
		assertFalse(backoff.shouldSkip(secondFailure + 100_000_000L));

		backoff.recordSuccess();
		assertFalse(backoff.shouldSkip(secondFailure));

		backoff.recordFailure(secondFailure);
		assertTrue(backoff.shouldSkip(secondFailure + 49_999_999L));
		assertFalse(backoff.shouldSkip(secondFailure + 50_000_000L));
	}

	@Test
	void aggressiveFailureDoesNotJumpToLongBackoff() {
		Ae2PushBackoff backoff = new Ae2PushBackoff();
		long start = 20_000L;

		backoff.recordFailureAggressive(start);
		assertTrue(backoff.shouldSkip(start + 49_999_999L));
		assertFalse(backoff.shouldSkip(start + 50_000_000L));
	}
}
