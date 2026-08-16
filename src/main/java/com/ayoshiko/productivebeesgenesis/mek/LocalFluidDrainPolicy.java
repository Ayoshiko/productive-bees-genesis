package com.ayoshiko.productivebeesgenesis.mek;

/**
 * Decides when a local fluid buffer needs an in-batch drain.
 * <p>
 * Normal output is drained once at the end of the real game tick. High-parallel processing
 * only needs an extra drain when the remaining tank capacity cannot accept another candidate
 * batch; this keeps accelerated low-volume processing off the AE2 hot path.
 */
final class LocalFluidDrainPolicy {

	private LocalFluidDrainPolicy() {}

	static boolean shouldDrainAfterCommit(long remainingCapacity, long nextCandidateBatchAmount) {
		return remainingCapacity < Math.max(1L, nextCandidateBatchAmount);
	}
}
