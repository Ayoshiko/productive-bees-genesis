package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * per-host 有界标签过滤结果缓存。
 * <p>
 * <b>为什么必须缓存</b>：拉取热路径每 tick 会对候选 key 逐个判定，而
 * 「读取物品全部标签 + 逐个通配匹配」的成本远高于一次哈希查找。
 * 缓存把每个 AEItemKey 的判定摊销为 O(1)，与 {@link Ae2SmeltingInputCache}
 * 的设计动机与容量策略保持一致。
 * <p>
 * <b>失效条件</b>（任一变化即整体清空，避免逐条失效带来的复杂度）：
 * <ul>
 *   <li>表达式配置代号 {@link Ae2TagFilter#getGeneration()} 变化</li>
 *   <li>{@link ProductiveBeesGenesis#RECIPE_VERSION} 变化 —— 该版本号由
 *       {@code TagsUpdatedEvent} 递增，正是标签重载的可靠信号</li>
 * </ul>
 * <b>线程安全</b>：所有方法 synchronized，锁粒度为单台机器，
 * 不会让不同机器互相串行。
 * <p>
 * <b>异常安全</b>：标签读取异常按「不通过」处理并限流告警，
 * 避免可选依赖异常导致整台机器崩溃或拉取到不该拉的物品。
 */
final class Ae2TagFilterCache {

	/** 上限与 SMELTING 缓存一致：防止长期加载的机器缓存下整个网络的物品类型。 */
	static final int MAX_ENTRIES = 1_024;

	private final LinkedHashMap<AEItemKey, Boolean> results = new LinkedHashMap<>(64, 0.75f, true);
	private int observedGeneration = Integer.MIN_VALUE;
	private long observedRecipeVersion = Long.MIN_VALUE;

	/**
	 * 判定候选是否通过标签过滤。
	 *
	 * @param filter per-tile 标签过滤状态；null 或未配置时直接放行
	 * @param key    候选 AE2 物品键
	 */
	synchronized boolean allows(Ae2TagFilter filter, AEItemKey key) {
		if (filter == null || !filter.isActive()) return true;
		if (key == null) return false;
		refresh(filter);

		Boolean cached = results.get(key);
		if (cached != null) return cached;

		boolean allowed;
		try {
			allowed = filter.getSpec().allows(Ae2ItemTagView.candidateOf(key.getItem()));
		} catch (LinkageError | RuntimeException error) {
			LogThrottle.warn("ae2_tag_filter_eval",
					"AE2 标签过滤判定异常，拒绝本次候选 key={}: {}", key, error.toString());
			allowed = false;
		}
		evictIfFull();
		results.put(key, allowed);
		return allowed;
	}

	/** 配置或标签版本变化时整体清空。 */
	private void refresh(Ae2TagFilter filter) {
		int generation = filter.getGeneration();
		long recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (observedGeneration == generation && observedRecipeVersion == recipeVersion) return;
		results.clear();
		observedGeneration = generation;
		observedRecipeVersion = recipeVersion;
	}

	private void evictIfFull() {
		if (results.size() < MAX_ENTRIES) return;
		Iterator<AEItemKey> iterator = results.keySet().iterator();
		if (iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
	}

	/** 清空缓存（AE2 网格拓扑变化等场景）。 */
	synchronized void clear() {
		results.clear();
		observedGeneration = Integer.MIN_VALUE;
		observedRecipeVersion = Long.MIN_VALUE;
	}

	/** 当前缓存条目数，供诊断与测试使用。 */
	synchronized int size() {
		return results.size();
	}
}
