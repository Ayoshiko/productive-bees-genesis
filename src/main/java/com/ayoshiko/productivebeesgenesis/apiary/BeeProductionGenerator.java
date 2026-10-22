package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.*;
import com.ayoshiko.productivebeesgenesis.apiary.BeeProduceProcessor.Generated;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;

/** 机械蜂箱唯一的产物生成阶段。只在服务器线程使用，不推进周期或写入外部接收方。 */
final class BeeProductionGenerator {
	private static final int MYRIAD_RANDOM_CAP = 576;
	private final Object cacheOwner;
	private final MultiFlowerProductionCache multiFlowerProductionCache;
	private final GeneSampler geneSampler = new GeneSampler();
	private final GeneSampler.SampleBatch reusableGeneSampleBatch = new GeneSampler.SampleBatch();
	private final MyriadAggregatedStacksBuilder myriadAggregatedBuilder = new MyriadAggregatedStacksBuilder();
	private final ArrayList<ItemStack> reusableProducedItems = new ArrayList<>();
	private final long[] reusableProductivityCounts = new long[BeeProductivityGene.VERY_HIGH + 1];

	BeeProductionGenerator(Object cacheOwner, MultiFlowerProductionCache cache) {
		this.cacheOwner = cacheOwner;
		this.multiFlowerProductionCache = cache;
	}

	Generated prepare(BeeSlot[] beeSlots, int[] pendingCounts, OrderedSlotIndex groupSlotIndices,
			ResourceLocation beeTypeKey, Map<ItemStack, ChancedOutput> produceList, FeederSlotManager feederManager,
			BlockPos origin, Level level, ApiaryBatchUpgradeSnapshot upgrades, boolean multiFlowerBee,
			boolean nativeProduction, long forcedRolls, EssenceConversionUpgradeHelper.BatchPlan conversion,
			GeneSampler.Snapshot sampling, long seed) {
		if (beeSlots == null || pendingCounts == null || groupSlotIndices == null
				|| produceList == null
				|| (produceList.isEmpty() && !PBConstants.WANNA_TYPE.equals(beeTypeKey)
						&& !multiFlowerBee && sampling == null && upgrades.geneSamplerCount() <= 0)) {
			return Generated.EMPTY;
		}

		// 累积所有蜜蜂的产出物品，待批量合并插入
		List<ItemStack> allItems = reusableProducedItems;
		reusableProducedItems.clear();
		long totalFluidAmount = 0L;
		long aggregatedCount = 0L;  // 累积同组产出次数，循环外统一调用 BeeProduceBatchSampler（聚合取整修复）
		// 基因采样批次复用固定数组，避免高等级工厂每个蜂种组创建 List/record/NBT 引用。
		GeneSampler.SampleBatch geneSampleBatch = reusableGeneSampleBatch;
		geneSampleBatch.clear();
		// 机器级状态一律取自本轮 flush 共享快照（详见 ApiaryBatchUpgradeSnapshot）：
		// 这些取值与蜂种分组无关，逐组查询会把 ApiaryUpgradeCache 的 AtomicLong
		// 刷新计数放大 N 倍（N = 本轮分组数，混养时 N 可达槽位数）。
		int geneSamplerCount = upgrades.geneSamplerCount();
		long[] productivityCounts = reusableProductivityCounts;
		Arrays.fill(productivityCounts, 0L);
		boolean isMyriad = PBConstants.MYRIADCREATIONS_TYPE.equals(beeTypeKey);
		ServerLevel wannaBeeLevel = level instanceof ServerLevel serverLevel
				&& PBConstants.WANNA_TYPE.equals(beeTypeKey) ? serverLevel : null;

		float productivityMultiplier = upgrades.productivityMultiplier();
		boolean discardUselessByproducts = upgrades.discardUselessByproducts();

		// 模块 2+3：循环外查询流体输出类型（同组蜜蜂共享 beeTypeKey，流体类型一致）
		// BeeFluidOutputResolver 从离心配方推断流体类型：蜂蜜返回 FluidStack(honey, 250)，
		// 非蜂蜜流体（如时间流体）返回 EMPTY，无配方返回蜂蜜（向后兼容）
		// 仅基因采样不属于普通生产，不能触发无离心配方时的蜂蜜兜底。
		boolean hasNativeProducts = !produceList.isEmpty() || multiFlowerBee || wannaBeeLevel != null || isMyriad;
		FluidStack fluidTemplate = (nativeProduction && hasNativeProducts && !discardUselessByproducts && beeTypeKey != null && level != null)
				? BeeFluidOutputResolver.resolveFluidOutput(beeTypeKey, level)
				: FluidStack.EMPTY;

		// Bug 3: 仅遍历当前组的槽位索引，避免混养时其他组槽位被错误处理
		for (int position = 0; position < groupSlotIndices.size(); position++) {
			int idx = groupSlotIndices.get(position);
			int count = pendingCounts[idx];
			if (count <= 0) continue;
			BeeSlot slot = beeSlots[idx];
			if (slot == null || slot.isEmpty()) continue;
			CompoundTag beeData = slot.getBeeData();

			aggregatedCount = SaturatingMath.saturatingAdd(aggregatedCount, count);
			// 模块 2+3：仅当流体模板非空时累积流体量（非蜂蜜流体蜜蜂不注入蜂蜜）
			if (!fluidTemplate.isEmpty()) {
				long fluidAmount = SaturatingMath.saturatingMultiply(fluidTemplate.getAmount(), count);
				totalFluidAmount = SaturatingMath.saturatingAdd(totalFluidAmount, fluidAmount);
			}
			if (geneSamplerCount > 0) {
				// PB 原版只采样成年蜂；Age 缺失时 CompoundTag#getInt 返回 0，按成年蜂兼容。
				if (beeData == null || beeData.getInt("Age") >= 0) {
					geneSampleBatch.add(slot.getGeneSampleProfile(), count);
				}
			}
			int productivityLevel = slot.getProductivityLevel();
			productivityCounts[productivityLevel] = SaturatingMath.saturatingAdd(
					productivityCounts[productivityLevel], count);
		}

		// 按四档生产力等级分别批量采样。PB 原版公式含逐栈取整，混养时不能用平均等级替代。
		List<ItemStack> allFlowerOutputs = aggregatedCount > 0 && multiFlowerBee && BalanceConfig.apiaryProduceAllFlowers()
				? multiFlowerProductionCache.get(beeTypeKey, feederManager, level) : null;
		if (aggregatedCount > 0) {
			for (int productivityLevel = BeeProductivityGene.NORMAL;
					productivityLevel <= BeeProductivityGene.VERY_HIGH; productivityLevel++) {
				int sampledProductionCount = SaturatingMath.saturatingToInt(
						productivityCounts[productivityLevel]);
				if (sampledProductionCount <= 0) continue;
				// 机械蜂箱当前无 stability 升级，stabilityBonus = 0.0
				if (allFlowerOutputs != null) {
					for (ItemStack output : allFlowerOutputs) {
						BeeProduceBatchSampler.sampleGuaranteedInto(allItems, output,
								sampledProductionCount, productivityMultiplier, productivityLevel);
					}
				} else if (multiFlowerBee) {
					ItemStack feederProduce = MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(
							beeTypeKey, feederManager, level);
					BeeProduceBatchSampler.sampleGuaranteedInto(allItems, feederProduce,
							sampledProductionCount, productivityMultiplier, productivityLevel);
				} else if (forcedRolls >= 0) {
					BeeProduceBatchSampler.sampleInto(allItems, produceList,
							Math.toIntExact(forcedRolls), 1, 0.0f, productivityLevel);
				} else {
					BeeProduceBatchSampler.sampleInto(allItems, produceList,
							sampledProductionCount, productivityMultiplier, 0.0f, productivityLevel);
				}
			}
			if (wannaBeeLevel != null) {
				allItems.addAll(WannaBeeAmberAdapter.sampleBatches(wannaBeeLevel, origin, feederManager,
						productivityCounts, productivityMultiplier, forcedRolls >= 0));
			}
		}
		// 万象创世追加产物也按每只蜜蜂的生产力等级计算，避免与主产物倍率脱节。
		double scaledMyriadCount = 0.0D;
		if (isMyriad) {
			for (int productivityLevel = BeeProductivityGene.NORMAL;
					productivityLevel <= BeeProductivityGene.VERY_HIGH; productivityLevel++) {
				scaledMyriadCount += productivityCounts[productivityLevel]
						* (double) productivityMultiplier
						* BeeProductivityGene.adjustStackCount(1, productivityLevel);
			}
		}
		int effectiveMyriadCount = scaledMyriadCount >= Integer.MAX_VALUE
				? Integer.MAX_VALUE : Math.max(0, (int) scaledMyriadCount);

		// Bug 10: 万象创世蜜蜂追加随机蜜脾/蜜脾块
		// 机械蜂箱绕过 BeeHelperMixin 注入（调用 BeeInfoHelper.getBeeProduce 而非 BeeHelper.getBeeProduce），
		// 需在此动态追加。随机产物不进入静态缓存 BeeProduceCache，避免所有蜂箱共享同一份随机结果。
		// Task 7: 改用 MyriadAggregatedStacksBuilder 预聚合，将原 576 ItemStack 降为 ≤9 个聚合 stack，
		// 后续 distributeToOutput 迭代次数从 576 降为 9。
		if (effectiveMyriadCount > 0 && level != null) {
			try {
				// cappedMyriadCount 作为 totalCount 上限保护输出槽总容量（9 槽 × 64 = 576，实际 ItemStack ≤9）
				int cappedMyriadCount = Math.min(effectiveMyriadCount, MYRIAD_RANDOM_CAP);
				List<ItemStack> randomItems;
				if (upgrades.hasCombBlockUpgrade()) {
					// 有 Block/Omega 升级：buildAggregatedCombBlocks 内部已 4× 缩放（与 Mixin 单次 4 个比例一致）
					randomItems = myriadAggregatedBuilder.buildAggregatedCombBlocks(
							cappedMyriadCount, level, cacheOwner);
				} else {
					// 无 Block/Omega 升级
					randomItems = myriadAggregatedBuilder.buildAggregatedHoneycombs(
							cappedMyriadCount, level, cacheOwner);
				}
				if (randomItems != null && !randomItems.isEmpty()) {
					allItems.addAll(randomItems);
				}
			} catch (Exception e) {
				if (forcedRolls >= 0) throw new IllegalStateException("Myriad resource batch generation failed", e);
				// 随机产物追加失败不影响主产出，记录警告便于调试
				// MyriadAggregatedStacksBuilder 内部已有降级路径，外层 try-catch 为最终兜底
				ProductiveBeesGenesis.LOGGER.warn("万象创世随机蜜脾追加失败", e);
			}
		}

		// 基因采样器按 PB 原版六类 GeneAttribute 随机采样。机械蜂箱从每只蜜蜂保存的
		// attributes_handler NBT 读取属性值，不能只按 beeTypeKey 聚合，否则同类型异属性蜜蜂会串值。
		// 概率公式：PB samplerChance × 采样器数量；批次总量按成年蜜蜂产出次数加权。
		if (!geneSampleBatch.isEmpty() && beeTypeKey != null && level != null && geneSamplerCount > 0) {
			try {
				if (sampling != null) allItems.addAll(sampling.sample(aggregatedCount, seed));
				else geneSampler.generateGeneSamplesInto(
						allItems, geneSampleBatch, beeTypeKey, geneSamplerCount, level,
						upgrades.geneTypeOnly(), upgrades.geneFullPurity());
			} finally {
				geneSampleBatch.clear();
			}
		}
		// 防御性清理：beeTypeKey/level 异常时也不跨 tick 保留属性快照。
		geneSampleBatch.clear();

		if (allItems.isEmpty() && totalFluidAmount == 0) return Generated.EMPTY;

		allItems = ApiaryOutputUpgrades.apply(allItems, discardUselessByproducts, upgrades.hasCombBlockUpgrade(),
				conversion, false);
		return new Generated(allItems, fluidTemplate, totalFluidAmount);
	}

}
