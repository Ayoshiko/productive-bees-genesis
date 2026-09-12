package com.ayoshiko.productivebeesgenesis.util;

/** Wanna Bee 动态战利品的有界分层事件采样计划。 */
final class WannaBeeBatchPlan {

	/**
	 * 单次批处理最多执行的独立生产事件样本数。
	 * <br/>
	 * Productive Bees 的一次生产事件只执行一次实体战利品表，随后从本次结果池抽取升级轮次。
	 * 小批次保持每事件一次表执行；大批次通过代表事件权重限制 LootModifier 链的调用上限。
	 */
	private static final int MAX_INDEPENDENT_EVENTS = 16;

	private WannaBeeBatchPlan() {
	}

	static int sampleCount(int productionCount) {
		return Math.min(Math.max(0, productionCount), MAX_INDEPENDENT_EVENTS);
	}

	static int weightAt(int productionCount, int sampleIndex) {
		return weightAt(productionCount, sampleCount(productionCount), sampleIndex);
	}

	static int weightAt(int productionCount, int samples, int sampleIndex) {
		if (productionCount <= 0 || samples <= 0 || sampleIndex < 0 || sampleIndex >= samples) return 0;
		return productionCount / samples + (sampleIndex < productionCount % samples ? 1 : 0);
	}

	/** 按生产力等级的生产事件数比例分配本组的独立事件采样预算。 */
	static int[] allocateSampleCounts(int[] productionCounts) {
		if (productionCounts == null || productionCounts.length == 0) return new int[0];
		int[] samples = new int[productionCounts.length];
		long totalEvents = 0L;
		for (int productionCount : productionCounts) {
			if (productionCount > 0) totalEvents += productionCount;
		}
		if (totalEvents <= 0L) return samples;
		if (totalEvents <= MAX_INDEPENDENT_EVENTS) {
			for (int i = 0; i < productionCounts.length; i++) {
				samples[i] = Math.max(0, productionCounts[i]);
			}
			return samples;
		}

		int remaining = MAX_INDEPENDENT_EVENTS;
		for (int i = 0; i < productionCounts.length; i++) {
			if (productionCounts[i] > 0) {
				samples[i] = 1;
				remaining--;
			}
		}
		while (remaining-- > 0) {
			int best = -1;
			for (int i = 0; i < productionCounts.length; i++) {
				if (productionCounts[i] <= samples[i]) continue;
				if (best < 0 || (long) productionCounts[i] * (samples[best] + 1L)
						> (long) productionCounts[best] * (samples[i] + 1L)) best = i;
			}
			if (best < 0) break;
			samples[best]++;
		}
		return samples;
	}
}
