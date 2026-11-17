package com.ayoshiko.productivebeesgenesis.apiary;

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
	private final ItemStack[] reusableSingleFlowerOutputs = new ItemStack[BeeProductivityGene.VERY_HIGH + 1];
	private final long[] reusableSingleFlowerAmounts = new long[BeeProductivityGene.VERY_HIGH + 1];
	private final long[] reusableSingleFlowerCycleCounts = new long[BeeProductivityGene.VERY_HIGH + 1];
	private int[] reusablePaidCounts = new int[0];

	int[] generatedPaidCounts() {
		return reusablePaidCounts;
	}

	BeeProductionGenerator(Object cacheOwner, MultiFlowerProductionCache cache) {
		this.cacheOwner = cacheOwner;
		this.multiFlowerProductionCache = cache;
	}

	/** 利用生成阶段已有的四档汇总选择前段，不重复扫描蜜蜂槽。 */
	private long maximumPaidCycles(long[] counts, Map<ItemStack, ChancedOutput> outputs,
			ApiaryBatchUpgradeSnapshot upgrades, EssenceConversionUpgradeHelper.BatchPlan conversion,
			WannaBeeAmberAdapter.BatchSnapshot wannaBatch, int myriadCount,
			List<ItemStack> flowers, ItemStack[] singleFlowerOutputs) {
		long requested = 0;
		int gene = BeeProductivityGene.NORMAL;
		for (int i = 0; i < counts.length; i++) {
			requested += counts[i];
			if (counts[i] > 0) gene = i;
		}
		if (requested == 0) return 0;
		float multiplier = upgrades.productivityMultiplier();
		if (!Float.isFinite(multiplier) || multiplier <= 0.0F) {
			throw new ArithmeticException("Invalid bee productivity multiplier");
		}
		if ((flowers != null || singleFlowerOutputs != null) && multiplier >= Integer.MAX_VALUE) {
			throw new ArithmeticException("Per-event bee multiplier exceeds int");
		}
		int myriadItems = myriadCount * (upgrades.hasCombBlockUpgrade() ? 4 : 1);
		// 常规及随机回退最多九类 PB 蜜脾，每类按 64 个拆栈；跨身份最多增加八个尾栈。
		int myriadStacks = myriadItems == 0 ? 0 : (myriadItems + 63) / 64 + Math.min(myriadItems, 9) - 1;
		long low = 0;
		long high = requested;
		if (fitsPaidBudget(high, counts, gene, outputs, flowers, singleFlowerOutputs, upgrades, conversion, wannaBatch, myriadStacks)) return requested;
		while (low < high) {
			long middle = low + (high - low + 1) / 2;
			if (fitsPaidBudget(middle, counts, gene, outputs, flowers, singleFlowerOutputs, upgrades, conversion, wannaBatch, myriadStacks)) low = middle;
			else high = middle - 1;
		}
		if (low == 0) throw new ArithmeticException("Single bee cycle exceeds generated stack budget");
		return low;
	}

	private boolean fitsPaidBudget(long cycles, long[] counts, int gene, Map<ItemStack, ChancedOutput> outputs,
			List<ItemStack> flowers, ItemStack[] singleFlowerOutputs, ApiaryBatchUpgradeSnapshot upgrades,
			EssenceConversionUpgradeHelper.BatchPlan conversion,
			WannaBeeAmberAdapter.BatchSnapshot wannaBatch, int myriadStacks) {
		if (cycles == 0) return true;
		long stacks = upgrades.geneSamplerCount() > 0
				? GeneSampler.maximumStackCount(cycles, upgrades.geneTypeOnly(), upgrades.geneFullPurity()) : 0;
		stacks += myriadStacks;
		long rolls = (long) Math.ceil(upgrades.productivityMultiplier());
		long amountLimit = (long) ItemStackMergeHelper.MAX_GENERATED_STACKS * Integer.MAX_VALUE;
		if (rolls <= 0 || cycles > amountLimit / rolls) return false;
		long maximumRolls = cycles * rolls;
		if (singleFlowerOutputs != null) {
			Arrays.fill(reusableSingleFlowerAmounts, 0L);
			long remaining = cycles;
			long rawStacks = 0;
			for (int level = 0; level < counts.length; level++) {
				long selected = Math.min(remaining, counts[level]);
				remaining -= selected;
				ItemStack flower = singleFlowerOutputs[level];
				if (selected == 0 || flower.isEmpty()) continue;
				long count = BeeProductivityGene.adjustCount((int) rolls, level);
				if (count > amountLimit / selected) return false;
				long amount = count * selected;
				rawStacks += ItemStackMergeHelper.bulkStackCount(amount);
				if (rawStacks > ItemStackMergeHelper.MAX_GENERATED_STACKS - stacks) return false;
				int identity = level;
				for (int previous = 0; previous < level; previous++) {
					if (reusableSingleFlowerAmounts[previous] > 0
							&& ItemStack.isSameItemSameComponents(flower, singleFlowerOutputs[previous])) {
						identity = previous;
						break;
					}
				}
				reusableSingleFlowerAmounts[identity] += amount;
			}
			long convertedStacks = 0;
			if (conversion != null) {
				for (int level = 0; level < counts.length; level++) {
					if (reusableSingleFlowerAmounts[level] > 0) {
						convertedStacks += outputStackBound(singleFlowerOutputs[level],
								reusableSingleFlowerAmounts[level], conversion);
					}
				}
			}
			// 原始四档拆栈与同组件合计转换分别有界，预算与实际生成采用同一档位前段。
			stacks += Math.max(rawStacks, convertedStacks);
		} else if (flowers != null) {
			for (ItemStack flower : flowers) {
				long count = BeeProductivityGene.adjustCount((int) rolls, gene);
				if (count > amountLimit / cycles) return false;
				stacks += outputStackBound(flower, count * cycles, conversion) + Math.min(cycles, 4) - 1;
				if (stacks > ItemStackMergeHelper.MAX_GENERATED_STACKS) return false;
			}
		} else {
			for (var entry : outputs.entrySet()) {
				ChancedOutput output = entry.getValue();
				if (Float.isNaN(output.chance()) || output.chance() <= 0 || output.max() <= 0) continue;
				long count = BeeProductivityGene.adjustCount(output.max(), gene);
				if (count > amountLimit / maximumRolls) return false;
				stacks += outputStackBound(entry.getKey(), count * maximumRolls, conversion) + Math.min(cycles, 4) - 1;
				if (stacks > ItemStackMergeHelper.MAX_GENERATED_STACKS) return false;
			}
		}
		if (stacks > ItemStackMergeHelper.MAX_GENERATED_STACKS) return false;
		return wannaBatch == null ? true
				: wannaBatch.fitsStackBudget(maximumRolls, (int) (ItemStackMergeHelper.MAX_GENERATED_STACKS - stacks));
	}

	private static long outputStackBound(ItemStack template, long amount,
			EssenceConversionUpgradeHelper.BatchPlan conversion) {
		long raw = ItemStackMergeHelper.bulkStackCount(amount);
		if (conversion == null) return raw;
		try {
			return Math.max(raw, conversion.maximumStackCount(template, amount));
		} catch (ArithmeticException countOverflow) {
			// 数量已超过 long，更不适合当前有界列表；二分下一前段，无任何资产变更。
			return ItemStackMergeHelper.MAX_GENERATED_STACKS + 1L;
		}
	}

	private static int myriadCount(long[] productivityCounts, float multiplier) {
		double scaled = 0;
		for (int gene = 0; gene < productivityCounts.length; gene++) {
			scaled += productivityCounts[gene] * (double) multiplier * BeeProductivityGene.adjustStackCount(1, gene);
		}
		return (int) Math.min(MYRIAD_RANDOM_CAP, Math.max(0, scaled));
	}

	Generated prepare(BeeSlot[] beeSlots, int[] pendingCounts, OrderedSlotIndex groupSlotIndices,
			ResourceLocation beeTypeKey, Map<ItemStack, ChancedOutput> produceList, FeederSlotManager feederManager,
			BlockPos origin, Level level, ApiaryBatchUpgradeSnapshot upgrades, boolean multiFlowerBee,
			boolean nativeProduction, long forcedRolls, EssenceConversionUpgradeHelper.BatchPlan conversion,
			GeneSampler.Snapshot sampling, long seed, boolean paidProduction, boolean includeMyriad) {
		return prepare(beeSlots, pendingCounts, groupSlotIndices, beeTypeKey, produceList, feederManager,
				origin, level, upgrades, multiFlowerBee, nativeProduction, forcedRolls, conversion, sampling,
				seed, paidProduction, includeMyriad, -1, null, null);
	}

	private Generated prepare(BeeSlot[] beeSlots, int[] pendingCounts, OrderedSlotIndex groupSlotIndices,
			ResourceLocation beeTypeKey, Map<ItemStack, ChancedOutput> produceList, FeederSlotManager feederManager,
			BlockPos origin, Level level, ApiaryBatchUpgradeSnapshot upgrades, boolean multiFlowerBee,
			boolean nativeProduction, long forcedRolls, EssenceConversionUpgradeHelper.BatchPlan conversion,
			GeneSampler.Snapshot sampling, long seed, boolean paidProduction, boolean includeMyriad,
			int plannedMyriadCount, WannaBeeAmberAdapter.BatchSnapshot wannaBatch,
			ItemStack[] singleFlowerOutputs) {
		if (beeSlots == null || pendingCounts == null || groupSlotIndices == null
				|| produceList == null
				|| (produceList.isEmpty() && !PBConstants.WANNA_TYPE.equals(beeTypeKey)
						&& !multiFlowerBee && sampling == null && upgrades.geneSamplerCount() <= 0)) {
			return Generated.EMPTY;
		}

		if (paidProduction && reusablePaidCounts.length != pendingCounts.length) {
			reusablePaidCounts = new int[pendingCounts.length];
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
			if (paidProduction) reusablePaidCounts[idx] = count;
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

		boolean produceAllFlowers = multiFlowerBee && BalanceConfig.apiaryProduceAllFlowers();
		List<ItemStack> allFlowerOutputs = aggregatedCount > 0 && produceAllFlowers
				? multiFlowerProductionCache.get(beeTypeKey, feederManager, level) : null;
		if (aggregatedCount > 0 && multiFlowerBee && !produceAllFlowers && singleFlowerOutputs == null) {
			singleFlowerOutputs = reusableSingleFlowerOutputs;
			Arrays.fill(singleFlowerOutputs, ItemStack.EMPTY);
			for (int gene = 0; gene < productivityCounts.length; gene++) {
				if (productivityCounts[gene] > 0) {
					singleFlowerOutputs[gene] = MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(
							beeTypeKey, feederManager, level);
				}
			}
		}
		if (wannaBeeLevel != null && aggregatedCount > 0 && wannaBatch == null) {
			wannaBatch = WannaBeeAmberAdapter.prepareBatch(
					wannaBeeLevel, origin, feederManager, productivityCounts, conversion);
		}
		int myriadCount = plannedMyriadCount;
		if (myriadCount < 0) {
			myriadCount = 0;
			if (isMyriad && includeMyriad) myriadCount = myriadCount(productivityCounts, productivityMultiplier);
		}
		if (paidProduction && aggregatedCount > 0) {
			long limit = maximumPaidCycles(productivityCounts, produceList, upgrades, conversion,
					wannaBatch, myriadCount, allFlowerOutputs, singleFlowerOutputs);
			if (limit < aggregatedCount) {
				long remaining = limit;
				if (singleFlowerOutputs != null) {
					for (int gene = 0; gene < productivityCounts.length; gene++) {
						long selected = Math.min(remaining, productivityCounts[gene]);
						reusableSingleFlowerCycleCounts[gene] = selected;
						remaining -= selected;
					}
				}
				for (int position = 0; position < groupSlotIndices.size(); position++) {
					int index = groupSlotIndices.get(position);
					int selected;
					if (singleFlowerOutputs == null) {
						selected = (int) Math.min(remaining, pendingCounts[index]);
						remaining -= selected;
					} else {
						int gene = beeSlots[index].getProductivityLevel();
						selected = (int) Math.min(reusableSingleFlowerCycleCounts[gene], pendingCounts[index]);
						reusableSingleFlowerCycleCounts[gene] -= selected;
					}
					reusablePaidCounts[index] = selected;
				}
				geneSampleBatch.clear();
				return prepare(beeSlots, reusablePaidCounts, groupSlotIndices, beeTypeKey, produceList,
						feederManager, origin, level, upgrades, multiFlowerBee, nativeProduction, forcedRolls,
						conversion, sampling, seed, false, includeMyriad, myriadCount, wannaBatch, singleFlowerOutputs);
			}
		}

		// 按四档生产力等级分别批量采样。PB 原版公式含逐栈取整，混养时不能用平均等级替代。
		if (aggregatedCount > 0) {
			for (int productivityLevel = BeeProductivityGene.NORMAL;
					productivityLevel <= BeeProductivityGene.VERY_HIGH; productivityLevel++) {
				long sampledProductionCount = productivityCounts[productivityLevel];
				if (sampledProductionCount <= 0) continue;
				// 机械蜂箱当前无 stability 升级，stabilityBonus = 0.0
				if (allFlowerOutputs != null) {
					for (ItemStack output : allFlowerOutputs) {
						BeeProduceBatchSampler.sampleGuaranteedInto(allItems, output,
								sampledProductionCount, productivityMultiplier, productivityLevel);
					}
				} else if (multiFlowerBee) {
					ItemStack feederProduce = singleFlowerOutputs[productivityLevel];
					BeeProduceBatchSampler.sampleGuaranteedInto(allItems, feederProduce,
							sampledProductionCount, productivityMultiplier, productivityLevel);
				} else if (forcedRolls >= 0) {
					BeeProduceBatchSampler.sampleInto(allItems, produceList,
							forcedRolls, 1, 0.0f, productivityLevel);
				} else {
					BeeProduceBatchSampler.sampleInto(allItems, produceList,
							sampledProductionCount, productivityMultiplier, 0.0f, productivityLevel);
				}
			}
			if (wannaBeeLevel != null) {
				allItems.addAll(wannaBatch.sample(wannaBeeLevel, productivityCounts, productivityMultiplier));
			}
		}
		// Bug 10: 万象创世蜜蜂追加随机蜜脾/蜜脾块
		// 机械蜂箱绕过 BeeHelperMixin 注入（调用 BeeInfoHelper.getBeeProduce 而非 BeeHelper.getBeeProduce），
		// 需在此动态追加。随机产物不进入静态缓存 BeeProduceCache，避免所有蜂箱共享同一份随机结果。
		// 每个选定模板保留完整数量的大堆叠，避免生成拆栈与冻结输出再聚合。
		if (myriadCount > 0 && level != null) {
			try {
				List<ItemStack> randomItems;
				if (upgrades.hasCombBlockUpgrade()) {
					// 有 Block/Omega 升级：buildAggregatedCombBlocks 内部已 4× 缩放（与 Mixin 单次 4 个比例一致）
					randomItems = myriadAggregatedBuilder.buildAggregatedCombBlocks(
							myriadCount, level, cacheOwner);
				} else {
					// 无 Block/Omega 升级
					randomItems = myriadAggregatedBuilder.buildAggregatedHoneycombs(
							myriadCount, level, cacheOwner);
				}
				allItems.addAll(randomItems);
			} catch (Exception e) {
				throw new IllegalStateException("Myriad batch generation failed", e);
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
