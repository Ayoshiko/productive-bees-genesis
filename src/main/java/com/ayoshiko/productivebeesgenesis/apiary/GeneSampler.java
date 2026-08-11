package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.common.item.Gene;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
	 * 基因采样器产出处理器
	 * <br/>
	 * 从 {@link BeeProduceProcessor} 抽取的基因采样逻辑，负责生成 TYPE 基因物品。
	 * <p>
	 * 复刻 PB 原版 {@code AdvancedBeehiveBlockEntity#beeReleasePostAction} 的基因采样语义：
	 * <ul>
	 *   <li>每次蜜蜂产出独立判定，命中概率 = {@link #SAMPLER_BASE_CHANCE} × 采样器数量</li>
	 *   <li>命中时生成 1 个 TYPE 基因，purity = random.nextInt(4) + 1（范围 1-4）</li>
	 *   <li>基因物品格式：{@link Gene#getStack(String, int)}</li>
	 * </ul>
	 * <p>
	 * 与 PB 原版的差异：机械蜂箱虽无实体蜜蜂，但可从蜜蜂 NBT 的
	 * neoforge:attachments.productivebees:attributes_handler 读取 GeneAttribute 属性类基因
	 * （参考 BeeTooltipRenderer.getAttributesCompound）。当前 GeneSampler 仅生成 TYPE 基因，
	 * PRODUCTIVITY 基因加成已在 BeeProduceProcessor.buildAdjustedItems 中应用，
	 * ENDURANCE/TEMPER 不适用（无实体蜜蜂），BEHAVIOR/WEATHER_TOLERANCE 待后续实现。
	 * TYPE 基因的 type 字段使用 {@link BeeNbtHelper#resolveBeeTypeKey} 解析的 ResourceLocation 字符串，
	 * 与 PB 原版 {@code ConfigurableBee#getBeeType().toString()} 语义一致。
	 * <p>
	 * 性能保护：当 produceCount 超过 {@link #GENE_SAMPLER_MAX_LOOP} 时，改用批量概率聚合计算，
	 * 避免高倍加速场景下（STACK满级+多蜜蜂+20tick累积）循环暴增导致服务端假死。
	 * 批量计算公式：expectedHits = produceCount * chance，使用正态分布近似添加随机扰动。
	 * <p>
	 * 线程安全：仅服务端 tick 线程调用，level.getRandom() 单线程访问无需同步。
	 */
public class GeneSampler {

	/**
	 * 基因采样器单次产出概率基数（与 PB 原版 ProductiveBeesConfig.UPGRADES.samplerChance 默认值 0.05 一致）
	 * <br/>
	 * 实际概率 = {@link #SAMPLER_BASE_CHANCE} × 采样器数量。
	 * PB 原版 {@code AdvancedBeehiveBlockEntity#beeReleasePostAction} 中：
	 * {@code level.random.nextFloat() <= samplerChance * samplerUpgrades}
	 */
	private static final float SAMPLER_BASE_CHANCE = 0.05f;

	/**
	 * 基因采样器独立伯努利判定的循环上限
	 * <br/>
	 * 当累积产出次数超过此值时，改用批量概率聚合计算（正态分布近似），
	 * 避免高倍加速场景下循环暴增导致服务端假死。
	 * 默认值 1000 足以覆盖正常场景（20 tick 累积 × 8 只蜜蜂 × 6 倍加速 ≈ 960）。
	 */
	private static final int GENE_SAMPLER_MAX_LOOP = 1000;

	/**
	 * 生成基因采样器产出的 TYPE 基因物品
	 * <br/>
	 * 复刻 PB 原版 {@code AdvancedBeehiveBlockEntity#beeReleasePostAction} 的基因采样逻辑：
	 * <ul>
	 *   <li>每次蜜蜂产出独立判定，命中概率 = {@link #SAMPLER_BASE_CHANCE} × 采样器数量</li>
	 *   <li>命中时生成 1 个 TYPE 基因，purity = random.nextInt(4) + 1（范围 1-4，与 PB 原版一致）</li>
	 *   <li>基因物品格式：{@link Gene#getStack(String, int)}，使用蜜蜂类型键字符串作为 type</li>
	 * </ul>
	 * <p>
	 * 性能保护：当 produceCount 超过 {@link #GENE_SAMPLER_MAX_LOOP} 时，改用批量概率聚合计算，
	 * 避免高倍加速场景下循环暴增导致服务端假死。
	 * 批量计算公式：expectedHits = produceCount * chance，使用正态分布近似添加随机扰动。
	 *
	 * @param beeTypeKey   蜜蜂类型键（如 productivebees:iron）
	 * @param produceCount 累积产出次数（独立伯努利判定次数）
	 * @param samplerCount 基因采样器安装数量（来自 {@link ApiaryUpgradeHandler#getGeneSamplerCount}）
	 * @param level        世界实例（随机数源）
	 * @return 基因物品列表（可能为空，未命中时返回空列表）
	 */
	public List<ItemStack> generateGeneSamples(ResourceLocation beeTypeKey, int produceCount,
			int samplerCount, Level level) {
		if (beeTypeKey == null || produceCount <= 0 || level == null || samplerCount <= 0) {
			return List.of();
		}
		// 单次产出概率 = 基础概率 × 采样器数量（与 PB 原版 samplerChance * samplerUpgrades 一致）
		float chance = SAMPLER_BASE_CHANCE * samplerCount;
		if (chance <= 0.0f) {
			return List.of();
		}
		// 蜜蜂类型字符串 — 与 PB 原版 ConfigurableBee#getBeeType().toString() 格式一致
		String typeString = beeTypeKey.toString();
		List<ItemStack> genes = new ArrayList<>();

		if (produceCount <= GENE_SAMPLER_MAX_LOOP) {
			// 正常场景：独立伯努利判定，保留完整随机性
			for (int i = 0; i < produceCount; i++) {
				if (level.getRandom().nextFloat() <= chance) {
					int purity = level.getRandom().nextInt(4) + 1;
					genes.add(Gene.getStack(typeString, purity));
				}
			}
		} else {
			// 高倍加速场景：批量概率聚合，避免循环暴增
			// M2-1 修复：chance > 1.0 时截断为 1.0（与正常场景行 92 的隐式行为一致，
			// nextFloat() 返回 [0,1) 永远 <= chance，故 chance > 1.0 等价于 chance = 1.0）
			// 原实现 expectedHits = produceCount * chance 会导致期望命中数超过 produceCount，
			// 而正常场景每次循环最多产生 1 个基因，期望不应超过 produceCount。
			float effectiveChance = Math.min(1.0f, chance);
			double expectedHits = produceCount * effectiveChance;
			double variance = expectedHits * (1.0 - effectiveChance);
			double stddev = Math.sqrt(variance);
			// Box-Muller 正态分布采样（截断到非负整数）
			double u1 = level.getRandom().nextDouble();
			double u2 = level.getRandom().nextDouble();
			double z = Math.sqrt(-2.0 * Math.log(u1 + 1e-10)) * Math.cos(2.0 * Math.PI * u2);
			// M2-2 修复：hitCount 上限截断到 produceCount
			// 正态分布右尾可能使 hitCount 超过 produceCount（如 expectedHits=produceCount，
			// z*stddev > 0 时 hitCount > produceCount），但实际单次循环最多产生 1 个基因，
			// 总命中数不应超过循环次数 produceCount
			int hitCount = Math.max(0, (int) Math.round(expectedHits + z * stddev));
			hitCount = Math.min(hitCount, produceCount);
			for (int i = 0; i < hitCount; i++) {
				int purity = level.getRandom().nextInt(4) + 1;
				genes.add(Gene.getStack(typeString, purity));
			}
		}
		return genes;
	}
}
