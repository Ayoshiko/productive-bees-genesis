package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.SampleUniformSum;
import com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** 蜂箱批量概率采样；每个成功产物仍应用 PB 的逐栈生产力基因公式。 */
public final class BeeProduceBatchSampler {

	private BeeProduceBatchSampler() {
	}

	public static List<ItemStack> sample(Map<ItemStack, ChancedOutput> recipeOutputs,
			long batchCount, float multiplier, float stabilityBonus) {
		if (recipeOutputs == null || recipeOutputs.isEmpty() || batchCount <= 0) return List.of();
		List<ItemStack> result = new ArrayList<>(recipeOutputs.size());
		sampleInto(result, recipeOutputs, batchCount, multiplier, stabilityBonus, BeeProductivityGene.NORMAL);
		return result;
	}

	public static void sampleInto(List<ItemStack> output, Map<ItemStack, ChancedOutput> recipeOutputs,
			long batchCount, float multiplier, float stabilityBonus) {
		sampleInto(output, recipeOutputs, batchCount, multiplier, stabilityBonus, BeeProductivityGene.NORMAL);
	}

	public static void sampleInto(List<ItemStack> output, Map<ItemStack, ChancedOutput> recipeOutputs,
			long batchCount, float multiplier, float stabilityBonus, int productivityLevel) {
		if (output == null || recipeOutputs == null || recipeOutputs.isEmpty() || batchCount <= 0
				|| !Float.isFinite(multiplier) || multiplier <= 0.0f) return;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		long rollCount = sampleRollCount(random, batchCount, multiplier);
		if (rollCount <= 0) return;

		for (Map.Entry<ItemStack, ChancedOutput> entry : recipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			float chance = chanced.chance();
			if (Float.isNaN(chance) || chance <= 0.0f) continue;
			float safeStabilityBonus = Float.isFinite(stabilityBonus)
					? Math.max(0.0f, stabilityBonus)
					: (stabilityBonus > 0.0f ? 1.0f : 0.0f);
			float adjustedChance = chance >= 1.0f ? 1.0f : Math.min(1.0f, chance + safeStabilityBonus);
			if (adjustedChance <= 0.0f) continue;

			int min = Math.max(0, chanced.min());
			int max = Math.max(chanced.max(), min);
			if (max == 0) continue;
			ItemStackMergeHelper.checkBulkCount(output.size(),
					Math.multiplyExact(BeeProductivityGene.adjustCount(max, productivityLevel), rollCount));
			// PB 的 nextFloat <= chance 在 24 位均匀网格上包含阈值对应的端点。
			double probability = Math.min(1.0, (Math.floor((double) adjustedChance * 0x1.0p24) + 1) * 0x1.0p-24);
			long successes = ExactBinomialSampler.sample(random, rollCount, probability);
			long count = sampleGeneAdjustedSum(random, min, max, successes, productivityLevel);
			ItemStackMergeHelper.appendBulkCount(output, entry.getKey(), count);
		}
	}

	/** 喂食台产物先按生产事件确定升级后的栈大小，再应用 PB 基因公式。 */
	public static void sampleGuaranteedInto(List<ItemStack> output, ItemStack template,
			long batchCount, float multiplier) {
		sampleGuaranteedInto(output, template, batchCount, multiplier, BeeProductivityGene.NORMAL);
	}

	public static void sampleGuaranteedInto(List<ItemStack> output, ItemStack template,
			long batchCount, float multiplier, int productivityLevel) {
		if (output == null || template == null || template.isEmpty() || batchCount <= 0
				|| !Float.isFinite(multiplier) || multiplier <= 0.0f) return;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (multiplier >= Integer.MAX_VALUE) throw new ArithmeticException("Per-event bee multiplier exceeds int");
		int lowerRolls = (int) Math.floor(multiplier);
		double fraction = multiplier - lowerRolls;
		long upperEvents = ExactBinomialSampler.sample(random, batchCount, fraction);
		long lowerEvents = batchCount - upperEvents;
		long count = Math.multiplyExact(
				BeeProductivityGene.adjustCount(lowerRolls, productivityLevel), lowerEvents);
		if (upperEvents > 0) {
			count = Math.addExact(count, Math.multiplyExact(
					BeeProductivityGene.adjustCount(lowerRolls + 1, productivityLevel), upperEvents));
		}
		ItemStackMergeHelper.appendBulkCount(output, template, count);
	}

	/** 固定轮次与小数额外轮次均以 long 聚合；不能把已付生产次数截成 int。 */
	public static long sampleRollCount(RandomGenerator random, long batchCount, float multiplier) {
		if (random == null || batchCount <= 0 || !Float.isFinite(multiplier) || multiplier <= 0.0F) return 0;
		if (multiplier >= Long.MAX_VALUE) throw new ArithmeticException("Bee production rolls exceed long");
		long fixedRolls = (long) Math.floor(multiplier);
		long totalRolls = Math.multiplyExact(fixedRolls, batchCount);
		double fraction = multiplier - fixedRolls;
		return Math.addExact(totalRolls, ExactBinomialSampler.sample(random, batchCount, fraction));
	}

	/**
	 * 同余类内保留 Java 两次 float 舍入，按精确等差分布聚合；工作量不随成功轮数线性增长。
	 * float 倍率比 4/3、7/4、11/5 的理想斜率非小，因此扣除等差项后的修正单调不减。
	 */
	static long sampleGeneAdjustedSum(RandomGenerator random, int min, int max,
			long sampleCount, int productivityLevel) {
		if (sampleCount <= 0 || min < 0 || max < min) return 0;
		int level = Math.max(BeeProductivityGene.NORMAL,
				Math.min(BeeProductivityGene.VERY_HIGH, productivityLevel));
		long maximum = BeeProductivityGene.adjustCount(max, level);
		Math.multiplyExact(maximum, sampleCount);
		if (min == max) {
			return Math.multiplyExact(maximum, sampleCount);
		}
		if (sampleCount <= 32) {
			return sampleSingleTotals(random, min, max, sampleCount, level);
		}
		if (level == BeeProductivityGene.NORMAL) {
			return Math.addExact(Math.multiplyExact((long) min, sampleCount),
					sampleUniformOffsets(random, (long) max - min + 1, sampleCount));
		}
		int numerator = switch (level) {
			case 1 -> 4;
			case 2 -> 7;
			default -> 11;
		};
		int denominator = switch (level) {
			case 1 -> 3;
			case 2 -> 4;
			default -> 5;
		};
		float coefficient = 1.0F / (level + 2.0F) + (level + 1.0F) / 2.0F;
		long total = 0;
		long remainingSamples = sampleCount;
		long start = min;
		while (start <= max && remainingSamples > 0) {
			int first = (int) start;
			int last = first < 2 ? first : quantizationEnd(first, max, coefficient);
			long values = (long) last - first + 1;
			long remainingValues = (long) max - first + 1;
			long selected = values == remainingValues ? remainingSamples
					: ExactBinomialSampler.sample(random, remainingSamples, (double) values / remainingValues);
			if (selected > 0) {
				long subtotal = first < 2
						? Math.multiplyExact(BeeProductivityGene.adjustCount(first, level), selected)
						: sampleQuantizedRange(random, first, last, selected, level, coefficient, numerator, denominator);
				total = Math.addExact(total, subtotal);
			}
			remainingSamples -= selected;
			start = (long) last + 1;
		}
		return total;
	}

	private static long sampleSingleTotals(RandomGenerator random, int min, int max, long samples, int level) {
		long total = 0;
		for (long i = 0; i < samples; i++) {
			int sampled = SampleUniformSum.sampleSingle(random, min, max);
			total = Math.addExact(total, BeeProductivityGene.adjustCount(sampled, level));
		}
		return total;
	}

	private static int quantizationEnd(int min, int max, float coefficient) {
		long key = quantizationKey(min, coefficient);
		int low = min;
		int high = max;
		while (low < high) {
			int middle = low + (int) (((long) high - low + 1) / 2);
			if (quantizationKey(middle, coefficient) == key) low = middle;
			else high = middle - 1;
		}
		return low;
	}

	private static long quantizationKey(int count, float coefficient) {
		float product = coefficient * count;
		// ULP < 1 时输入整数精确，最终整数舍入的阈值随 ULP 增大单调降低。
		if (Math.ulp(product) < 1.0F) return 0;
		return ((long) Math.getExponent((float) count) << Integer.SIZE) | Math.getExponent(product);
	}

	private static long sampleQuantizedRange(RandomGenerator random, int min, int max, long samples,
			int level, float coefficient, int numerator, int denominator) {
		// 周期是输入、乘积两种量化网格的偶数倍，ties-to-even 在平移后保持相同方向。
		int period = 2 * denominator * (int) Math.max(1.0F, Math.ulp(coefficient * min));
		long values = (long) max - min + 1;
		int families = (int) Math.min(period, values);
		if (samples <= families) return sampleSingleTotals(random, min, max, samples, level);
		long step = period + (long) period * numerator / denominator;
		long total = 0;
		long remainingSamples = samples;
		long remainingValues = values;
		for (int residue = 0; residue < families && remainingSamples > 0; residue++) {
			int first = min + residue;
			long familyValues = ((long) max - first) / period + 1;
			long selected = familyValues == remainingValues ? remainingSamples
					: ExactBinomialSampler.sample(random, remainingSamples, (double) familyValues / remainingValues);
			total = Math.addExact(total,
					sampleProgression(random, first, period, step, familyValues, selected, level));
			remainingSamples -= selected;
			remainingValues -= familyValues;
		}
		return total;
	}

	static long sampleProgression(RandomGenerator random, int first, int period, long step,
			long values, long samples, int level) {
		long total = 0;
		long index = 0;
		long remainingSamples = samples;
		while (index < values && remainingSamples > 0) {
			long correction = progressionCorrection(first, period, step, index, level);
			long low = index;
			long high = values - 1;
			while (low < high) {
				long middle = low + (high - low + 1) / 2;
				if (progressionCorrection(first, period, step, middle, level) == correction) low = middle;
				else high = middle - 1;
			}
			long plateauValues = low - index + 1;
			long remainingValues = values - index;
			long selected = plateauValues == remainingValues ? remainingSamples
					: ExactBinomialSampler.sample(random, remainingSamples, (double) plateauValues / remainingValues);
			long base = correction + step * index;
			long subtotal = Math.addExact(Math.multiplyExact(base, selected),
					Math.multiplyExact(step, sampleUniformOffsets(random, plateauValues, selected)));
			total = Math.addExact(total, subtotal);
			remainingSamples -= selected;
			index = low + 1;
		}
		return total;
	}

	private static long progressionCorrection(int first, int period, long step, long index, int level) {
		int count = (int) (first + period * index);
		return BeeProductivityGene.adjustCount(count, level) - step * index;
	}

	/** 每个 dyadic 块内各二进制位独立，N 次索引之和等于各位 Binomial(N, 1/2) 的加权和。 */
	private static long sampleUniformOffsets(RandomGenerator random, long values, long samples) {
		if (samples == 0 || values == 1) return 0;
		if (samples <= 32) {
			long total = 0;
			for (long i = 0; i < samples; i++) {
				total = Math.addExact(total, random.nextLong(values));
			}
			return total;
		}
		long total = 0;
		long remainingValues = values;
		long remainingSamples = samples;
		while (remainingValues > 0 && remainingSamples > 0) {
			long block = Long.highestOneBit(remainingValues);
			long selected = block == remainingValues ? remainingSamples
					: ExactBinomialSampler.sample(random, remainingSamples, (double) block / remainingValues);
			total = Math.addExact(total, Math.multiplyExact(values - remainingValues, selected));
			for (long bit = 1; bit < block; bit <<= 1) {
				long ones = ExactBinomialSampler.sample(random, selected, 0.5);
				total = Math.addExact(total, Math.multiplyExact(bit, ones));
			}
			remainingSamples -= selected;
			remainingValues -= block;
		}
		return total;
	}
}
