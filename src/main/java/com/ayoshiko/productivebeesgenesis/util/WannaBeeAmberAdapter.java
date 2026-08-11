package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager;
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
	 * 按生产事件采样并聚合掉落。普通批次逐次精确采样；超过上限时以 128 个独立样本
	 * 分层代表整个批次。每批仅扫描一次喂食槽，并按候选槽位惰性构建实体战利品上下文；
	 * 战利品表仍按样本独立执行，避免把一次随机结果复制到整个批次。
	 */
	public static List<ItemStack> sampleBatch(ServerLevel level, BlockPos origin, FeederSlotManager feeder,
			int productionCount, float multiplier) {
		if (level == null || origin == null || feeder == null
				|| productionCount <= 0 || multiplier <= 0.0F) return List.of();
		List<CustomData> candidates = feeder.getAmberEntityDataSnapshot();
		if (candidates.isEmpty()) return List.of();

		int sampleCount = WannaBeeBatchPlan.sampleCount(productionCount);
		BatchSampler sampler;
		try {
			sampler = new BatchSampler(level, origin, candidates);
		} catch (Exception e) {
			LogThrottle.warn("wanna_bee_amber_loot",
					"Wanna Bee 无法初始化封存生物战利品采样，跳过本批产出: origin={}", origin, e);
			return List.of();
		}
		List<AggregatedDrop> aggregated = new ArrayList<>();
		for (int i = 0; i < sampleCount; i++) {
			ItemStack sampled = sampler.sample();
			if (sampled.isEmpty()) continue;
			long representedEvents = WannaBeeBatchPlan.weightAt(productionCount, i);
			long amount = (long) sampled.getCount() * representedEvents;
			merge(aggregated, sampled, amount);
		}

		List<ItemStack> result = new ArrayList<>(aggregated.size());
		for (AggregatedDrop drop : aggregated) {
			double scaled = drop.count * (double) multiplier;
			int count = scaled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(scaled);
			if (count > 0) result.add(drop.template.copyWithCount(count));
		}
		return result;
	}

	private static void merge(List<AggregatedDrop> aggregated, ItemStack sampled, long amount) {
		for (AggregatedDrop existing : aggregated) {
			if (ItemStack.isSameItemSameComponents(existing.template, sampled)) {
				existing.count = amount > Long.MAX_VALUE - existing.count
						? Long.MAX_VALUE
						: existing.count + amount;
				return;
			}
		}
		aggregated.add(new AggregatedDrop(sampled.copyWithCount(1), amount));
	}

	private static final class BatchSampler {
		private static final LootSource INVALID_SOURCE = new LootSource(null, null);

		private final ServerLevel level;
		private final BlockPos origin;
		private final Vec3 originCenter;
		private final List<CustomData> candidates;
		private final LootSource[] sources;
		private final FakePlayer fakePlayer;
		private final ItemStack tool = new ItemStack(Items.DIAMOND_AXE);

		private BatchSampler(ServerLevel level, BlockPos origin, List<CustomData> candidates) {
			this.level = level;
			this.origin = origin;
			this.originCenter = Vec3.atCenterOf(origin);
			this.candidates = candidates;
			this.sources = new LootSource[candidates.size()];
			this.fakePlayer = FakePlayerFactory.get(level, WANNA_BEE_PROFILE);
		}

		private ItemStack sample() {
			int candidateIndex = ThreadLocalRandom.current().nextInt(candidates.size());
			try {
				LootSource source = sources[candidateIndex];
				if (source == null) {
					source = createSource(candidates.get(candidateIndex));
					sources[candidateIndex] = source;
				}
				return source.sample(level);
			} catch (Exception e) {
				sources[candidateIndex] = INVALID_SOURCE;
				LogThrottle.warn("wanna_bee_amber_loot",
						"Wanna Bee 无法读取封存生物战利品，跳过本次产出: origin={}", origin, e);
				return ItemStack.EMPTY;
			}
		}

		private LootSource createSource(CustomData entityData) {
			Entity entity = AmberBlockEntity.createEntity(level, entityData.copyTag());
			if (!(entity instanceof Mob mob)) return INVALID_SOURCE;
			mob.setPos(originCenter);

			LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(mob.getLootTable());
			if (lootTable == LootTable.EMPTY) return INVALID_SOURCE;

			LootParams params = new LootParams.Builder(level)
					.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, fakePlayer)
					.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
					.withParameter(LootContextParams.TOOL, tool)
					.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, fakePlayer)
					.withOptionalParameter(LootContextParams.ATTACKING_ENTITY, fakePlayer)
					.withParameter(LootContextParams.THIS_ENTITY, mob)
					.withParameter(LootContextParams.ORIGIN, originCenter)
					.create(LootContextParamSets.ENTITY);
			return new LootSource(lootTable, params);
		}
	}

	private record LootSource(LootTable lootTable, LootParams params) {
		private ItemStack sample(ServerLevel level) {
			if (lootTable == null) return ItemStack.EMPTY;
			List<ItemStack> drops = lootTable.getRandomItems(params);
			int eligibleCount = 0;
			for (ItemStack stack : drops) {
				if (!stack.is(ModTags.WANNABEE_LOOT_BLACKLIST)) eligibleCount++;
			}
			if (eligibleCount == 0) return ItemStack.EMPTY;

			int selected = level.random.nextInt(eligibleCount);
			for (ItemStack stack : drops) {
				if (stack.is(ModTags.WANNABEE_LOOT_BLACKLIST)) continue;
				if (selected-- == 0) return stack.copy();
			}
			return ItemStack.EMPTY;
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
