package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** AE 输入拉取的无状态公平性与节流公式。 */
final class Ae2PullFairnessPolicy {

	private Ae2PullFairnessPolicy() {
	}

	static int effectiveInterval(int configuredInterval, int accelerationMultiplier) {
		int interval = Math.max(1, configuredInterval);
		int multiplier = Math.max(1, accelerationMultiplier);
		return Math.max(1, (interval + multiplier - 1) / multiplier);
	}

	/** 首次拉取按方块坐标稳定错峰，避免大量机器在同一冷却边界同步扫描网络。 */
	static boolean isInitialPhaseReady(long pullCounter, int interval, long positionSeed) {
		int safeInterval = Math.max(1, interval);
		if (safeInterval == 1) return true;
		return Math.floorMod(pullCounter, safeInterval) == initialPhase(safeInterval, positionSeed);
	}

	static int initialPhase(int interval, long positionSeed) {
		int safeInterval = Math.max(1, interval);
		long mixed = positionSeed;
		mixed ^= mixed >>> 33;
		mixed *= 0xff51afd7ed558ccdl;
		mixed ^= mixed >>> 33;
		mixed *= 0xc4ceb9fe1a85ec53l;
		mixed ^= mixed >>> 33;
		return (int) Math.floorMod(mixed, safeInterval);
	}

	static int resolveAccelerationMultiplier(int executedBatchMultiplier,
			int currentTrackerMultiplier, int previousTrackerMultiplier) {
		if (executedBatchMultiplier > 0) {
			return executedBatchMultiplier;
		}
		return Math.max(1, Math.max(currentTrackerMultiplier, previousTrackerMultiplier));
	}

	static long perSlotQuota(long configuredRate, int accelerationMultiplier, int processCount) {
		long rate = Math.max(1L, configuredRate);
		long processes = Math.max(1, processCount);
		long fairShare = (rate + processes - 1L) / processes;
		long multiplier = Math.max(1, accelerationMultiplier);
		long acceleratedShare = saturatedMultiply(fairShare, multiplier);
		long acceleratedRate = saturatedMultiply(rate, multiplier);
		return Math.min(acceleratedRate, Math.max(64L, acceleratedShare));
	}

	private static long saturatedMultiply(long left, long right) {
		if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
		return left * right;
	}
}
