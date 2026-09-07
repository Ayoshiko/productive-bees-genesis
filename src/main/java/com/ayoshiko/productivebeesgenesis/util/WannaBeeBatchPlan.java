package com.ayoshiko.productivebeesgenesis.util;

/** Wanna Bee 动态战利品的有界分层采样计划。 */
final class WannaBeeBatchPlan {

	/**
	 * 单次批处理最多执行的独立战利品表采样次数。
	 * <br/>
	 * Wanna Bee 的每次采样都会进入 NeoForge LootModifier 链；高倍率蜂箱若执行 128 次，
	 * 会把昂贵的全局战利品条件集中到同一个 tick。16 次仍保留小批次的逐次精确采样，
	 * 大批次通过权重保持总产出次数不变，并将主线程尖峰压缩到可控范围。
	 */
	private static final int MAX_INDEPENDENT_SAMPLES = 16;

	private WannaBeeBatchPlan() {
	}

	static int sampleCount(int productionCount) {
		return Math.min(Math.max(0, productionCount), MAX_INDEPENDENT_SAMPLES);
	}

	static int weightAt(int productionCount, int sampleIndex) {
		return weightAt(productionCount, sampleCount(productionCount), sampleIndex);
	}

	static int weightAt(int productionCount, int samples, int sampleIndex) {
		if (productionCount <= 0 || samples <= 0 || sampleIndex < 0 || sampleIndex >= samples) return 0;
		return productionCount / samples + (sampleIndex < productionCount % samples ? 1 : 0);
	}

	/** 按生产力等级的轮数比例分配本组的独立采样预算。 */
	static int[] allocateSampleCounts(int[] rollCounts) {
		if (rollCounts == null || rollCounts.length == 0) return new int[0];
		int[] samples = new int[rollCounts.length];
		long totalRolls = 0L;
		for (int rollCount : rollCounts) {
			if (rollCount > 0) totalRolls += rollCount;
		}
		if (totalRolls <= 0L) return samples;
		if (totalRolls <= MAX_INDEPENDENT_SAMPLES) {
			for (int i = 0; i < rollCounts.length; i++) samples[i] = Math.max(0, rollCounts[i]);
			return samples;
		}

		int remaining = MAX_INDEPENDENT_SAMPLES;
		for (int i = 0; i < rollCounts.length; i++) {
			if (rollCounts[i] > 0) {
				samples[i] = 1;
				remaining--;
			}
		}
		while (remaining-- > 0) {
			int best = -1;
			for (int i = 0; i < rollCounts.length; i++) {
				if (rollCounts[i] <= samples[i]) continue;
				if (best < 0 || (long) rollCounts[i] * (samples[best] + 1L)
						> (long) rollCounts[best] * (samples[i] + 1L)) best = i;
			}
			if (best < 0) break;
			samples[best]++;
		}
		return samples;
	}
}
