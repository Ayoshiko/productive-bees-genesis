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
	 *   <li>{@link BatchProbabilitySampler#sampleBinomialWithGuarantee} — 保底机制 + 自适应 Binomial 采样</li>
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
	 *   <li>batchCount&gt;1 且 adjustedChance&lt;1.0：保底机制 + Binomial 采样成功次数，再 CLT 近似</li>
	 * </ul>
	 * multiplier（含 productivity 升级倍率 × (1 + 0.2 × purity) 加成）在 baseSum 采样后统一应用，
	 * 保持与原 {@code buildAdjustedItems} 的 {@code Math.round(totalBase * finalMultiplier)} 语义一致。
	 *
	 * @param recipeOutputs  配方输出表（ItemStack -> ChancedOutput），不执行概率检查的原始数据
	 * @param batchCount     累积产出次数（同组蜜蜂的 pendingCount 之和）
	 * @param multiplier     生产力倍率（含高产基因 purity 加成的 finalMultiplier）
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
		sampleInto(result, recipeOutputs, batchCount, multiplier, stabilityBonus);
		return result;
	}

	/** Writes sampled stacks into a caller-owned buffer to avoid an intermediate list per bee group. */
	public static void sampleInto(List<ItemStack> output,
			Map<ItemStack, ChancedOutput> recipeOutputs,
			int batchCount, float multiplier, float stabilityBonus) {
		if (output == null || recipeOutputs == null || recipeOutputs.isEmpty() || batchCount <= 0
				|| Float.isNaN(multiplier) || multiplier <= 0.0f) return;
		ThreadLocalRandom random = ThreadLocalRandom.current();

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

			// baseSum：不含 multiplier 的基础数量之和（long 域防溢出）
			long baseSum;
			if (batchCount == 1) {
				// 单次路径 — 与 PbRecipeCompleter.accumulatePbRecipeOutputs 完全等价
				if (adjustedChance < 1.0f && random.nextFloat() >= adjustedChance) continue;
				baseSum = SampleUniformSum.sampleSingle(random, min, max);
			} else if (adjustedChance >= 1.0f) {
				// 必定通过 — CLT 近似 batchCount 次 [min,max] 之和（modifier=1，倍率后续统一应用）
				baseSum = SampleUniformSum.sample(random, min, max, batchCount, 1);
			} else {
				// 保底机制 + 自适应 Binomial 采样成功次数
				long k = BatchProbabilitySampler.sampleBinomialWithGuarantee(random, batchCount, adjustedChance);
				if (k <= 0) continue;
				// k 次 [min,max] 之和（modifier=1）
				baseSum = SampleUniformSum.sample(random, min, max, k, 1);
			}

			if (baseSum <= 0) continue;
			// 应用 float multiplier（含 purity 加成），long 域防溢出
			long totalCount = SaturatingMath.saturatingRoundToLong((double) baseSum * multiplier);
			// 最终 clamp 到 Integer.MAX_VALUE（ItemStack count 上限）
			totalCount = Math.min(totalCount, Integer.MAX_VALUE);
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
		if (output == null || template == null || template.isEmpty() || batchCount <= 0
				|| Float.isNaN(multiplier) || multiplier <= 0.0f) return;
		long totalCount = SaturatingMath.saturatingRoundToLong((double) batchCount * multiplier);
		totalCount = Math.min(totalCount, Integer.MAX_VALUE);
		if (totalCount > 0) {
			output.add(template.copyWithCount((int) totalCount));
		}
	}
}
