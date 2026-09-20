package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.mek.BatchProbabilitySampler;
import com.ayoshiko.productivebeesgenesis.mek.SampleUniformSum;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** 离心数量内核；不持有物品栈、库存、世界或全局随机状态。 */
public final class CentrifugeProductionSampling {

	/**
	 * 返回应用生产力倍率前的数量。int 轮数 × int 单次数量始终可由 long 精确保存。
	 * 单轮保留 nextFloat 调用顺序；多轮沿用独立离心机的保底和数量和近似，非原始二项分布。
	 */
	public static long sampleOutput(RandomGenerator random, int rolls, int minimum, int maximum,
			float chance, double stabilityBonus) {
		Objects.requireNonNull(random);
		if (rolls < 0) throw new IllegalArgumentException("Negative centrifuge rolls");
		if (rolls == 0) return 0;
		int min = Math.max(0, minimum), max = Math.max(min, maximum);
		float adjusted = (float) adjustedChance(chance, stabilityBonus);
		if (rolls == 1) {
			if (adjusted < 1.0F && random.nextFloat() >= adjusted) return 0;
			return SampleUniformSum.sampleSingle(random, min, max);
		}
		long successes = adjusted >= 1.0F ? rolls
				: BatchProbabilitySampler.sampleBinomialWithGuarantee(random, rolls, adjusted);
		return SampleUniformSum.sample(random, min, max, successes, 1);
	}

	public static double adjustedChance(float chance, double stabilityBonus) {
		if (Float.isNaN(chance)) return 0.0D;
		return Math.max(0.0D, Math.min(1.0D, chance + stabilityBonus));
	}

	private CentrifugeProductionSampling() { }
}
