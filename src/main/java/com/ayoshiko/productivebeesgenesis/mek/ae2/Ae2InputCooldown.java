package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
	 * Per-tile input pull scheduler mirroring AE2LT's OverloadedInterface
	 * {@code CooldownTracker}: successful pulls shorten the next interval
	 * (1 tick while an unlimited entry is active, 5 otherwise) while failures
	 * back off (linear in unlimited mode, halved in normal mode) to avoid
	 * hammering the AE2 grid when there is nothing to pull.
	 * <p>
	 * Pure server-tick logic: the puller advances this cooldown with its own
	 * call counter so acceleration mods that invoke ticks multiple times per
	 * game tick still converge quickly.
	 */
final class Ae2InputCooldown {

	static final int NORMAL_SUCCESS_TICKS = 5;
	static final int UNLIMITED_SUCCESS_TICKS = 1;
	static final int MAX_BACKOFF_TICKS = 40;

	private int cooldownN = NORMAL_SUCCESS_TICKS;

	int current() {
		return cooldownN;
	}

	void onSuccess(boolean unlimited) {
		cooldownN = unlimited ? UNLIMITED_SUCCESS_TICKS : NORMAL_SUCCESS_TICKS;
	}

	/**
	 * Supply-aware success (AE2LT {@code CooldownTracker} / {@code KeyModel} parity):
	 * when a normal pull could not reach its per-tick quota the network is
	 * replenishing slowly, so lengthen the next interval to avoid useless scans;
	 * unlimited pulls always stay at 1 tick.
	 */
	void onSuccess(boolean unlimited, long pulledAmount, long expectedQuota) {
		if (unlimited) {
			cooldownN = UNLIMITED_SUCCESS_TICKS;
			return;
		}
		if (expectedQuota > 0L && pulledAmount < expectedQuota) {
			cooldownN = Math.min(MAX_BACKOFF_TICKS, NORMAL_SUCCESS_TICKS + 5);
		} else {
			cooldownN = NORMAL_SUCCESS_TICKS;
		}
	}

	void onFail(boolean unlimited) {
		if (unlimited) {
			cooldownN = Math.min(cooldownN + 1, MAX_BACKOFF_TICKS);
		} else {
			cooldownN = Math.max(NORMAL_SUCCESS_TICKS, cooldownN / 2);
		}
	}

	void reset() {
		cooldownN = NORMAL_SUCCESS_TICKS;
	}
}
