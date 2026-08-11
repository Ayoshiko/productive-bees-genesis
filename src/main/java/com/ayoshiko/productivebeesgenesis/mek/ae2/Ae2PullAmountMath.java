package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** Pure amount math for configured and live-stock AE input entries. */
final class Ae2PullAmountMath {

	private Ae2PullAmountMath() {
	}

	static long addConfigured(long current, long amount) {
		long safeCurrent = Math.max(0L, current);
		long safeAmount = Math.max(0L, amount);
		return safeCurrent > Long.MAX_VALUE - safeAmount ? Long.MAX_VALUE : safeCurrent + safeAmount;
	}

	static long effectiveLimit(long configured, long visibleStock, boolean networkStock,
			long globalCap) {
		if (networkStock) {
			// Unlimited entries pull the full visible stock, ignoring the configured
			// global per-pull cap (AE2LT overloaded-interface parity).
			return Math.max(0L, visibleStock);
		}
		return Math.max(0L, configured);
	}
}
