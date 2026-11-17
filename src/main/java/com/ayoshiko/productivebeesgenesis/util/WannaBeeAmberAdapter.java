package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.BeeProductivityGene;
import com.ayoshiko.productivebeesgenesis.apiary.BeeProduceBatchSampler;
import com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.ItemStackMergeHelper;
import com.mojang.authlib.GameProfile;
import cy.jdkdigital.productivebees.common.block.entity.AmberBlockEntity;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.init.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Reproduces Productive Bees' dynamic Wanna Bee loot path for a simulated apiary. */
public final class WannaBeeAmberAdapter {

	private static final GameProfile WANNA_BEE_PROFILE =
			new GameProfile(ModEntities.WANNA_BEE_UUID, "wanna_bee");

	private WannaBeeAmberAdapter() {
	}

	/**
	 * 按生产事件采样并聚合掉落。每个代表事件只执行一次实体战利品表，随后从其候选池
	 * 抽取该事件代表的全部升级轮次。
	 * <p>
	 * Productive Bees 与 JDTE 均是一次生产事件调用一次 {@code getRandomItems}，再从本次
	 * 结果中抽取升级轮次。本实现保留该关联语义，并把大批次限制为最多 16 个代表事件，
	 * 避免同刻重复穿过昂贵的 NeoForge 全局 LootModifier 条件链。
	 */
	public static List<ItemStack> sampleBatch(ServerLevel level, BlockPos origin, FeederSlotManager feeder,
			int productionCount, float multiplier) {
		return sampleBatch(level, origin, feeder, productionCount, multiplier, BeeProductivityGene.NORMAL);
	}

	/**
	 * 按 PB 原版公式对每个抽中的战利品栈应用生产力基因，再聚合批量结果。
	 *
	 * @param level             服务端世界
	 * @param origin            蜂箱位置
	 * @param feeder            喂食槽
	 * @param productionCount   生产次数
	 * @param multiplier        蜂箱生产力升级倍率
	 * @param productivityLevel 生产力基因等级 0 到 3
	 * @return 聚合后的战利品栈
	 */
	public static List<ItemStack> sampleBatch(ServerLevel level, BlockPos origin, FeederSlotManager feeder,
			int productionCount, float multiplier, int productivityLevel) {
		long[] productionCounts = new long[Math.max(BeeProductivityGene.VERY_HIGH + 1, productivityLevel + 1)];
		productionCounts[Math.max(0, productivityLevel)] = productionCount;
		return sampleBatches(level, origin, feeder, productionCounts, multiplier);
	}

	/**
	 * 按生产力等级合并采样一个 Wanna Bee 蜂种组。
	 * <br/>
	 * 组级共享 16 次独立生产事件预算。小批次逐事件执行；大批次按各等级生产事件数分层，
	 * 每个代表事件的权重总和仍等于该等级的完整事件数。
	 */
	public static List<ItemStack> sampleBatches(ServerLevel level, BlockPos origin, FeederSlotManager feeder,
			long[] productionCounts, float multiplier) {

		if (level == null || origin == null || feeder == null || productionCounts == null
				|| !Float.isFinite(multiplier) || multiplier <= 0.0F) return List.of();
		return prepareBatch(level, origin, feeder, productionCounts, null).sample(level, productionCounts, multiplier);
	}

	/** 先执行原有最多 16 个代表事件，预算缩段与产量采样共用这些真实候选池。 */
	public static BatchSnapshot prepareBatch(ServerLevel level, BlockPos origin, FeederSlotManager feeder,
			long[] productionCounts, EssenceConversionUpgradeHelper.BatchPlan conversion) {
		List<CustomData> candidates = feeder.getAmberEntityDataSnapshot();
		if (candidates.isEmpty()) return new BatchSnapshot(List.of(), conversion);
		int[] sampleCounts = WannaBeeBatchPlan.allocateSampleCounts(productionCounts);
		List<SamplePool> pools = new ArrayList<>();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int productivityLevel = 0; productivityLevel < sampleCounts.length; productivityLevel++) {
			for (int sampleIndex = 0; sampleIndex < sampleCounts[productivityLevel]; sampleIndex++) {
				LootPool pool;
				try {
					pool = createLootPool(level, origin, candidates, random);
				} catch (Exception error) {
					throw new IllegalStateException("Wanna Bee batch loot preparation failed at " + origin, error);
				}
				pools.add(new SamplePool(productivityLevel, sampleIndex, sampleCounts[productivityLevel], pool));
			}
		}
		return new BatchSnapshot(List.copyOf(pools), conversion);
	}

	private record SamplePool(int level, int index, int samples, LootPool pool) {}

	public static final class BatchSnapshot {
		private final List<SamplePool> pools;
		private final long maximumUnit;
		private final long maximumIdentities;
		private final int conversionForms;

		private BatchSnapshot(List<SamplePool> pools, EssenceConversionUpgradeHelper.BatchPlan conversion) {
			this.pools = pools;
			long maximum = 0;
			long identities = 0;
			for (SamplePool sampled : pools) {
				identities += sampled.pool().eligibleDrops.size();
				for (ItemStack drop : sampled.pool().eligibleDrops) {
					long amount = BeeProductivityGene.adjustCount(drop.getCount(), sampled.level());
					long converted = conversion == null ? amount : conversion.maximumUnitCount(drop, amount);
					maximum = Math.max(maximum, converted);
				}
			}
			maximumUnit = maximum;
			maximumIdentities = identities;
			conversionForms = conversion == null ? 1 : 3;
		}

		/** 每轮只选一个候选；实际非空身份不超过轮数，不能把整个大候选池当成已生成结果。 */
		public boolean fitsStackBudget(long rolls, int availableStacks) {
			if (rolls == 0 || maximumUnit == 0) return true;
			long amountLimit = (long) availableStacks * Integer.MAX_VALUE;
			if (rolls > amountLimit / maximumUnit) return false;
			long amount = maximumUnit * rolls;
			long identities = Math.min(rolls, maximumIdentities);
			return conversionForms * (amount / Integer.MAX_VALUE + identities) <= availableStacks;
		}

		public List<ItemStack> sample(ServerLevel level, long[] productionCounts, float multiplier) {
			List<AggregatedDrop> aggregated = new ArrayList<>();
			ThreadLocalRandom random = ThreadLocalRandom.current();
			for (SamplePool sampled : pools) {
				long events = WannaBeeBatchPlan.weightAt(
						productionCounts[sampled.level()], sampled.samples(), sampled.index());
				long rolls = BeeProduceBatchSampler.sampleRollCount(random, events, multiplier);
				if (rolls > 0) sampleRolls(level, aggregated, sampled.pool(), rolls, sampled.level());
			}
			List<ItemStack> result = new ArrayList<>(aggregated.size());
			for (AggregatedDrop drop : aggregated) {
				ItemStackMergeHelper.appendBulkCount(result, drop.template, drop.count);
			}
			return result;
		}
	}

	private static LootPool createLootPool(ServerLevel level, BlockPos origin, List<CustomData> candidates,
			ThreadLocalRandom random) {
		CustomData entityData = candidates.get(random.nextInt(candidates.size()));
		Entity entity = AmberBlockEntity.createEntity(level, entityData.copyTag());
		if (!(entity instanceof Mob mob)) return LootPool.EMPTY;
		Vec3 originCenter = Vec3.atCenterOf(origin);
		mob.setPos(originCenter);
		LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(mob.getLootTable());
		if (lootTable == LootTable.EMPTY) return LootPool.EMPTY;

		FakePlayer fakePlayer = FakePlayerFactory.get(level, WANNA_BEE_PROFILE);
		LootParams params = new LootParams.Builder(level)
				.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, fakePlayer)
				.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
				.withParameter(LootContextParams.TOOL, new ItemStack(Items.DIAMOND_AXE))
				.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, fakePlayer)
				.withOptionalParameter(LootContextParams.ATTACKING_ENTITY, fakePlayer)
				.withParameter(LootContextParams.THIS_ENTITY, mob)
				.withParameter(LootContextParams.ORIGIN, originCenter)
				.create(LootContextParamSets.ENTITY);
		return LootPool.from(lootTable.getRandomItems(params));
	}

	private static void sampleRolls(ServerLevel level, List<AggregatedDrop> aggregated, LootPool pool,
			long rollCount, int productivityLevel) {
		if (pool.eligibleDrops.isEmpty()) return;
		long maximum = 0;
		for (ItemStack drop : pool.eligibleDrops) {
			maximum = Math.max(maximum, BeeProductivityGene.adjustCount(drop.getCount(), productivityLevel));
		}
		ItemStackMergeHelper.checkBulkCount(0, Math.multiplyExact(maximum, rollCount));
		if (pool.eligibleDrops.size() == 1) {
			ItemStack sampled = pool.eligibleDrops.getFirst();
			long amount = Math.multiplyExact(
					BeeProductivityGene.adjustCount(sampled.getCount(), productivityLevel), rollCount);
			merge(aggregated, sampled, amount);
			return;
		}
		long[] selectedCounts = WannaBeeLootSelection.sampleCounts(
				level.random, rollCount, pool.eligibleDrops.size());
		for (int i = 0; i < selectedCounts.length; i++) {
			if (selectedCounts[i] <= 0) continue;
			ItemStack sampled = pool.eligibleDrops.get(i);
			long amount = Math.multiplyExact(
					BeeProductivityGene.adjustCount(sampled.getCount(), productivityLevel), selectedCounts[i]);
			merge(aggregated, sampled, amount);
		}
	}

	private static void merge(List<AggregatedDrop> aggregated, ItemStack sampled, long amount) {
		if (amount <= 0) return;
		for (AggregatedDrop existing : aggregated) {
			if (ItemStack.isSameItemSameComponents(existing.template, sampled)) {
				existing.count = Math.addExact(existing.count, amount);
				return;
			}
		}
		aggregated.add(new AggregatedDrop(sampled.copyWithCount(1), amount));
	}

	private record LootPool(List<ItemStack> eligibleDrops) {
		private static final LootPool EMPTY = new LootPool(List.of());

		private static LootPool from(List<ItemStack> drops) {
			List<ItemStack> eligible = new ArrayList<>(drops.size());
			for (ItemStack stack : drops) {
				if (!stack.isEmpty() && !stack.is(ModTags.WANNABEE_LOOT_BLACKLIST)) eligible.add(stack);
			}
			return eligible.isEmpty() ? EMPTY : new LootPool(List.copyOf(eligible));
		}
	}

	private static final class AggregatedDrop {
		private final ItemStack template;
		private long count;

		private AggregatedDrop(ItemStack template, long count) {
			this.template = template;
			this.count = count;
		}
	}
}
