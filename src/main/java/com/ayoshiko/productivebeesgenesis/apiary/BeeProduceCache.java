package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 蜜蜂产出配方正/负缓存（静态共享，LRU）
 * <br/>
 * 从 {@link BeeProduceProcessor} 拆分而来，职责（SRP）：缓存蜜蜂类型→配方输出表
 * 与"无配方"负缓存，配方重载时整体失效。
 * <p>
 * 静态化原因：相同蜜蜂类型的产出配方数据全局一致，所有方块实体共享
 * 同一份缓存避免 N 个蜂箱各存一份的内存浪费。
 * <p>
 * 使用 ResourceLocation 而非 EntityType 作为键的原因：
 * ConfigurableBee 的 EntityType 永远是 productivebees:configurable_bee，
 * 但具体蜜蜂类型（如 productivebees:iron）存储在 beeData 的 "type" 字段中。
 * 使用 EntityType 作为键会导致所有 ConfigurableBee 共享同一份（错误的）配方。
 * <p>
 * 线程安全：LinkedHashMap + removeEldestEntry 实现 LRU，synchronizedMap 提供防御性线程安全。
 */
final class BeeProduceCache {

	/** 正缓存最大条目数 */
	private static final int MAX_CACHE_SIZE = 512;

	/** 负缓存最大条目数（与 PbRecipeFinder.MAX_RECIPE_CACHE_SIZE 对齐） */
	private static final int MAX_NEGATIVE_CACHE_SIZE = 256;

	/**
	 * 产出配方正缓存（静态共享，LRU，容量 512）
	 * <br/>
	 * Value: 该蜜蜂的配方输出表（ItemStack -> ChancedOutput，原始数据不执行概率检查）。
	 * 概率判定统一由 {@link BeeProduceBatchSampler} 在采样阶段处理，
	 * 避免原 {@code chancedOutput.max()} 硬编码导致概率产物变必产物。
	 */
	private static final Map<ResourceLocation, Map<ItemStack, ChancedOutput>> produceCache =
			Collections.synchronizedMap(new LinkedHashMap<ResourceLocation, Map<ItemStack, ChancedOutput>>(512, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Map<ItemStack, ChancedOutput>> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			});

	/**
	 * 蜜蜂无产出配方负缓存（静态共享，LRU，容量 256）
	 * <br/>
	 * 缓存 BeeInfoHelper.getBeeProduce 返回空结果的蜜蜂类型键，避免重复全量遍历。
	 * 缓存失效通过 {@link #invalidate()} 在配方重载时清空。
	 */
	private static final Map<ResourceLocation, Boolean> negativeProduceCache =
			Collections.synchronizedMap(new LinkedHashMap<ResourceLocation, Boolean>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Boolean> eldest) {
					return size() > MAX_NEGATIVE_CACHE_SIZE;
				}
			});

	private BeeProduceCache() {
	}

	/** 查询正缓存 */
	static Map<ItemStack, ChancedOutput> getProduce(ResourceLocation beeTypeKey) {
		return produceCache.get(beeTypeKey);
	}

	/** 写入正缓存 */
	static void putProduce(ResourceLocation beeTypeKey, Map<ItemStack, ChancedOutput> result) {
		produceCache.put(beeTypeKey, result);
	}

	/** 查询负缓存 */
	static boolean isNegative(ResourceLocation beeTypeKey) {
		return negativeProduceCache.containsKey(beeTypeKey);
	}

	/** 写入负缓存 */
	static void putNegative(ResourceLocation beeTypeKey) {
		negativeProduceCache.put(beeTypeKey, Boolean.TRUE);
	}

	/** 清空全部缓存（配方重载时调用） */
	static void invalidate() {
		produceCache.clear();
		negativeProduceCache.clear();
	}
}
