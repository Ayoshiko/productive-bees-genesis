package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.mek.BatchProbabilitySampler;
import com.ayoshiko.productivebeesgenesis.mek.SampleUniformSum;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** 一个有界采样段的数量计算；无物品栈、世界、库存或全局随机状态。 */
public final class BeeProductionSampling {

	/** 保留独立蜂箱的概率模式：小二项精确，大二项和数量和沿用现有近似。 */
	public static long sampleOutput(RandomGenerator random, int rolls, int minimum, int maximum,
			float chance, float stabilityBonus, int productivityLevel) {
		Objects.requireNonNull(random);
		if (rolls < 0) throw new IllegalArgumentException("Negative bee rolls");
		if (rolls == 0 || Float.isNaN(chance) || chance <= 0) return 0;
		float stability = Float.isFinite(stabilityBonus) ? Math.max(0, stabilityBonus) : stabilityBonus > 0 ? 1 : 0;
		float adjustedChance = chance >= 1 ? 1 : Math.min(1, chance + stability);
		long successes;
		if (rolls == 1) successes = adjustedChance < 1 && random.nextFloat() >= adjustedChance ? 0 : 1;
		else successes = adjustedChance >= 1 ? rolls : BatchProbabilitySampler.sampleBinomial(random, rolls, adjustedChance);
		int min = Math.max(0, minimum), max = Math.max(maximum, min);
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

	public static long sampleGeneAdjustedSum(RandomGenerator random, int min, int max,
			long samples, int productivityLevel) {
		Objects.requireNonNull(random);
		if (samples > Integer.MAX_VALUE) throw new IllegalArgumentException("Split bee sampling into bounded work segments");
		if (samples <= 0 || min < 0 || max < min) return 0;
		int level = Math.max(0, Math.min(3, productivityLevel));
		if (level == 0) return SampleUniformSum.sample(random, min, max, samples, 1);
		if (samples <= 32) {
			long sum = 0;
			for (long i = 0; i < samples; i++) sum += adjustStackCount(SampleUniformSum.sampleSingle(random, min, max), level);
			return sum;
		}
		if (min == max) return (long) adjustStackCount(min, level) * samples;
		long range = (long) max - min + 1;
		if (range > 4096) {
			long baseSum = SampleUniformSum.sample(random, min, max, samples, 1);
			double baseMean = ((double) min + max) / 2;
			double ratio = baseMean <= 0 ? 1 : adjustStackCount((int) Math.round(baseMean), level) / baseMean;
			return SaturatingMath.saturatingRoundToLong(baseSum * ratio);
		}
		double mean = 0, squares = 0;
		for (long raw = min; raw <= max; raw++) {
			int adjusted = adjustStackCount((int) raw, level);
			mean += adjusted; squares += (double) adjusted * adjusted;
		}
		mean /= range;
		double variance = Math.max(0, squares / range - mean * mean);
		double value = mean * samples + random.nextGaussian() * Math.sqrt(variance * samples);
		long lower = (long) adjustStackCount(min, level) * samples;
		long upper = (long) adjustStackCount(max, level) * samples;
		return Math.max(lower, Math.min(upper, SaturatingMath.saturatingRoundToLong(value)));
	}

	private BeeProductionSampling() { }
}
