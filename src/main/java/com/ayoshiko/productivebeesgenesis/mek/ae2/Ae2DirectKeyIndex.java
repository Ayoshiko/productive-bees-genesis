package com.ayoshiko.productivebeesgenesis.mek.ae2;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Arrays;

/**
 * 精确条目索引：{@code 键 → 过滤槽位下标}，把「候选键 × 过滤槽位」的乘积级扫描降到 O(1)
 * <p>
 * <b>为什么需要</b>：{@code Ae2InputFilterQuerySupport.pullLimitIfAllowed} 对每个网络候选键
 * 都线性走完全部过滤槽位（默认 36，上限 1024），槽位里绝大多数是精确指纹条目。
 * spark 报告 vVh8WfPCN3 实测这条路径 1044ms（{@code Ae2InputFilter.getPullLimitIfAllowed}），
 * 是拉取路径上最后一处随「拉取类型数 × 过滤条目数」成积增长的开销。
 * <p>
 * <b>为什么只能覆盖精确命中</b>：{@code Ae2FilterEntryMatcher.matchesDirect} 除
 * {@code configured.equals(candidate)} 之外还有模糊路径 —— 非 precise 时按 bee_type 分组匹配，
 * ignoreNbt 时按同 base item 匹配。这些都无法用「键 → 槽位」哈希表表达，必须线性回退。
 * 因此索引只在<b>候选键无 bee_type 且未开启 ignoreNbt</b> 时启用：
 * 此条件下 matchesDirect 的两条模糊分支都要求 {@code ignoreNbt}，模糊条目又要求
 * {@code candidateBeeType != null}，故除精确相等外<b>不存在任何其他命中可能</b>，索引结果完备。
 * 大型 ME 网络里绝大多数候选是非蜜脾物品（熔炼输入、普通材料），正好落在这条快路径上；
 * 蜜脾候选照旧走线性匹配，语义完全不变。
 * <p>
 * <b>版本判定</b>：{@link Ae2InputFilter} 的所有写操作都是 clone→修改→发布（CopyOnWrite），
 * 因此<b>数组对象身份即版本号</b>。索引同时记录 {@code slots} 与 {@code keys} 两个数组的身份 ——
 * {@code resolveDirectKey} 只克隆 keys 不动 slots，只比 slots 会读到过期索引。
 * <p>
 * <b>线程安全</b>：不可变（构造后字段与表内容均不再修改），由 {@link Ae2InputFilter} 以
 * volatile 字段发布；竞态最坏结果是同一版本被重复构建一次，无正确性影响。
 *
 * @param <K> 存储键类型（生产路径为 {@code AEItemKey}；泛型化便于纯 Java 单元测试）
 */
final class Ae2DirectKeyIndex<K> {

	/** 构建时的槽位数组身份 */
	private final String[] slots;
	/** 构建时的已解析键数组身份 */
	private final K[] keys;
	/** 是否所有精确条目都已解析出键；存在未解析条目时索引不完备，必须线性回退 */
	private final boolean complete;
	/** 精确键 → 命中槽位下标（同一键可配置在多个槽位，数量需累加） */
	private final Object2ObjectOpenHashMap<K, int[]> keyToSlots;

	private Ae2DirectKeyIndex(String[] slots, K[] keys, boolean complete,
			Object2ObjectOpenHashMap<K, int[]> keyToSlots) {
		this.slots = slots;
		this.keys = keys;
		this.complete = complete;
		this.keyToSlots = keyToSlots;
	}

	/**
	 * 复用或重建索引。
	 *
	 * @param cached 上一次的索引（可为 null）；两个数组身份都未变时原样返回
	 */
	static <K> Ae2DirectKeyIndex<K> of(String[] currentSlots, K[] currentKeys,
			Ae2DirectKeyIndex<K> cached) {
		if (cached != null && cached.slots == currentSlots && cached.keys == currentKeys) return cached;
		Object2ObjectOpenHashMap<K, int[]> map = new Object2ObjectOpenHashMap<>();
		boolean complete = true;
		for (int i = 0; i < currentSlots.length; i++) {
			if (!Ae2InputFilter.isDirectFingerprint(currentSlots[i])) continue;
			K key = currentKeys != null && i < currentKeys.length ? currentKeys[i] : null;
			if (key == null) {
				// NBT 恢复后尚未懒解析，此条目只能靠指纹串匹配，索引无法表达
				complete = false;
				continue;
			}
			int[] existing = map.get(key);
			if (existing == null) {
				map.put(key, new int[] { i });
			} else {
				int[] grown = Arrays.copyOf(existing, existing.length + 1);
				grown[existing.length] = i;
				map.put(key, grown);
			}
		}
		return new Ae2DirectKeyIndex<>(currentSlots, currentKeys, complete, map);
	}

	/** 索引是否覆盖全部精确条目；false 时调用方必须走线性匹配 */
	boolean isComplete() {
		return complete;
	}

	/**
	 * 精确命中的槽位下标（按下标升序），无命中返回 null。
	 * <p>
	 * 仅在 {@link #isComplete()} 且候选键无 bee_type、未开启 ignoreNbt 时可作为完备结果使用。
	 */
	int[] slotsFor(K key) {
		return key == null ? null : keyToSlots.get(key);
	}
}
