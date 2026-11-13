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

	/** 少于总配额也可能只是本地容量不足，成功供料不额外延迟后续补货。 */
	void onSuccess(boolean unlimited, long pulledAmount, long expectedQuota) {
		onSuccess(unlimited);
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
