package com.ayoshiko.productivebeesgenesis.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 泛型配方缓存管理器 — 基于LRU淘汰策略的实例级缓存
 * <p>
 * 每个方块实体维护独立实例，避免多世界环境下的缓存污染。
 * 不使用同步锁（Minecraft方块实体在服务端单线程执行），消除不必要的性能开销。
 * <p>
 * 缓存策略：使用 {@link Optional} 包装值，支持缓存"无配方"结果（{@link Optional#empty()}），
 * 避免对没有配方的输入物品每tick重复全量遍历配方。
 *
 * @param <T> 缓存的配方类型
 */
public class RecipeCacheManager<T> {

	/**
	 * 缓存键 — record 自动生成基于字段的 hashCode/equals，避免 String 拼接的 GC 开销
	 * <p>
	 * Item 为注册单例，identity equals/hashCode 在 JVM 内稳定；componentHash 覆盖数据组件差异。
	 */
	private record CacheKey(Item item, int componentHash) {
	}

	private final LinkedHashMap<CacheKey, Optional<T>> cache;
	private final int maxSize;

	/** 缓存命中次数 */
	private long hitCount;
	/** 缓存未命中次数 */
	private long missCount;
	/** 上次get操作是否命中缓存（volatile保证可见性，供PerformanceMonitor精确记录cacheHit） */
	private volatile boolean lastGetHit;

	/**
	 * @param maxSize 最大缓存条目数，超出后按LRU淘汰，必须为正数
	 * @throws IllegalArgumentException 如果 maxSize <= 0
	 */
	public RecipeCacheManager(int maxSize) {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
		}
		this.maxSize = maxSize;
		this.cache = new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<CacheKey, Optional<T>> eldest) {
				return size() > maxSize;
			}
		};
	}

	/**
	 * 生成缓存键，使用官方 {@link ItemStack#hashItemAndComponents(ItemStack)} 覆盖物品与数据组件，避免手工拼接 hashCode 的碰撞风险
	 * <p>
	 * 返回 {@link CacheKey}（record）而非 String，避免高频查找时的字符串拼接 GC 开销。
	 *
	 * @param stack 输入物品
	 * @return 缓存键
	 */
	private static CacheKey computeKey(ItemStack stack) {
		return new CacheKey(stack.getItem(), ItemStack.hashItemAndComponents(stack));
	}

	/**
	 * 查询缓存
	 * <p>
	 * 返回 {@link Optional} 包装的结果：
	 * <ul>
	 *   <li>{@code null}：缓存未命中，需要查询配方</li>
	 *   <li>{@code Optional.empty()}：缓存命中"无配方"结果</li>
	 *   <li>{@code Optional.of(recipe)}：缓存命中具体配方</li>
	 * </ul>
	 *
	 * @param input 输入物品
	 * @return 缓存结果，未命中返回null
	 */
	@Nullable
	public Optional<T> get(ItemStack input) {
		CacheKey key = computeKey(input);
		Optional<T> cached = cache.get(key);
		if (cached != null) {
			hitCount++;
			lastGetHit = true;
		} else {
			missCount++;
			lastGetHit = false;
		}
		return cached;
	}

	/**
	 * 返回上次 {@link #get(ItemStack)} 操作是否命中缓存
	 * <br/>
	 * 供 PerformanceMonitor 在 findPbRecipe 调用后精确记录 cacheHit，
	 * 避免修改 findPbRecipe 签名（保持单一职责）。
	 */
	public boolean wasLastGetHit() {
		return lastGetHit;
	}

	/**
	 * 存入缓存
	 * <p>
	 * 支持缓存"无配方"结果（recipe 为 null 时存入 {@link Optional#empty()}），
	 * 避免对没有配方的输入物品每tick重复全量遍历。
	 *
	 * @param input  输入物品
	 * @param recipe 配方对象，为 null 时缓存为"无配方"
	 */
	public void put(ItemStack input, @Nullable T recipe) {
		CacheKey key = computeKey(input);
		cache.put(key, recipe != null ? Optional.of(recipe) : Optional.empty());
	}

	/** 清空缓存 */
	public void clear() {
		cache.clear();
		hitCount = 0;
		missCount = 0;
	}

	/** 获取缓存命中率 */
	public double getHitRate() {
		long total = hitCount + missCount;
		return total > 0 ? (double) hitCount / total : 0.0;
	}

	/** 获取当前缓存条目数 */
	public int size() {
		return cache.size();
	}
}