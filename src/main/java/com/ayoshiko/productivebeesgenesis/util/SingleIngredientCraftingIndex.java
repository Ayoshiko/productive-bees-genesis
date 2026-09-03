package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单原料合成配方索引 — {@code 输入物品 → 配方产物} 的 O(1) 查找
 * <br/>
 * 复刻 PB 原版 {@code BeeHelper.getRecipeOutputFromInput} 的查找语义：在全部合成配方中查找
 * “恰好一个原料、且该原料首个候选物品等于输入物品”的配方，返回其产出。
 * <p>
 * <b>动机</b>：原实现每次调用都执行 {@code getAllRecipesFor(RecipeType.CRAFTING)} 全量遍历，
 * 并对每个单原料配方调用 {@link Ingredient#getItems()}（含标签展开与栈数组构造）。
 * 染料蜜蜂在每个产出周期都会触发该扫描，整合包配方规模下属于 tick 路径上的 O(全部合成配方) 开销。
 * 索引把这份成本一次性前移到配方重载后的首次查询。
 * <p>
 * <b>语义等价</b>：构建时按 {@code getAllRecipesFor} 的迭代顺序 {@code putIfAbsent}，保留原实现
 * “首个匹配配方胜出”的语义；产出为空的配方不入索引，等价于原实现“结果为空则继续查找下一条”。
 * <p>
 * <b>线程安全</b>：不可变快照 + 单一 volatile 引用，读线程始终看到一致状态；构建与失效在
 * {@code synchronized} 块内完成，构建期间旧快照继续服务读请求。
 * <p>
 * <b>失效时机</b>：{@link #invalidate()} 由 {@code ProductiveBeesGenesis.onTagsReload} 调用，
 * 与 {@link BeeConversionQueries#invalidate()} 生命周期一致。构建失败按
 * {@link #RETRY_INTERVAL_TICKS} 节流重试，避免失败后每次查询都重跑全量遍历。
 */
public final class SingleIngredientCraftingIndex {

	/** 不可变快照 — 输入物品 → 配方产出（存放规范实例，对外一律返回副本） */
	private record Snapshot(Map<Item, ItemStack> resultByInput) {
		static final Snapshot EMPTY = new Snapshot(Map.of());
	}

	/** 当前快照 — volatile 引用保证原子替换 */
	private static volatile Snapshot snapshot = Snapshot.EMPTY;

	/** 索引是否已构建（防止空索引场景下每次查询都重跑全量遍历） */
	private static volatile boolean loaded = false;

	/** 上次构建失败的 gameTick — 失败后按 {@link #RETRY_INTERVAL_TICKS} 重试 */
	private static volatile long lastFailedTick = Long.MIN_VALUE;

	/** 构建失败重试间隔（tick）— 100 tick（5 秒），与 {@link BeeConversionQueries} 一致 */
	private static final long RETRY_INTERVAL_TICKS = 100L;

	private SingleIngredientCraftingIndex() {
		// 工具类禁止实例化
	}

	/**
	 * 查询单原料合成配方的产出
	 *
	 * @param level 世界实例（配方管理器与注册表来源），为 null 返回空栈
	 * @param input 输入物品，为空栈返回空栈
	 * @return 配方产出的副本，无匹配配方时返回 {@link ItemStack#EMPTY}
	 */
	@Nonnull
	public static ItemStack resolve(@Nullable Level level, @Nullable ItemStack input) {
		if (level == null || input == null || input.isEmpty()) return ItemStack.EMPTY;
		ensureLoaded(level);
		ItemStack result = snapshot.resultByInput.get(input.getItem());
		// 返回副本：索引持有的是跨调用共享的规范实例，不能交给调用方修改
		return result == null ? ItemStack.EMPTY : result.copy();
	}

	/**
	 * 失效索引（配方/标签重载时调用）
	 * <br/>
	 * 同时清除失败重试节流，使重载后能立即重建。
	 */
	public static void invalidate() {
		synchronized (SingleIngredientCraftingIndex.class) {
			snapshot = Snapshot.EMPTY;
			loaded = false;
			lastFailedTick = Long.MIN_VALUE;
		}
	}

	/**
	 * 确保索引已构建（幂等，线程安全）
	 * <br/>
	 * 双重检查：{@code loaded} 为 volatile，未构建时进入同步块再次确认，避免重复全量遍历。
	 */
	private static void ensureLoaded(@Nonnull Level level) {
		if (loaded) return;
		synchronized (SingleIngredientCraftingIndex.class) {
			if (loaded) return;
			// 上次构建失败后的节流：未到重试间隔直接返回，本次查询按“无匹配”处理
			if (lastFailedTick != Long.MIN_VALUE
					&& level.getGameTime() - lastFailedTick < RETRY_INTERVAL_TICKS) {
				return;
			}
			try {
				snapshot = new Snapshot(buildIndex(level));
				loaded = true;
				lastFailedTick = Long.MIN_VALUE;
			} catch (RuntimeException e) {
				lastFailedTick = level.getGameTime();
				LogThrottle.error("single_ingredient_crafting_index",
						"单原料合成配方索引构建失败，将在 5 秒后重试", e);
			}
		}
	}

	/**
	 * 全量扫描合成配方构建索引
	 * <br/>
	 * 单个配方解析失败（自定义/动态配方可能在 {@code getItems} 或 {@code getResultItem} 抛异常）
	 * 只跳过该条，不影响整体索引。
	 */
	@Nonnull
	private static Map<Item, ItemStack> buildIndex(@Nonnull Level level) {
		List<RecipeHolder<CraftingRecipe>> recipes =
				level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
		Map<Item, ItemStack> index = new HashMap<>();
		for (RecipeHolder<CraftingRecipe> holder : recipes) {
			try {
				List<Ingredient> ingredients = holder.value().getIngredients();
				if (ingredients.size() != 1) continue;
				ItemStack[] candidates = ingredients.get(0).getItems();
				if (candidates.length == 0) continue;
				ItemStack result = holder.value().getResultItem(level.registryAccess());
				if (result.isEmpty()) continue;
				// putIfAbsent 保留“首个匹配配方胜出”的原语义
				index.putIfAbsent(candidates[0].getItem(), result);
			} catch (RuntimeException e) {
				LogThrottle.warn("single_ingredient_crafting_index_skip",
						"单原料合成配方索引：跳过无法解析的配方 {}", holder.id());
			}
		}
		return Map.copyOf(index);
	}
}
