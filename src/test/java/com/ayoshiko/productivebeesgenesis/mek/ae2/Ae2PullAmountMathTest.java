package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Ae2PullAmountMathTest {

	@Test
	void configuredAmountsSaturate() {
		assertEquals(80L, Ae2PullAmountMath.addConfigured(64L, 16L));
		assertEquals(Long.MAX_VALUE, Ae2PullAmountMath.addConfigured(Long.MAX_VALUE - 3L, 64L));
		assertEquals(0L, Ae2PullAmountMath.addConfigured(-10L, -20L));
	}

	@Test
	void networkStockRespectsConfiguredRequestAndUnlimitedUsesVisibleStock() {
		assertEquals(64L, Ae2PullAmountMath.effectiveLimit(64L, 4096L, false, 16_384L));
		assertEquals(64L, Ae2PullAmountMath.effectiveLimit(64L, 4096L, true, 16_384L));
		assertEquals(64L, Ae2PullAmountMath.effectiveLimit(64L, 100_000L, true, 1_000L));
		assertEquals(0L, Ae2PullAmountMath.effectiveLimit(64L, -1L, true, 16_384L));
	}

	@Test
	void networkStockReserveOnlyPullsAmountAboveFloor() {
		assertEquals(64L, Ae2PullAmountMath.effectiveLimit(64L, 1_000L, true, 16_384L, 300L));
		assertEquals(0L, Ae2PullAmountMath.effectiveLimit(64L, 300L, true, 16_384L, 300L));
		assertEquals(0L, Ae2PullAmountMath.effectiveLimit(64L, 100L, true, 16_384L, 300L));
		assertEquals(700L, Ae2PullAmountMath.effectiveLimit(64L, 1_000L, true, true, 16_384L, 300L));
	}

	@Test
	void globalReserveWorksForUnmarkedCandidatesAndUnlimitedPull() {
		assertEquals(700L, Ae2PullAmountMath.effectiveLimit(Long.MAX_VALUE, 1_000L,
				true, true, Long.MAX_VALUE, 300L));
		assertEquals(0L, Ae2PullAmountMath.effectiveLimit(Long.MAX_VALUE, 300L,
				true, true, Long.MAX_VALUE, 300L));
	}
}
