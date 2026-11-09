package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 已退出内部生产路径的旧实例缓存，仅为既有外部二进制保留。
 * <p>
 * 旧键只比较蜂种或组件哈希，不能作为完整物品身份使用。正式离心查询由
 * {@link com.ayoshiko.productivebeesgenesis.mek.PbRecipeFinder} 与
 * {@link SharedPbRecipeCache} 保管完整组件和配方代际；新代码不得接入本类。
 *
 * @param <T> 缓存的配方类型
 * @deprecated 使用正式配方查询入口；移除前需确认外部消费者。
 */
@Deprecated(forRemoval = true)
public class RecipeCacheManager<T> {

	/** 旧兼容键不保留完整组件；不同组件可能命中同一项。 */
	private record CacheKey(Item item, @Nullable ResourceLocation beeType, int componentHash) {
		/** 保留旧二进制入口的语义，不作为新调用方的身份合同。 */
		static CacheKey of(ItemStack stack) {
			Item item = stack.getItem();
			if (item == PbCombItemRefs.honeycomb() || item == PbCombItemRefs.combBlock()) {
				// 轻量路径：只提取 bee_type，跳过 hashItemAndComponents
				return new CacheKey(item, stack.get(PbDataComponents.beeType()), 0);
			}
			// 通用旧键只保留哈希，无法消解完整组件身份的碰撞。
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
