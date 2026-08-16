package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * Schedules buffered fluid inserts without linking the decision to AE2 API classes.
 * <p>
 * When direct AE output is disabled, new fluid first occupies a local Mekanism tank.
 * Waiting for the normal 20-tick buffer window makes that tank the throughput limit, so
 * this path flushes on every real game tick. The caller still coalesces accelerator
 * sub-ticks before reaching this policy.
 */
final class Ae2FluidFlushPolicy {

	/** Large direct-output buffers flush before their regular batch window expires. */
	private static final long SATURATION_ACCUMULATE_THRESHOLD_MB = 250_000L;

	private Ae2FluidFlushPolicy() {}

	static boolean shouldFlush(boolean directAeOutputEnabled, boolean batchWindowRipe,
			long pendingAmount, long totalInTanks) {
		if (!directAeOutputEnabled) return true;
		long adaptiveThreshold = Math.max(SATURATION_ACCUMULATE_THRESHOLD_MB, totalInTanks);
		return batchWindowRipe || pendingAmount >= adaptiveThreshold;
	}
}
