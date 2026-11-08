package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.util.RandomSource;

/** 将 Wanna Bee 升级轮次分配到一次战利品表结果中的候选项。 */
final class WannaBeeLootSelection {

	private WannaBeeLootSelection() {
	}

	/** 小批次逐次选择，大批次用条件二项分布分配；重复候选项保留各自权重。 */
	static long[] sampleCounts(RandomSource random, long rolls, int candidateCount) {
		long[] counts = new long[Math.max(0, candidateCount)];
		if (random == null || rolls <= 0 || candidateCount <= 0) return counts;
		if (rolls <= 1_024) {
			for (long i = 0; i < rolls; i++) counts[random.nextInt(candidateCount)]++;
			return counts;
		}
		long remaining = rolls;
		for (int i = 0; i < candidateCount - 1; i++) {
			counts[i] = ExactBinomialSampler.sample(random, remaining, 1.0 / (candidateCount - i));
			remaining -= counts[i];
		}
		counts[candidateCount - 1] = remaining;
		return counts;
	}
}
