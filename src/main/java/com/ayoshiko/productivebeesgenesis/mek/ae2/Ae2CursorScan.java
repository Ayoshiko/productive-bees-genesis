package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * AE2 输入拉取游标回绕扫描纯逻辑（无 AE2 / Minecraft 依赖，可直接单元测试）
 * <br/>
 * 主扫描跳过游标之前的键；命中游标键时将其自身也加入 —— 修复"只拉取一次"：
 * 旧实现命中游标键后 continue 跳过它，回绕扫描又在游标键处 break，
 * 单蜜脾类型网络中 pullList 恒空、游标永不更新，导致后续不再拉取。
 * 收集不足 maxTypes 时从头回绕补充游标之前的键；游标键已在列表中或不可拉取时
 * 回绕继续向后扫描（重复键由 {@code seen} 集合去重）。
 * <p>
 * 调用方保证 keys 序列在同一快照内稳定。游标键不在 keys 中时回绕收集全部可拉取键。
 * <p>
 * <b>去重用哈希集合而非 {@code out.contains}</b>：候选列表在大型 AE 网络里可达数千项，
 * 而 {@code out} 的容量是「进程数 × 2」（最高等级工厂可到数十）。线性 contains 使整轮
 * 扫描退化为 O(候选数 × out 容量) 次 {@code AEItemKey.equals}，而后者内部走
 * {@code ItemStack.isSameItemSameComponents}（装 geckolib 时还被 mixin 包裹）——
 * spark BkTP3d9oSc 中 {@code AEItemKey.equals} 自身 self 时间 1416ms（2.36%），
 * 为全服第 5 热方法。改为哈希去重后每个键只付一次 hashCode（AEItemKey 构造时已缓存）
 * 加至多一次 equals。
 *
 * @since 2.0.9
 */
final class Ae2CursorScan {

	private Ae2CursorScan() {
		// 纯静态工具类禁止实例化
	}

	/**
	 * 按游标回绕规则收集扫描候选（便捷重载，内部自建去重集合，供测试与低频路径使用）
	 *
	 * @param out        收集结果容器（可为预置条目的列表，自动去重）
	 * @param keys       候选键序列
	 * @param cursor     上一轮最后拉取的键（null 表示无游标，全量收集）
	 * @param maxTypes   收集上限（不含 out 中已预置的条目）
	 * @param acceptable 键是否可拉取（false 的键跳过，不影响游标定位）
	 */
	static <T> void collect(List<T> out, List<T> keys, T cursor,
			int maxTypes, Predicate<T> acceptable) {
		List<T> prefixScratch = new ArrayList<>(Math.min(Math.max(0, maxTypes), 16));
		collectMapped(out, prefixScratch, new HashSet<>(out), keys, cursor, maxTypes,
				Function.identity(), acceptable);
	}

	/**
	 * Single-pass cursor scan for a heterogeneous source. Only up to {@code maxTypes}
	 * acceptable keys before the cursor are retained for a possible wraparound.
	 *
	 * @param seen 已加入 {@code out} 的键集合；调用方负责在首次调用前使其与 {@code out} 一致，
	 *             多次连续调用（如 {@link #collectPrioritized}）必须复用同一集合
	 */
	static <S, T> void collectMapped(List<T> out, List<T> prefixScratch, Set<T> seen,
			Iterable<S> keys, T cursor, int maxTypes,
			Function<S, T> mapper, Predicate<T> acceptable) {
		prefixScratch.clear();
		if (maxTypes <= out.size()) return;

		boolean afterCursor = cursor == null;
		for (S source : keys) {
			T key = mapper.apply(source);
			if (key == null || seen.contains(key)) continue;

			if (!afterCursor) {
				if (key.equals(cursor)) {
					if (!acceptable.test(key)) continue;
					afterCursor = true;
					select(out, seen, key);
				} else if (prefixScratch.size() < maxTypes && acceptable.test(key)) {
					prefixScratch.add(key);
				}
			} else {
				if (acceptable.test(key)) select(out, seen, key);
			}

			if (afterCursor && out.size() >= maxTypes) return;
		}

		// If the cursor was absent or unacceptable, prefixScratch contains the first
		// acceptable keys from the whole source. Otherwise it is the wraparound prefix.
		for (T key : prefixScratch) {
			if (out.size() >= maxTypes) break;
			if (!seen.contains(key)) select(out, seen, key);
		}
	}

	/**
	 * 便捷重载：自建去重集合。仅供测试与非热路径使用，热路径请传入可复用集合。
	 */
	static <S, T> void collectMapped(List<T> out, List<T> prefixScratch,
			Iterable<S> keys, T cursor, int maxTypes,
			Function<S, T> mapper, Predicate<T> acceptable) {
		collectMapped(out, prefixScratch, new HashSet<>(out), keys, cursor, maxTypes, mapper, acceptable);
	}

	/**
	 * Collects the prioritized group first, then uses remaining capacity for fallback keys.
	 *
	 * @param seen 可复用的去重集合，调用前必须与 {@code out} 内容一致（通常两者都为空）
	 * @return 优先组贡献的条目数；{@code out} 的前这么多项来自 {@code prioritizedKeys}。
	 *         调用方据此还原每个候选的分类，无需在后续排序阶段重跑一次分类判定
	 *         （那会再次穿过配方/标签缓存，spark BkTP3d9oSc 中分类链路占 784ms / 1.31%）。
	 */
	static <T> int collectPrioritized(List<T> out, List<T> prefixScratch, Set<T> seen,
			Iterable<T> prioritizedKeys, Iterable<T> fallbackKeys, T cursor,
			int maxTypes, Predicate<T> acceptable) {
		collectMapped(out, prefixScratch, seen, prioritizedKeys, cursor, maxTypes,
				Function.identity(), acceptable);
		int prioritizedCount = out.size();
		collectMapped(out, prefixScratch, seen, fallbackKeys, cursor, maxTypes,
				Function.identity(), acceptable);
		return prioritizedCount;
	}

	/**
	 * 便捷重载：自建去重集合。仅供测试与非热路径使用。
	 */
	static <T> int collectPrioritized(List<T> out, List<T> prefixScratch,
			Iterable<T> prioritizedKeys, Iterable<T> fallbackKeys, T cursor,
			int maxTypes, Predicate<T> acceptable) {
		return collectPrioritized(out, prefixScratch, new HashSet<>(out),
				prioritizedKeys, fallbackKeys, cursor, maxTypes, acceptable);
	}

	/** 返回包含游标的起始下标；游标为空或已消失时从 0 开始。 */
	static <S, T> int cursorStartIndex(List<S> values, T cursor, Function<S, T> mapper) {
		if (cursor == null || values == null || values.isEmpty()) return 0;
		for (int i = 0; i < values.size(); i++) {
			T value = mapper.apply(values.get(i));
			if (cursor.equals(value)) return i;
		}
		return 0;
	}

	private static <T> void select(List<T> out, Set<T> seen, T key) {
		out.add(key);
		seen.add(key);
	}
}
