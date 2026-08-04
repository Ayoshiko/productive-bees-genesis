package com.ayoshiko.productivebeesgenesis.util;

/** Wanna Bee 动态战利品的有界分层采样计划。 */
final class WannaBeeBatchPlan {

	private static final int MAX_INDEPENDENT_SAMPLES = 128;

	private WannaBeeBatchPlan() {
	}

	static int sampleCount(int productionCount) {
		return Math.min(Math.max(0, productionCount), MAX_INDEPENDENT_SAMPLES);
	}

	static int weightAt(int productionCount, int sampleIndex) {
		int samples = sampleCount(productionCount);
		if (samples == 0 || sampleIndex < 0 || sampleIndex >= samples) return 0;
		return productionCount / samples + (sampleIndex < productionCount % samples ? 1 : 0);
	}
}
