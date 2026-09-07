package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BoundedBooleanMemo;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.world.item.Item;

/**
 * per-host 有界标签过滤结果缓存。
 * <p>
 * <b>为什么必须缓存</b>：拉取热路径每 tick 会对候选 key 逐个判定，而
 * 「读取物品全部标签 + 逐个通配匹配」的成本远高于一次哈希查找。
 * 缓存把每次判定摊销为 O(1)，与 {@link Ae2SmeltingInputCache}
 * 的设计动机与容量策略保持一致。
 * <p>
 * <b>键是 {@link Item} 而不是 {@code AEItemKey}</b>：判定输入只有
 * {@code Ae2ItemTagView.candidateOf(key.getItem())}，即结果<b>完全由 Item 决定</b>，
 * 数据组件不参与。按完整键记忆等于给同一 Item 的每个组件变体各存一条，
 * 既压低命中率又要在查找时付 {@code AEItemKey.equals}（内部走
 * {@code ItemStack.isSameItemSameComponents}，spark BkTP3d9oSc 中该方法
 * 自身 self 时间 1416ms / 2.36%，为全服第 5 热方法）。
 * <p>
 * <b>失效条件</b>（任一变化即整体清空，避免逐条失效带来的复杂度）：
 * <ul>
 *   <li>表达式配置代号 {@link Ae2TagFilter#getGeneration()} 变化</li>
 *   <li>{@link ProductiveBeesGenesis#RECIPE_VERSION} 变化 —— 该版本号由
 *       {@code TagsUpdatedEvent} 递增，正是标签重载的可靠信号</li>
 * </ul>
 * <b>线程安全</b>：判定只在服务端 tick 线程发生，因此不加锁；
 * 网格回调的 {@link #clear()} 只投递失效请求，真正清表推迟到 tick 线程下一次判定。
 * <p>
 * <b>异常安全</b>：标签读取异常按「不通过」处理并限流告警，
 * 避免可选依赖异常导致整台机器崩溃或拉取到不该拉的物品。
 */
final class Ae2TagFilterCache {

	/** 上限与 SMELTING 缓存一致：防止长期加载的机器缓存下整个网络的物品类型。 */
	static final int MAX_ENTRIES = 1_024;

	private final BoundedBooleanMemo<Item> results = new BoundedBooleanMemo<>(MAX_ENTRIES);
	private int observedGeneration = Integer.MIN_VALUE;
	private long observedRecipeVersion = Long.MIN_VALUE;

	/**
	 * 判定候选是否通过标签过滤。
	 *
	 * @param filter per-tile 标签过滤状态；null 或未配置时直接放行
	 * @param key    候选 AE2 物品键
	 */
	boolean allows(Ae2TagFilter filter, AEItemKey key) {
		if (filter == null || !filter.isActive()) return true;
		if (key == null) return false;
		refresh(filter);

		Item item = key.getItem();
		int state = results.state(item);
		if (state != BoundedBooleanMemo.STATE_UNKNOWN) {
			return state == BoundedBooleanMemo.STATE_TRUE;
		}

		boolean allowed;
		try {
			allowed = filter.getSpec().allows(Ae2ItemTagView.candidateOf(item));
		} catch (LinkageError | RuntimeException error) {
			LogThrottle.warn("ae2_tag_filter_eval",
					"AE2 标签过滤判定异常，拒绝本次候选 key={}: {}", key, error.toString());
			allowed = false;
		}
		return results.remember(item, allowed);
	}

	/** 配置或标签版本变化时整体清空。 */
	private void refresh(Ae2TagFilter filter) {
		int generation = filter.getGeneration();
		long recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (observedGeneration == generation && observedRecipeVersion == recipeVersion) return;
		results.clearNow();
		observedGeneration = generation;
		observedRecipeVersion = recipeVersion;
	}

	/**
	 * 清空缓存（AE2 网格拓扑变化等场景）。可从任意线程调用。
	 * <p>
	 * 只投递失效请求，不触碰 {@code observedGeneration} / {@code observedRecipeVersion} ——
	 * 这两个字段由 tick 线程独占读写，跨线程改写它们才需要锁。
	 */
	void clear() {
		results.requestClear();
	}

	/** 当前缓存条目数，供诊断与测试使用。 */
	int size() {
		return results.size();
	}
}
