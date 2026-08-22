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
		return effectiveLimit(configured, visibleStock, networkStock, globalCap, 0L);
	}

	static long effectiveLimit(long configured, long visibleStock, boolean networkStock,
			long globalCap, long reserveAmount) {
		return effectiveLimit(configured, visibleStock, networkStock, false, globalCap, reserveAmount);
	}

	static long effectiveLimit(long configured, long visibleStock, boolean networkStock,
			boolean unlimitedPull, long globalCap, long reserveAmount) {
		if (networkStock) {
			// Stock mode always enforces the floor. Unlimited mode only removes the
			// per-pull cap; it must never bypass the configured reserve.
			long safeVisible = Math.max(0L, visibleStock);
			long safeReserve = Math.max(0L, reserveAmount);
			long aboveReserve = safeVisible <= safeReserve ? 0L : safeVisible - safeReserve;
			return unlimitedPull ? aboveReserve : Math.min(Math.max(0L, configured), aboveReserve);
		}
		if (unlimitedPull) return Math.max(0L, visibleStock);
		return Math.max(0L, configured);
	}
}
