package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Ae2InputFilterUnlimitedTest {

	@Test
	void unlimitedAllNeverBypassesBlacklistOrWhitelistAdmission() {
		assertEquals(Ae2FilterPullPolicy.PULL_DISALLOWED, decide(false, false));
		assertEquals(-1L, decide(true, false));
	}

	@Test
	void unlimitedAllRemovesExactEntryCapButKeepsStockReserve() {
		assertEquals(1_000L, decide(true, true));
		assertEquals(700L, Ae2FilterPullPolicy.effectiveLimit(true, true,
				64L, 1_000L, true, 300L, false, true, false, 0L, 8_192L));
		assertEquals(700L, Ae2FilterPullPolicy.effectiveLimit(true, false,
				64L, 1_000L, false, 0L, false, true, true, 300L, 8_192L));
	}

	private static long decide(boolean admitted, boolean directFound) {
		return Ae2FilterPullPolicy.effectiveLimit(admitted, directFound,
				64L, 1_000L, false, 0L, false, true, false, 0L, 8_192L);
	}
}
