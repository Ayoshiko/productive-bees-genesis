package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import cy.jdkdigital.productivebees.common.item.Gene;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneValue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 机械蜂箱基因采样器。
 * <p>
 * 对齐 PB 13.13.5：成年蜂每次完成生产时按配置概率命中，再均匀选择 TYPE、PRODUCTIVITY、
 * ENDURANCE、TEMPER、BEHAVIOR、WEATHER_TOLERANCE 之一，纯度为 1 到 4。
 * 小批次保留逐事件随机；高倍加速批次聚合命中与分布，避免主线程循环随倍率线性增长。
 * <p>
 * 线程安全：实例仅由所属方块实体的服务端 tick 线程调用，内部数组跨批次复用。
 */
public class GeneSampler {

	/** 256 倍加速下单蜂种也进入聚合路径，同时保留普通批次的精确随机。 */
	private static final int MAX_EXACT_EVENTS = 128;
	private static final int PURITY_COUNT = 4;
	private static final GeneAttribute[] ATTRIBUTES = GeneAttribute.values();
	private static final GeneValue[] GENE_VALUES = GeneValue.values();
	private static final int COUNTS_PER_ATTRIBUTE = GENE_VALUES.length * PURITY_COUNT;

	/** 固定域计数器替代每次命中新建 Map key；最大仅 6×19×4 个 long。 */
	private final long[] sampledCounts = new long[ATTRIBUTES.length * COUNTS_PER_ATTRIBUTE];
	private final int[] touchedIndices = new int[sampledCounts.length];
	private int touchedCount;

	/** 同一游戏刻的多蜂种分组共享一次配置读取。 */
	private long cachedChanceGameTime = Long.MIN_VALUE;
	private int cachedChanceSamplerCount = -1;
	private float cachedChance;

	/** 可复用的蜜蜂来源批次；只保存属性快照，不在 flush 之间持有 NBT。 */
	static final class SampleBatch {
		private GeneSampleProfile[] profiles = new GeneSampleProfile[4];
		private long[] produceCounts = new long[4];
		private int size;
		private long totalProduceCount;

		void add(GeneSampleProfile profile, long produceCount) {
			if (profile == null || produceCount <= 0) return;
			ensureCapacity(size + 1);
			profiles[size] = profile;
			produceCounts[size] = produceCount;
			size++;
			totalProduceCount = totalProduceCount > Long.MAX_VALUE - produceCount
					? Long.MAX_VALUE : totalProduceCount + produceCount;
		}

		boolean isEmpty() {
			return size == 0 || totalProduceCount <= 0;
		}

		void clear() {
			Arrays.fill(profiles, 0, size, null);
			size = 0;
			totalProduceCount = 0L;
		}

		private void ensureCapacity(int required) {
			if (required <= profiles.length) return;
			int capacity = Math.max(required, profiles.length * 2);
			profiles = Arrays.copyOf(profiles, capacity);
			produceCounts = Arrays.copyOf(produceCounts, capacity);
		}
	}

	/** 兼容单一来源调用方的采样入口。 */
	public List<ItemStack> generateGeneSamples(ResourceLocation beeTypeKey, int produceCount,
			int samplerCount, Level level) {
		if (produceCount <= 0) return List.of();
		SampleBatch batch = new SampleBatch();
		batch.add(GeneSampleProfile.fromBeeData(null), produceCount);
		List<ItemStack> result = new ArrayList<>();
		generateGeneSamplesInto(result, batch, beeTypeKey, samplerCount, level);
		return result.isEmpty() ? List.of() : result;
	}

	/** 将一组成年蜜蜂的采样结果直接追加到产出列表，避免创建中间列表。 */
	void generateGeneSamplesInto(List<ItemStack> output, SampleBatch sources,
			ResourceLocation beeTypeKey, int samplerCount, Level level) {
		if (output == null || sources == null || sources.isEmpty() || beeTypeKey == null
				|| samplerCount <= 0 || level == null) return;

		float chance = resolveChance(samplerCount, level);
		if (chance <= 0.0F) return;
		RandomSource random = level.getRandom();
		try {
			if (sources.totalProduceCount <= MAX_EXACT_EVENTS) {
				generateExactSamples(sources, chance, random);
			} else {
				long hitCount = approximateHitCount(sources.totalProduceCount, chance, random);
				generateAggregatedSamples(sources, hitCount, random);
			}
			emitSamples(output, beeTypeKey.toString());
		} finally {
			clearAccumulator();
		}
	}

	private float resolveChance(int samplerCount, Level level) {
		long gameTime = level.getGameTime();
		if (cachedChanceGameTime == gameTime && cachedChanceSamplerCount == samplerCount) {
			return cachedChance;
		}
		double configuredChance = ProductiveBeesConfig.UPGRADES.samplerChance.get();
		cachedChance = (float) Math.max(0.0D, Math.min(1.0D, configuredChance * samplerCount));
		cachedChanceSamplerCount = samplerCount;
		cachedChanceGameTime = gameTime;
		return cachedChance;
	}

	private void generateExactSamples(SampleBatch sources, float chance, RandomSource random) {
		for (int sourceIndex = 0; sourceIndex < sources.size; sourceIndex++) {
			GeneSampleProfile profile = sources.profiles[sourceIndex];
			long produceCount = sources.produceCounts[sourceIndex];
			for (long event = 0; event < produceCount; event++) {
				if (random.nextFloat() > chance) continue;
				GeneAttribute attribute = ATTRIBUTES[random.nextInt(ATTRIBUTES.length)];
				addSample(profile, attribute, random.nextInt(PURITY_COUNT), 1L);
			}
		}
	}

	private void generateAggregatedSamples(SampleBatch sources, long hitCount, RandomSource random) {
		if (hitCount <= 0) return;
		double allocationOffset = random.nextDouble();
		long cumulativeProduce = 0L;
		long allocatedHits = 0L;
		for (int sourceIndex = 0; sourceIndex < sources.size; sourceIndex++) {
			long produceCount = sources.produceCounts[sourceIndex];
			cumulativeProduce = cumulativeProduce > Long.MAX_VALUE - produceCount
					? Long.MAX_VALUE : cumulativeProduce + produceCount;
			long cumulativeHits = sourceIndex == sources.size - 1
					? hitCount
					: GeneSamplerMath.cumulativeHitAllocation(hitCount, cumulativeProduce,
							sources.totalProduceCount, allocationOffset);
			long sourceHits = Math.max(0L, cumulativeHits - allocatedHits);
			allocatedHits = cumulativeHits;
			distributeSourceHits(sources.profiles[sourceIndex], sourceHits, random);
		}
	}

	private void distributeSourceHits(GeneSampleProfile profile, long sourceHits, RandomSource random) {
		if (sourceHits <= 0) return;
		long perAttribute = sourceHits / ATTRIBUTES.length;
		int attributeRemainder = (int) (sourceHits % ATTRIBUTES.length);
		int attributeStart = random.nextInt(ATTRIBUTES.length);
		for (int offset = 0; offset < ATTRIBUTES.length; offset++) {
			long attributeHits = perAttribute + (offset < attributeRemainder ? 1L : 0L);
			if (attributeHits <= 0) continue;
			GeneAttribute attribute = ATTRIBUTES[(attributeStart + offset) % ATTRIBUTES.length];
			long perPurity = attributeHits / PURITY_COUNT;
			int purityRemainder = (int) (attributeHits % PURITY_COUNT);
			int purityStart = random.nextInt(PURITY_COUNT);
			for (int purityOffset = 0; purityOffset < PURITY_COUNT; purityOffset++) {
				long count = perPurity + (purityOffset < purityRemainder ? 1L : 0L);
				int purityIndex = (purityStart + purityOffset) % PURITY_COUNT;
				addSample(profile, attribute, purityIndex, count);
			}
		}
	}

	private void addSample(GeneSampleProfile profile, GeneAttribute attribute,
			int purityIndex, long count) {
		if (count <= 0) return;
		int valueIndex = attribute == GeneAttribute.TYPE ? 0 : profile.value(attribute).ordinal();
		int index = attribute.ordinal() * COUNTS_PER_ATTRIBUTE
				+ valueIndex * PURITY_COUNT + purityIndex;
		long previous = sampledCounts[index];
		if (previous == 0L) touchedIndices[touchedCount++] = index;
		sampledCounts[index] = previous > Long.MAX_VALUE - count ? Long.MAX_VALUE : previous + count;
	}

	private void emitSamples(List<ItemStack> output, String beeType) {
		for (int touched = 0; touched < touchedCount; touched++) {
			int index = touchedIndices[touched];
			long count = sampledCounts[index];
			if (count <= 0) continue;
			int attributeIndex = index / COUNTS_PER_ATTRIBUTE;
			int attributeOffset = index % COUNTS_PER_ATTRIBUTE;
			int valueIndex = attributeOffset / PURITY_COUNT;
			int purity = attributeOffset % PURITY_COUNT + 1;
			GeneAttribute attribute = ATTRIBUTES[attributeIndex];
			String value = attribute == GeneAttribute.TYPE
					? beeType : GENE_VALUES[valueIndex].getSerializedName();
			while (count > 0L) {
				int stackCount = (int) Math.min(count, Integer.MAX_VALUE);
				output.add(Gene.getStack(attribute, value, stackCount, purity));
				count -= stackCount;
			}
		}
	}

	private long approximateHitCount(long produceCount, float chance, RandomSource random) {
		double expectedHits = produceCount * (double) chance;
		double variance = expectedHits * (1.0D - chance);
		if (variance <= 0.0D) return Math.min(produceCount, Math.max(0L, Math.round(expectedHits)));
		double u1 = Math.max(1.0E-12D, random.nextDouble());
		double u2 = random.nextDouble();
		double z = Math.sqrt(-2.0D * Math.log(u1)) * Math.cos(2.0D * Math.PI * u2);
		long rounded = Math.round(expectedHits + z * Math.sqrt(variance));
		return Math.min(produceCount, Math.max(0L, rounded));
	}

	private void clearAccumulator() {
		for (int touched = 0; touched < touchedCount; touched++) {
			sampledCounts[touchedIndices[touched]] = 0L;
		}
		touchedCount = 0;
	}
}
