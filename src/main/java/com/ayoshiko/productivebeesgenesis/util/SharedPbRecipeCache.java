package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * PB 离心配方查找结果缓存 — 静态共享 LRU
 * <p>
 * 替代原 {@link com.ayoshiko.productivebeesgenesis.mek.PbRecipeFinder} 的实例级
 * {@link RecipeCacheManager}：所有离心机工厂（Mek/MEK/EME 各 tier）共享同一份查找缓存，
 * 消除 N 台机器处理同种蜜脾时的独立长期缓存与重复全量遍历。
 * <p>
 * <b>静态化依据</b>（参考模组先例）：
 * <ul>
 *   <li>Mekanism 本体 {@code InputRecipeCache} 为 per-recipeType 静态单例（{@code MekanismRecipeType} 持有一个 inputCache）</li>
 *   <li>本模组 {@code BeeProduceCache}（静态共享 LRU）已成功运行多个版本</li>
 *   <li>OmniSequence 的规划缓存同样跨方块实例复用</li>
 * </ul>
 * <p>
 * <b>世界无关性论证</b>：缓存 value 为 {@link RecipeHolder}，其来源 {@code CentrifugeRecipeIndex}
 * 与全量遍历结果均取自当前世界的 RecipeManager；配方/标签重载时 {@link #invalidate()} 与
 * {@code CentrifugeRecipeIndex} 原子替换同步触发，不会跨存档残留旧 RecipeHolder 引用。
 * <p>
 * <b>线程安全</b>：{@link Collections#synchronizedMap} 提供防御性线程安全
 * （JEI 客户端查询与服务端 tick 可能并发访问 {@link #invalidate}）。
 * 缓存键对 configurable_honeycomb / configurable_comb_block 使用 {@code (Item, beeType)}
 * 计算轻量哈希，跳过 owo 组件哈希；所有路径都保留完整 {@link DataComponentPatch}
 * 参与 equals，避免忽略自定义组件或仅依赖 int 哈希导致误命中。
 */
public final class SharedPbRecipeCache {

	/** 缓存最大条目数（与原实例级 MAX_RECIPE_CACHE_SIZE 对齐） */
	private static final int MAX_CACHE_SIZE = 256;

	/**
	 * 缓存键 — record 基于字段比较完整身份，并复用预计算哈希避免重复遍历组件。
	 * <p>
	 * 对 configurable_honeycomb / configurable_comb_block：用 {@code (Item, beeType)} 计算哈希，
	 * 跳过全组件哈希计算。所有物品都保留 {@code (Item, DataComponentPatch)}
	 * 作为最终等价性依据，同 bee_type 但其他组件不同的蜜脾不会共用结果。
	 */
	private record CacheKey(Item item, @Nullable ResourceLocation beeType,
			DataComponentPatch components, int cachedHash) {
		static CacheKey of(ItemStack stack) {
			Item item = stack.getItem();
			if (item == ModItems.CONFIGURABLE_HONEYCOMB.get() || item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				// 轻量哈希：只用 bee_type 分桶；完整组件补丁仍参与 equals 消解碰撞。
				ResourceLocation beeType = stack.get(PbDataComponents.beeType());
				int hash = 31 * item.hashCode() + (beeType == null ? 0 : beeType.hashCode());
				return new CacheKey(item, beeType, stack.getComponentsPatch(), hash);
			}
			// 通用路径：哈希用于快速分桶，完整组件补丁用于最终等价性判断。
			return new CacheKey(item, null, stack.getComponentsPatch(), ItemStack.hashItemAndComponents(stack));
		}

		@Override
		public int hashCode() {
			return cachedHash;
		}
	}

	/**
	 * 静态共享 LRU 缓存（synchronizedMap 提供防御性线程安全）
	 * <p>
	 * Value 用 {@link Optional} 包装，支持缓存"无配方"结果（{@link Optional#empty()}），
	 * 避免对没有配方的输入物品每 tick 重复全量遍历。
	 */
	private static final Map<CacheKey, Optional<RecipeHolder<CentrifugeRecipe>>> CACHE =
			Collections.synchronizedMap(new LinkedHashMap<CacheKey, Optional<RecipeHolder<CentrifugeRecipe>>>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<CacheKey, Optional<RecipeHolder<CentrifugeRecipe>>> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			});

	private SharedPbRecipeCache() {
		// 工具类禁止实例化
	}

	/**
	 * 查询缓存
	 *
	 * @param input 输入物品
	 * @return {@code null}=未命中；{@code Optional.empty()}=命中"无配方"；{@code Optional.of}=命中具体配方
	 */
	@Nullable
	public static Optional<RecipeHolder<CentrifugeRecipe>> get(ItemStack input) {
		return CACHE.get(CacheKey.of(input));
	}

	/**
	 * 存入缓存（recipe 为 null 时缓存"无配方"结果）
	 *
	 * @param input  输入物品
	 * @param recipe 配方 Holder，null 表示无配方
	 */
	public static void put(ItemStack input, @Nullable RecipeHolder<CentrifugeRecipe> recipe) {
		CACHE.put(CacheKey.of(input), recipe != null ? Optional.of(recipe) : Optional.empty());
	}

	/**
	 * 清空全部缓存 — 配方/标签重载（{@code ProductiveBeesGenesis#onTagsReload}）
	 * 与服务器停止（{@code onServerStopped}）时调用，防止跨存档残留。
	 */
	public static void invalidate() {
		CACHE.clear();
	}

	/** 当前缓存条目数（测试用） */
	public static int size() {
		return CACHE.size();
	}
}
