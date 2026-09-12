package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.util.RandomSource;

/** 将 Wanna Bee 升级轮次分配到一次战利品表结果中的候选项。 */
final class WannaBeeLootSelection {

	private WannaBeeLootSelection() {
	}

	/**
	 * 对 {@code rolls} 次均匀选择做索引计数，候选列表中的重复项仍保留各自权重。
	 * <br/>
	 * 该循环不再执行战利品表或 LootModifier，只推进 PB 原版已有的列表随机选择；
	 * 始终使用世界随机源逐次抽取，保持准确的多项分布与原版随机序列语义。
	 */
	static int[] sampleCounts(RandomSource random, int rolls, int candidateCount) {
		int[] counts = new int[Math.max(0, candidateCount)];
		if (random == null || rolls <= 0 || candidateCount <= 0) return counts;
		if (candidateCount == 1) {
			counts[0] = rolls;
			return counts;
		}
		for (int i = 0; i < rolls; i++) counts[random.nextInt(candidateCount)]++;
		return counts;
	}
}
