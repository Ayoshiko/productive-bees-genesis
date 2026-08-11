package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
	 * 泛型配方缓存管理器 — 基于LRU淘汰策略的实例级缓存
	 * <p>
	 * 每个方块实体维护独立实例，避免多世界环境下的缓存污染。
	 * 不使用同步锁（Minecraft方块实体在服务端单线程执行），消除不必要的性能开销。
	 * <p>
	 * 缓存策略：使用 {@link Optional} 包装值，支持缓存"无配方"结果（{@link Optional#empty()}），
	 * 避免对没有配方的输入物品每tick重复全量遍历配方。
	 * <p>
	 * <b>缓存键优化</b>：对 configurable_honeycomb / configurable_comb_block 使用
	 * {@code Item + bee_type} 作为轻量 key，避免 {@link ItemStack#hashItemAndComponents}
	 * 触发 owo {@code DerivedComponentMap.hashCode()} 和 {@code PatchedDataComponentMap.hashCode()}
	 * 的高昂开销。其他物品仍使用 {@link ItemStack#hashItemAndComponents} 保证正确性。
	 *
	 * @param <T> 缓存的配方类型
	 */
public class RecipeCacheManager<T> {

	/**
	 * 缓存键 — record 自动生成基于字段的 hashCode/equals，避免 String 拼接的 GC 开销
	 * <p>
	 * 对 configurable_honeycomb / configurable_comb_block：使用 {@code (Item, beeType)} 作为键，
	 * 跳过全组件哈希计算。其他物品：使用 {@code (Item, componentHash)} 作为键，保证组件差异区分。
	 */
	private record CacheKey(Item item, @Nullable ResourceLocation beeType, int componentHash) {
		/**
		 * 从 ItemStack 构建缓存键
		 * <p>
		 * configurable_honeycomb / configurable_comb_block 物品除 bee_type 外无其他可变组件，
		 * 用 beeType 替代 componentHash 可避免 owo 组件哈希计算。
		 */
		static CacheKey of(ItemStack stack) {
			Item item = stack.getItem();
			if (item == ModItems.CONFIGURABLE_HONEYCOMB.get() || item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				// 轻量路径：只提取 bee_type，跳过 hashItemAndComponents
				return new CacheKey(item, stack.get(ModDataComponents.BEE_TYPE.get()), 0);
			}
			// 通用路径：使用官方 hashItemAndComponents 覆盖物品与数据组件
			return new CacheKey(item, null, ItemStack.hashItemAndComponents(stack));
		}
	}

	private final LinkedHashMap<CacheKey, Optional<T>> cache;
	private final int maxSize;

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
		CacheKey key = CacheKey.of(input);
		return cache.get(key);
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
		CacheKey key = CacheKey.of(input);
		cache.put(key, recipe != null ? Optional.of(recipe) : Optional.empty());
	}

	/** 清空缓存 */
	public void clear() {
		cache.clear();
	}

	/** 获取当前缓存条目数 */
	public int size() {
		return cache.size();
	}
}
