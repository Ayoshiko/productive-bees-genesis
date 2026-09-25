package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 蜜蜂产物配方查询与缓存（纯静态，无状态）
 * <br/>
 * 从 {@link BeeInfoHelper} 拆分而来，职责（SRP）：维护 AdvancedBeehiveRecipe 索引
 * 与配方输出表缓存，提供产物查询/展示列表查询，不涉及显示名称、图标等展示逻辑。
 * <p>
 * 线程安全：索引与输出表缓存均通过 volatile 引用 / ConcurrentHashMap 发布，
 * 读线程无需锁；重载事件通过 {@link #invalidate()} 原子失效。
 */
final class BeeProduceQueries {

	/**
	 * 不可变快照：封装 AdvancedBeehiveRecipe 索引
	 * <p>
	 * 通过单一 volatile 引用原子替换，保证读线程看到一致状态。
	 * 替代旧版 getBeeProduce 中的 O(N) 全量遍历，将 GUI 打开时 N 个蜜蜂的产物查询
	 * 从 O(N²) 降为 O(N)。
	 */
	private record AdvancedBeehiveRecipeIndex(
			Map<String, RecipeHolder<AdvancedBeehiveRecipe>> byBeeType,
			Map<ResourceLocation, List<RecipeHolder<AdvancedBeehiveRecipe>>> allByBeeType,
			boolean complete) {
		static final AdvancedBeehiveRecipeIndex EMPTY =
				new AdvancedBeehiveRecipeIndex(Map.of(), Map.of(), false);
	}

	/** 当前配方索引 — volatile 引用保证原子替换 */
	private static volatile AdvancedBeehiveRecipeIndex beehiveRecipeIndex =
			AdvancedBeehiveRecipeIndex.EMPTY;
	private static net.minecraft.world.item.crafting.RecipeManager indexedManager;
	private static long nextIndexRetryTick = Long.MIN_VALUE;
	private static final long INDEX_RETRY_TICKS = 20L;

	/**
	 * 配方输出表缓存 — 缓存 AdvancedBeehiveRecipe.getRecipeOutputs() 结果
	 * <br/>
	 * PB 的 getRecipeOutputs() 每次新建 LinkedHashMap，缓存避免重复创建。
	 * Key: 蜜蜂类型键 ResourceLocation；Value: 不可变的 ItemStack -> ChancedOutput 映射
	 * <p>
	 * 缓存值为 {@link Collections#unmodifiableMap} 包装，防止外部修改污染静态共享缓存。
	 */
	private static final Map<ResourceLocation, Map<ItemStack, ChancedOutput>> recipeOutputsCache =
			new ConcurrentHashMap<>();

	private BeeProduceQueries() {
	}

	/** 蜂箱配方覆盖固定实体蜂与可配置蜂，不以离心机可处理性筛选。 */
	static List<ResourceLocation> getBeeTypesWithProduce(Level level) {
		ensureRecipeIndex(level);
		return beehiveRecipeIndex.byBeeType.keySet().stream().map(ResourceLocation::parse).sorted().toList();
	}

	/** 万象枚举全部配方；普通蜂箱仍使用首条匹配配方，避免改变原有产量。 */
	static List<ItemStack> getAllBeeProduce(Level level, ResourceLocation beeType) {
		ensureRecipeIndex(level);
		List<ItemStack> outputs = new ArrayList<>();
		for (RecipeHolder<AdvancedBeehiveRecipe> holder : beehiveRecipeIndex.allByBeeType
				.getOrDefault(beeType, List.of())) {
			try {
				for (var entry : holder.value().getRecipeOutputs().entrySet()) {
					if (entry.getValue().chance() > 0 && entry.getValue().max() >= 1 && !entry.getKey().isEmpty()) {
						outputs.add(entry.getKey().copyWithCount(1));
					}
				}
			} catch (RuntimeException e) {
				markIndexIncomplete();
				LogThrottle.warn("myriad_recipe_outputs", "读取万象蜜脾配方 {} 失败，将重试: {}", holder.id(), e.toString());
			}
		}
		return outputs;
	}

	static boolean isProduceIndexComplete() {
		return beehiveRecipeIndex.complete;
	}

	/** 部分就绪时按世界时间退避；数据重载和世界切换仍立即重建。 */
	private static synchronized void ensureRecipeIndex(Level level) {
		if (indexedManager != level.getRecipeManager()) {
			invalidate();
			indexedManager = level.getRecipeManager();
		}
		if (!beehiveRecipeIndex.complete && level.getGameTime() >= nextIndexRetryTick) {
			nextIndexRetryTick = level.getGameTime() + INDEX_RETRY_TICKS;
			rebuildBeehiveRecipeIndex(level);
		}
	}

	private static void markIndexIncomplete() {
		AdvancedBeehiveRecipeIndex index = beehiveRecipeIndex;
		if (index.complete) {
			beehiveRecipeIndex = new AdvancedBeehiveRecipeIndex(index.byBeeType, index.allByBeeType, false);
		}
	}

	/**
	 * 查询指定蜜蜂类型的产物配方输出表
	 * <p>
	 * 优先通过静态索引 O(1) 查找配方；索引未建立时回退到全量遍历并构建索引。
	 * <p>
	 * 返回 {@code Map<ItemStack, ChancedOutput>} 原始配方数据，不执行概率检查；
	 * 概率判定统一由 {@link com.ayoshiko.productivebeesgenesis.apiary.BeeProduceBatchSampler} 处理。
	 *
	 * @param level   世界实例（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @return 配方输出表（ItemStack -> ChancedOutput），可能为空
	 */
	@Nonnull
	static Map<ItemStack, ChancedOutput> getBeeProduce(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		try {
			ensureRecipeIndex(level);
			// 1. 优先查配方输出表缓存（避免 getRecipeOutputs() 每次新建 LinkedHashMap）
			Map<ItemStack, ChancedOutput> cached = recipeOutputsCache.get(beeType);
			if (cached != null) return cached;

			String beeTypeKey = BeeTypeNormalizer.resolveLoadedBeeType(beeType).toString();
			// 2. 优先走索引（O(1)）
			RecipeHolder<AdvancedBeehiveRecipe> matched = beehiveRecipeIndex.byBeeType.get(beeTypeKey);
			if (matched == null) {
				return Map.of();
			}
			// 返回配方原始输出表，不执行概率检查（由 BeeProduceBatchSampler 统一处理）
			Map<ItemStack, ChancedOutput> outputs = matched.value().getRecipeOutputs();
			// 缓存不可变视图，防止外部修改污染静态共享缓存
			Map<ItemStack, ChancedOutput> immutable = Collections.unmodifiableMap(outputs);
			recipeOutputsCache.put(beeType, immutable);
			return immutable;
		} catch (Exception e) {
			markIndexIncomplete();
			LogThrottle.warn("bee_recipe_outputs", "查询蜜蜂产物配方失败 {}: {}", beeType, e.toString());
			return Map.of();
		}
	}

	/**
	 * 查询指定蜜蜂类型的产物 ItemStack 列表（显示用途）
	 * <p>
	 * 从 {@link #getBeeProduce} 返回的原始配方 Map 转换为 ItemStack 列表，
	 * 取 {@code chancedOutput.max()} 作为代表数量。仅供 GUI 显示使用，不参与实际产出计算。
	 *
	 * @param level   世界实例（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @return 产物列表（取 max 代表值），可能为空
	 */
	@Nonnull
	static List<ItemStack> getBeeProduceStacks(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		Map<ItemStack, ChancedOutput> outputs = getBeeProduce(level, beeType);
		if (outputs.isEmpty()) return List.of();
		List<ItemStack> result = new ArrayList<>(outputs.size());
		outputs.forEach((stack, chancedOutput) -> {
			ItemStack copy = stack.copy();
			copy.setCount(Math.max(1, (int) chancedOutput.max()));
			result.add(copy);
		});
		return result;
	}

	/**
	 * 重建 AdvancedBeehiveRecipe 索引
	 * <p>
	 * 遍历全部配方，从每个配方的 ingredient（{@code Supplier<BeeIngredient>}）中提取 beeType，
	 * 构建 {@code beeType -> recipe} 映射。完成后发布为不可变快照，保证后续读取的线程安全。
	 * 单条配方解析失败不影响整体索引。
	 *
	 * @param level 世界实例
	 */
	private static void rebuildBeehiveRecipeIndex(@Nonnull Level level) {
		try {
			List<RecipeHolder<AdvancedBeehiveRecipe>> recipes = level.getRecipeManager()
					.getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get());
			Map<String, RecipeHolder<AdvancedBeehiveRecipe>> newIndex = new HashMap<>(recipes.size() * 2);
			Map<ResourceLocation, List<RecipeHolder<AdvancedBeehiveRecipe>>> allRecipes = new HashMap<>();
			boolean complete = true;
			for (RecipeHolder<AdvancedBeehiveRecipe> recipe : recipes) {
				try {
					// AdvancedBeehiveRecipe.ingredient 是 Supplier<BeeIngredient>，
					// 通过 supplier.get() 获取 BeeIngredient 后调用 getBeeType() 提取 beeType
					BeeIngredient ing = recipe.value().ingredient.get();
					if (ing == null) {
						complete = false;
						continue;
					}
					ResourceLocation beeType = ing.getBeeType();
					if (beeType == null) {
						complete = false;
						continue;
					}
					newIndex.putIfAbsent(beeType.toString(), recipe);
					allRecipes.computeIfAbsent(beeType, ignored -> new ArrayList<>()).add(recipe);
				} catch (Exception e) {
					complete = false;
					LogThrottle.warn("bee_recipe_index", "构建蜂箱配方索引时无法解析 {}，将重试: {}", recipe.id(), e.toString());
				}
			}
			// 原子替换：发布不可变快照
			allRecipes.replaceAll((type, holders) -> List.copyOf(holders));
			recipeOutputsCache.clear();
			beehiveRecipeIndex = new AdvancedBeehiveRecipeIndex(Map.copyOf(newIndex), Map.copyOf(allRecipes), complete);
		} catch (Exception e) {
			markIndexIncomplete();
			LogThrottle.warn("bee_recipe_index_rebuild", "重建蜂箱配方索引失败，将重试: {}", e.toString());
		}
	}

	/** 失效索引与输出表缓存（数据包/标签重载时调用） */
	static void invalidate() {
		beehiveRecipeIndex = AdvancedBeehiveRecipeIndex.EMPTY;
		indexedManager = null;
		nextIndexRetryTick = Long.MIN_VALUE;
		recipeOutputsCache.clear();
	}
}
