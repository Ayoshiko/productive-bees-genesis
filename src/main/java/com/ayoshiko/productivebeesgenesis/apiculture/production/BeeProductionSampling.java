package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.mek.SampleUniformSum;
import com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** 单个采样段的 long 数量计算；任意精度总轮数与分段预算由调用方持有。 */
public final class BeeProductionSampling {

	/** 与正式维护蜂箱共用二项概率及逐栈基因公式；固定数量不消耗随机状态。 */
	public static long sampleOutput(RandomGenerator random, long rolls, int minimum, int maximum,
			float chance, float stabilityBonus, int productivityLevel) {
		Objects.requireNonNull(random);
		if (rolls < 0) throw new IllegalArgumentException("Negative bee rolls");
		if (rolls == 0 || Float.isNaN(chance) || chance <= 0) return 0;
		float stability = 0.0F;
		if (Float.isFinite(stabilityBonus)) {
			stability = Math.max(0.0F, stabilityBonus);
		} else if (stabilityBonus > 0.0F) {
			stability = 1.0F;
		}
		float adjustedChance = chance >= 1 ? 1 : Math.min(1, chance + stability);
		long successes = ExactBinomialSampler.sample(random, rolls, adjustedChance);
		int min = Math.max(0, minimum);
		int max = Math.max(maximum, min);
		return sampleGeneAdjustedSum(random, min, max, successes, productivityLevel);
	}

	/** PB 对每个原始栈取整；不能先汇总数量再应用基因，也不能平均不同基因。 */
	public static int adjustStackCount(int baseCount, int productivityLevel) {
		if (baseCount <= 0) return 0;
		int level = Math.max(0, Math.min(3, productivityLevel));
		if (level == 0) return baseCount;
		long adjusted = baseCount == 1 ? 1L + level
				: (long) baseCount + Math.round((1.0F / (level + 2.0F) + (level + 1.0F) / 2.0F) * baseCount);
		return (int) Math.min(Integer.MAX_VALUE, adjusted);
	}

	/** 二项分配递归拆分均匀区间，非线性基因结果只由合法逐栈数量组成。 */
	public static long sampleGeneAdjustedSum(RandomGenerator random, int min, int max,
			long samples, int productivityLevel) {
		Objects.requireNonNull(random);
		if (samples <= 0 || min < 0 || max < min) return 0;
		int level = Math.max(0, Math.min(3, productivityLevel));
		if (min == max) {
			return Math.multiplyExact(adjustStackCount(min, level), samples);
		}
		if (level == 0) {
			Math.multiplyExact((long) max, samples);
			return SampleUniformSum.sample(random, min, max, samples, 1);
		}
		int lower = adjustStackCount(min, level);
		int upper = adjustStackCount(max, level);
		if (lower == upper) return Math.multiplyExact((long) lower, samples);
		if (samples <= 32) {
			long sum = 0;
			for (long i = 0; i < samples; i++) {
				int sampled = SampleUniformSum.sampleSingle(random, min, max);
				sum = Math.addExact(sum, adjustStackCount(sampled, level));
			}
			return sum;
		}
		int middle = min + (int) (((long) max - min) / 2);
		double leftProbability = ((long) middle - min + 1.0) / ((long) max - min + 1.0);
		long leftCount = ExactBinomialSampler.sample(random, samples, leftProbability);
		return Math.addExact(
				sampleGeneAdjustedSum(random, min, middle, leftCount, level),
				sampleGeneAdjustedSum(random, middle + 1, max, samples - leftCount, level));
	}

	private BeeProductionSampling() { }
}
