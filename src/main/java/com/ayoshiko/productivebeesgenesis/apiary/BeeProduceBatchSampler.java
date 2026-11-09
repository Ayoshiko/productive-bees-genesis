package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeProductionSampling;
import com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ObjLongConsumer;
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
		if (output == null) return;
		sampleAmounts((template, count) -> ItemStackMergeHelper.appendBulkCount(output, template, count),
				recipeOutputs, batchCount, multiplier, stabilityBonus, productivityLevel, ThreadLocalRandom.current());
	}

	/**
	 * 模板只读，数量回调只写本次私有结果，不执行外部 IO；完整轮数与已付结果不截成 int。
	 */
	public static void sampleAmounts(ObjLongConsumer<ItemStack> output, Map<ItemStack, ChancedOutput> recipeOutputs,
			long batchCount, float multiplier, float stabilityBonus, int productivityLevel, RandomGenerator random) {
		if (output == null || recipeOutputs == null || recipeOutputs.isEmpty() || batchCount <= 0
				|| !Float.isFinite(multiplier) || multiplier <= 0.0f) return;
		long rollCount = sampleRollCount(random, batchCount, multiplier);
		if (rollCount <= 0) return;

		for (Map.Entry<ItemStack, ChancedOutput> entry : recipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			long count = BeeProductionSampling.sampleOutput(random, rollCount, chanced.min(), chanced.max(),
					chanced.chance(), stabilityBonus, productivityLevel);
			if (count > 0) output.accept(entry.getKey(), count);
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

	/** 物理蜂箱与网络共用逐栈基因公式，聚合数量不按 int 饱和。 */
	static long sampleGeneAdjustedSum(RandomGenerator random, int min, int max,
			long sampleCount, int productivityLevel) {
		return BeeProductionSampling.sampleGeneAdjustedSum(random, min, max, sampleCount, productivityLevel);
	}
}
