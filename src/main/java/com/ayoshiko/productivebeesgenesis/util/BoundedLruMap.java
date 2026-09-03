package com.ayoshiko.productivebeesgenesis.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界 LRU 映射工厂 — 访问顺序 {@link LinkedHashMap} + 超限淘汰最久未使用条目
 * <br/>
 * <b>动机</b>：项目内多处缓存需要"上限 + 淘汰"语义防止内存无界增长。
 * 常见的"满即整表清空"写法在条目数长期超过上限时会周期性丢弃全部热条目，
 * 命中率塌陷（清空后所有键都要重算）；LRU 只淘汰最久未使用的一条，
 * 让稳定复用的键始终驻留，代价是每次命中多一次链表节点移动。
 * <p>
 * <b>访问顺序</b>：{@code get} 与 {@code put} 都会把条目移到最近使用端，
 * 因此"最久未使用"按最后一次访问而非插入时间计算。
 * <p>
 * <b>线程安全</b>：{@link #accessOrdered} 返回的映射不同步，仅供单线程（如服务端 tick 线程）使用；
 * 需要跨线程访问时用 {@link #synchronizedAccessOrdered}，并注意昂贵的值计算应在锁外完成
 * （先 {@code get} 未命中，锁外算，再 {@code putIfAbsent}），避免长时间持有全表锁。
 */
public final class BoundedLruMap {

	private BoundedLruMap() {
		// 工具类禁止实例化
	}

	/**
	 * 创建非同步的有界 LRU 映射
	 *
	 * @param maxEntries 条目上限，必须为正数
	 * @return 访问顺序 LRU 映射，超限时淘汰最久未使用条目
	 * @throws IllegalArgumentException maxEntries 非正数
	 */
	public static <K, V> Map<K, V> accessOrdered(int maxEntries) {
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("maxEntries must be positive, got: " + maxEntries);
		}
		// 初始容量取上限但不超过 64，避免小缓存预分配过大数组
		int initialCapacity = Math.min(maxEntries, 64);
		return new LinkedHashMap<>(initialCapacity, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > maxEntries;
			}
		};
	}

	/**
	 * 创建同步包装的有界 LRU 映射
	 * <p>
	 * 全表锁：仅适用于非每 tick 热路径（如 GUI 查询）。
	 *
	 * @param maxEntries 条目上限，必须为正数
	 * @return 线程安全的访问顺序 LRU 映射
	 * @throws IllegalArgumentException maxEntries 非正数
	 */
	public static <K, V> Map<K, V> synchronizedAccessOrdered(int maxEntries) {
		return Collections.synchronizedMap(accessOrdered(maxEntries));
	}
}
