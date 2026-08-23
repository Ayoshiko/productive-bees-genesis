package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.BatchProbabilitySampler;
import com.ayoshiko.productivebeesgenesis.mek.SampleUniformSum;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
	 * 机械蜂箱批量概率采样器
	 * <br/>
	 * SRP：专门负责机械蜂箱批量概率采样，与离心机路径 {@link com.ayoshiko.productivebeesgenesis.mek.PbRecipeCompleter}
	 * 保持算法一致。原 {@code BeeInfoHelper.getBeeProduce} 使用 {@code chancedOutput.max()} 忽略 chance 字段，
	 * 导致概率产物变必产物。本类将概率判定统一到采样阶段，批量场景下用 Binomial/Poisson/CLT 替代 N 次 Bernoulli。
	 * <p>
	 * 算法参考：
	 * <ul>
	 *   <li>{@link BatchProbabilitySampler#sampleBinomial} — 自适应 Binomial/Poisson/CLT 采样</li>
	 *   <li>{@link SampleUniformSum#sample} — 均匀分布求和 CLT 近似</li>
	 *   <li>{@code PbRecipeCompleter.accumulatePbRecipeOutputsBatch} — 离心机批量聚合参考实现</li>
	 * </ul>
	 * <p>
	 * 线程安全：无状态静态方法，仅依赖 {@link ThreadLocalRandom}，可并发调用。
	 */
public final class BeeProduceBatchSampler {

	private BeeProduceBatchSampler() {
		// 工具类禁止实例化
	}

	/**
	 * 批量采样产出
	 * <br/>
	 * 对每个 ChancedOutput 执行概率判定和数量采样：
	 * <ul>
	 *   <li>batchCount=1：单次路径，与原版 Bernoulli 概率检查完全等价</li>
	 *   <li>batchCount&gt;1 且 adjustedChance≥1.0：必定通过，CLT 近似 N 次 [min,max] 之和</li>
	 *   <li>batchCount&gt;1 且 adjustedChance&lt;1.0：Binomial 采样成功次数，再 CLT 近似</li>
	 * </ul>
	 * 蜂箱生产力升级先转换为额外采样轮数，再对每个成功产出的原始栈应用 PB 生产力基因公式。
	 *
	 * @param recipeOutputs  配方输出表（ItemStack -> ChancedOutput），不执行概率检查的原始数据
	 * @param batchCount     累积产出次数（同组蜜蜂的 pendingCount 之和）
	 * @param multiplier     蜂箱生产力升级倍率
	 * @param stabilityBonus 稳定性加成（提升非保底产物概率，机械蜂箱当前为 0.0）
	 * @return 已应用概率+数量的产物列表
	 */
	public static List<ItemStack> sample(
			Map<ItemStack, ChancedOutput> recipeOutputs,
			int batchCount, float multiplier, float stabilityBonus) {
		if (recipeOutputs == null || recipeOutputs.isEmpty() || batchCount <= 0) {
			return List.of();
		}
		List<ItemStack> result = new ArrayList<>(recipeOutputs.size());
		sampleInto(result, recipeOutputs, batchCount, multiplier, stabilityBonus, BeeProductivityGene.NORMAL);
		return result;
	}

	/** Writes sampled stacks into a caller-owned buffer to avoid an intermediate list per bee group. */
	public static void sampleInto(List<ItemStack> output,
			Map<ItemStack, ChancedOutput> recipeOutputs,
			int batchCount, float multiplier, float stabilityBonus) {
		sampleInto(output, recipeOutputs, batchCount, multiplier, stabilityBonus, BeeProductivityGene.NORMAL);
	}

	/**
	 * 将指定生产力基因等级应用到批量配方产出，并写入调用方持有的缓冲区。
	 *
	 * @param output            产物缓冲区
	 * @param recipeOutputs     原始配方输出表
	 * @param batchCount        生产次数
	 * @param multiplier        蜂箱生产力升级倍率
	 * @param stabilityBonus    稳定性概率加成
	 * @param productivityLevel PB 生产力基因等级 0 到 3
	 */
	public static void sampleInto(List<ItemStack> output,
			Map<ItemStack, ChancedOutput> recipeOutputs,
			int batchCount, float multiplier, float stabilityBonus, int productivityLevel) {
		if (output == null || recipeOutputs == null || recipeOutputs.isEmpty() || batchCount <= 0
				|| !Float.isFinite(multiplier) || multiplier <= 0.0f) return;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int rollCount = sampleRollCount(random, batchCount, multiplier);
		if (rollCount <= 0) return;

		for (Map.Entry<ItemStack, ChancedOutput> entry : recipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			float chance = chanced.chance();
			if (Float.isNaN(chance) || chance <= 0.0f) continue;
			float safeStabilityBonus = Float.isFinite(stabilityBonus)
					? Math.max(0.0f, stabilityBonus)
					: (stabilityBonus > 0.0f ? 1.0f : 0.0f);
			// stability bonus 提升非保底产物概率，截断到 1.0（与 PbRecipeCompleter 一致）
			float adjustedChance = chance >= 1.0f ? 1.0f : Math.min(1.0f, chance + safeStabilityBonus);
			if (adjustedChance <= 0.0f) continue;

			int min = Math.max(0, chanced.min());
			int max = Math.max(chanced.max(), min);

			// PB 在每个成功生成的原始 ItemStack 上应用生产力基因，不能对混合等级取平均。
			long successCount;
			if (rollCount == 1) {
				// 单次路径 — 与 PbRecipeCompleter.accumulatePbRecipeOutputs 完全等价
				if (adjustedChance < 1.0f && random.nextFloat() >= adjustedChance) continue;
				successCount = 1L;
			} else if (adjustedChance >= 1.0f) {
				successCount = rollCount;
			} else {
				// PB 对每个升级轮次独立判定配方概率；批量路径使用自适应二项采样。
				successCount = BatchProbabilitySampler.sampleBinomial(
						random, rollCount, adjustedChance);
			}

			if (successCount <= 0) continue;
			long geneAdjustedSum = sampleGeneAdjustedSum(
					random, min, max, successCount, productivityLevel);
			if (geneAdjustedSum <= 0) continue;
			// 最终 clamp 到 Integer.MAX_VALUE（ItemStack count 上限）
			long totalCount = Math.min(geneAdjustedSum, Integer.MAX_VALUE);
			if (totalCount <= 0) continue;
			output.add(entry.getKey().copyWithCount((int) totalCount));
		}
	}

	/**
	 * Samples a guaranteed one-item output without constructing a synthetic recipe map.
	 * Used by feeder-dependent bees whose output item is selected once per production batch.
	 */
	public static void sampleGuaranteedInto(List<ItemStack> output, ItemStack template,
			int batchCount, float multiplier) {
		sampleGuaranteedInto(output, template, batchCount, multiplier, BeeProductivityGene.NORMAL);
	}

	/** Writes a guaranteed feeder-dependent output with the PB productivity-gene formula applied. */
	public static void sampleGuaranteedInto(List<ItemStack> output, ItemStack template,
			int batchCount, float multiplier, int productivityLevel) {
		if (output == null || template == null || template.isEmpty() || batchCount <= 0
				|| !Float.isFinite(multiplier) || multiplier <= 0.0f) return;
		if (multiplier >= Integer.MAX_VALUE) {
			output.add(template.copyWithCount(Integer.MAX_VALUE));
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int lowerRolls = (int) Math.floor(multiplier);
		float fractionalRoll = multiplier - lowerRolls;
		long upperEvents = fractionalRoll > 0.0F
				? BatchProbabilitySampler.sampleBinomial(random, batchCount, fractionalRoll)
				: 0L;
		long lowerEvents = batchCount - upperEvents;
		long totalCount = SaturatingMath.saturatingMultiply(
				BeeProductivityGene.adjustStackCount(lowerRolls, productivityLevel), lowerEvents);
		if (upperEvents > 0L && lowerRolls < Integer.MAX_VALUE) {
			totalCount = SaturatingMath.saturatingAdd(totalCount,
					SaturatingMath.saturatingMultiply(
							BeeProductivityGene.adjustStackCount(lowerRolls + 1, productivityLevel),
							upperEvents));
		}
		totalCount = Math.min(totalCount, Integer.MAX_VALUE);
		if (totalCount > 0) {
			output.add(template.copyWithCount((int) totalCount));
		}
	}

	/**
	 * 批量采样 PB 的升级轮数。
	 * <p>
	 * 原版每次生产使用 {@code floor(multiplier)} 个固定轮次，并按小数部分决定是否增加一个轮次。
	 * N 次生产的额外轮次等价于一次 {@code Binomial(N, fractionalPart)} 采样。
	 *
	 * @param random      线程局部随机数
	 * @param batchCount  生产次数
	 * @param multiplier  蜂箱生产力升级倍率
	 * @return 聚合轮数，溢出时截断到 {@link Integer#MAX_VALUE}
	 */
	public static int sampleRollCount(ThreadLocalRandom random, int batchCount, float multiplier) {
		if (random == null || batchCount <= 0 || !Float.isFinite(multiplier) || multiplier <= 0.0F) {
			return 0;
		}
		long fixedRolls = (long) Math.floor(multiplier);
		if (fixedRolls >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
		long totalRolls = SaturatingMath.saturatingMultiply(fixedRolls, batchCount);
		if (totalRolls >= Integer.MAX_VALUE) return Integer.MAX_VALUE;

		double fractionalRoll = multiplier - fixedRolls;
		if (fractionalRoll > 0.0D) {
			totalRolls = SaturatingMath.saturatingAdd(totalRolls,
					BatchProbabilitySampler.sampleBinomial(random, batchCount, fractionalRoll));
		}
		return totalRolls >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalRolls;
	}

	/**
	 * 对 N 次均匀数量采样逐栈应用 PB 生产力公式。
	 * <p>
	 * 小批次精确采样；大批次使用转换后离散分布的均值和方差做 O(1) 正态近似。
	 */
	static long sampleGeneAdjustedSum(ThreadLocalRandom random, int min, int max,
			long sampleCount, int productivityLevel) {
		if (sampleCount <= 0 || min < 0 || max < min) return 0L;
		int level = Math.max(BeeProductivityGene.NORMAL,
				Math.min(BeeProductivityGene.VERY_HIGH, productivityLevel));
		if (level == BeeProductivityGene.NORMAL) {
			return SampleUniformSum.sample(random, min, max, sampleCount, 1);
		}
		if (sampleCount <= 32L) {
			long sum = 0L;
			for (long i = 0L; i < sampleCount; i++) {
				int sampled = SampleUniformSum.sampleSingle(random, min, max);
				sum = SaturatingMath.saturatingAdd(
						sum, BeeProductivityGene.adjustStackCount(sampled, level));
			}
			return sum;
		}
		if (min == max) {
			return SaturatingMath.saturatingMultiply(
					BeeProductivityGene.adjustStackCount(min, level), sampleCount);
		}

		long range = (long) max - min + 1L;
		if (range > 4_096L) {
			long baseSum = SampleUniformSum.sample(random, min, max, sampleCount, 1);
			double baseMean = ((double) min + max) / 2.0D;
			int representative = (int) Math.round(baseMean);
			double ratio = baseMean <= 0.0D ? 1.0D
					: BeeProductivityGene.adjustStackCount(representative, level) / baseMean;
			return SaturatingMath.saturatingRoundToLong(baseSum * ratio);
		}

		double mean = 0.0D;
		double squareSum = 0.0D;
		for (long rawValue = min; rawValue <= max; rawValue++) {
			int value = (int) rawValue;
			int adjusted = BeeProductivityGene.adjustStackCount(value, level);
			mean += adjusted;
			squareSum += (double) adjusted * adjusted;
		}
		mean /= range;
		double variance = Math.max(0.0D, squareSum / range - mean * mean);
		double sampled = mean * sampleCount
				+ random.nextGaussian() * Math.sqrt(variance * sampleCount);
		long lower = SaturatingMath.saturatingMultiply(
				BeeProductivityGene.adjustStackCount(min, level), sampleCount);
		long upper = SaturatingMath.saturatingMultiply(
				BeeProductivityGene.adjustStackCount(max, level), sampleCount);
		return Math.max(lower, Math.min(upper, SaturatingMath.saturatingRoundToLong(sampled)));
	}
}
