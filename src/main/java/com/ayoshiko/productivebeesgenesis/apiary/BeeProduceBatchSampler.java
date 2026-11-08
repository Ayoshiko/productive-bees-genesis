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
			long successes = ExactBinomialSampler.sample(random, rollCount, adjustedChance);
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
		int lowerRolls = multiplier >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.floor(multiplier);
		double fraction = lowerRolls == Integer.MAX_VALUE ? 0 : multiplier - lowerRolls;
		long upperEvents = ExactBinomialSampler.sample(random, batchCount, fraction);
		long lowerEvents = batchCount - upperEvents;
		long count = Math.multiplyExact(
				BeeProductivityGene.adjustStackCount(lowerRolls, productivityLevel), lowerEvents);
		if (upperEvents > 0) {
			count = Math.addExact(count, Math.multiplyExact(
					BeeProductivityGene.adjustStackCount(lowerRolls + 1, productivityLevel), upperEvents));
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

	/** 以二项分配递归拆分均匀区间，聚合结果只由实际可生成的逐栈数量组成。 */
	static long sampleGeneAdjustedSum(RandomGenerator random, int min, int max,
			long sampleCount, int productivityLevel) {
		if (sampleCount <= 0 || min < 0 || max < min) return 0;
		int level = Math.max(BeeProductivityGene.NORMAL,
				Math.min(BeeProductivityGene.VERY_HIGH, productivityLevel));
		if (min == max) {
			return Math.multiplyExact(BeeProductivityGene.adjustStackCount(min, level), sampleCount);
		}
		if (level == BeeProductivityGene.NORMAL) {
			Math.multiplyExact((long) max, sampleCount);
			return SampleUniformSum.sample(random, min, max, sampleCount, 1);
		}
		int lower = BeeProductivityGene.adjustStackCount(min, level);
		int upper = BeeProductivityGene.adjustStackCount(max, level);
		if (lower == upper) return Math.multiplyExact((long) lower, sampleCount);
		if (sampleCount <= 32) {
			long sum = 0;
			for (long i = 0; i < sampleCount; i++) {
				int sampled = SampleUniformSum.sampleSingle(random, min, max);
				sum = Math.addExact(sum, BeeProductivityGene.adjustStackCount(sampled, level));
			}
			return sum;
		}
		int middle = min + (int) (((long) max - min) / 2);
		double leftProbability = ((long) middle - min + 1.0) / ((long) max - min + 1.0);
		long leftCount = ExactBinomialSampler.sample(random, sampleCount, leftProbability);
		return Math.addExact(
				sampleGeneAdjustedSum(random, min, middle, leftCount, level),
				sampleGeneAdjustedSum(random, middle + 1, max, sampleCount - leftCount, level));
	}
}
