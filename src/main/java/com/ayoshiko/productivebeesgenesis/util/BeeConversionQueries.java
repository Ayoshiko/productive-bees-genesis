package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.BlockConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * PB 物品转化 / 方块转化配方索引
 * <br/>
 * 机械蜂箱（Mek Apiary）内置模拟升级需要复刻 PB 原版蜂箱的转化行为：
 * <ul>
 *   <li><b>物品转化</b>（{@code productivebees:item_conversion}，如烈焰蜜蜂 + 黑曜石蜜蜂刷怪蛋 → 无限蜜蜂刷怪蛋）：
 *       由 {@link FeederSlotManager} 将转化原料视为有效花朵，由 {@code ApiaryConversionProcessor} 在喂食槽中执行转化。</li>
 *   <li><b>方块转化</b>（{@code productivebees:block_conversion}）：
 *       以饲养板（食料板）中的 BlockItem 为转化目标，在生产周期内按概率转化。</li>
 * </ul>
 * 本类按蜜蜂类型键（{@code ResourceLocation.toString()}，如 {@code productivebees:blazing}）索引配方，
 * 匹配逻辑逐行对齐 PB 的 {@link ItemConversionRecipe#matches} / {@link BlockConversionRecipe#matches}，
 * 但不构造真实蜜蜂实体（避免每 tick 实体创建开销），仅比较蜜蜂类型键与物品/方块状态。
 * <p>
 * 线程安全：缓存由服务端 tick 线程与配方重载事件（主线程）访问，使用 synchronized 保护写入；
 * 读路径（findXxx）在 tick 线程串行调用，配合 volatile loaded 标志保证发布可见性。
 * <p>
 * 失效时机：{@link #invalidate()} 在 {@code ProductiveBeesGenesis.onTagsReload}（标签/配方重载完成）调用，
 * 与 {@link BeeProduceQueries#invalidate()} 生命周期一致。
 */
public final class BeeConversionQueries {

	/** 物品转化配方索引：蜜蜂类型键 → 配方列表 */
	private static final Map<String, List<RecipeHolder<ItemConversionRecipe>>> ITEM_RECIPES_BY_BEE =
			new ConcurrentHashMap<>();

	/** 方块转化配方索引：蜜蜂类型键 → 配方列表 */
	private static final Map<String, List<RecipeHolder<BlockConversionRecipe>>> BLOCK_RECIPES_BY_BEE =
			new ConcurrentHashMap<>();

	/** 索引是否已构建（防重复全量遍历） */
	private static volatile boolean loaded = false;

	/** 配方版本号 — 每次 {@link #invalidate()} 递增，供花朵有效性缓存失效判断 */
	private static volatile int version = 0;

	/** 上次构建失败的 gameTick — 失败后每 100 tick（5 秒）重试一次，避免每 tick 全量遍历+日志刷屏 */
	private static volatile long lastFailedTick = Long.MIN_VALUE;

	/** 构建失败重试间隔（tick） */
	private static final long RETRY_INTERVAL_TICKS = 100L;

	private BeeConversionQueries() {
	}

	/**
	 * 确保配方索引已构建（幂等，线程安全）
	 * <br/>
	 * 从 RecipeManager 全量加载 item_conversion / block_conversion 配方并按蜜蜂类型分组。
	 *
	 * @param level 世界实例（配方管理器来源）
	 */
	public static void ensureLoaded(@Nullable Level level) {
		if (loaded) {
			return;
		}
		synchronized (BeeConversionQueries.class) {
			if (loaded) {
				return;
			}
			try {
				if (level == null || level.getRecipeManager() == null) {
					return;
				}
				// 上次构建失败后的节流：每 RETRY_INTERVAL_TICKS 才重试一次全量遍历
				if (lastFailedTick != Long.MIN_VALUE
						&& level.getGameTime() - lastFailedTick < RETRY_INTERVAL_TICKS) {
					return;
				}
				for (RecipeHolder<ItemConversionRecipe> holder :
						level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get())) {
					ItemConversionRecipe recipe = holder.value();
					if (recipe == null || recipe.bees == null) {
						continue;
					}
					for (Supplier<BeeIngredient> bee : recipe.bees) {
						try {
							BeeIngredient ingredient = bee.get();
							if (ingredient != null) {
								ITEM_RECIPES_BY_BEE
										.computeIfAbsent(ingredient.getBeeType().toString(), k -> new ArrayList<>(1))
										.add(holder);
							}
						} catch (Exception ignored) {
							// 单个蜜蜂类型解析失败不影响其他配方
						}
					}
				}
				for (RecipeHolder<BlockConversionRecipe> holder :
						level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.BLOCK_CONVERSION_TYPE.get())) {
					BlockConversionRecipe recipe = holder.value();
					if (recipe == null || recipe.bees == null) {
						continue;
					}
					for (Supplier<BeeIngredient> bee : recipe.bees) {
						try {
							BeeIngredient ingredient = bee.get();
							if (ingredient != null) {
								BLOCK_RECIPES_BY_BEE
										.computeIfAbsent(ingredient.getBeeType().toString(), k -> new ArrayList<>(1))
										.add(holder);
							}
						} catch (Exception ignored) {
							// 同上
						}
					}
				}
				// 冻结为不可变列表，防止后续误修改并提升并发读安全
				ITEM_RECIPES_BY_BEE.replaceAll((k, v) -> List.copyOf(v));
				BLOCK_RECIPES_BY_BEE.replaceAll((k, v) -> List.copyOf(v));
				loaded = true;
			} catch (Exception e) {
				// 节流日志（静态 key 全局 5 秒一次），并记录失败 tick 供下次重试判定
				lastFailedTick = level.getGameTime();
				LogThrottle.error("bee_conversion_index_build",
						"BeeConversionQueries 转化配方索引构建失败，将在 5 秒后重试", e);
			}
		}
	}

	/**
	 * 失效全部配方索引并递增版本号（配方重载时调用）
	 * <br/>
	 * 由 {@code ProductiveBeesGenesis.onTagsReload} 在标签/配方重载完成后调用，
	 * 与产出配方缓存（{@link BeeProduceQueries#invalidate()}）保持同一生命周期。
	 */
	public static void invalidate() {
		synchronized (BeeConversionQueries.class) {
			ITEM_RECIPES_BY_BEE.clear();
			BLOCK_RECIPES_BY_BEE.clear();
			loaded = false;
			version++;
		}
	}

	/**
	 * 获取当前配方版本号
	 * <br/>
	 * {@link FeederSlotManager} 的花朵有效性缓存据此在配方重载后失效，
	 * 避免转化原料类花朵判定使用过期结果。
	 *
	 * @return 配方版本号（每次 invalidate 递增）
	 */
	public static int getVersion() {
		return version;
	}

	/**
	 * 快速路径：该蜜蜂类型是否注册了任何物品/方块转化配方
	 *
	 * @param beeType 蜜蜂类型键
	 * @return true 表示存在至少一个转化配方
	 */
	public static boolean hasAnyConversionRecipe(@Nonnull ResourceLocation beeType) {
		String key = beeType.toString();
		return !ITEM_RECIPES_BY_BEE.getOrDefault(key, List.of()).isEmpty()
				|| !BLOCK_RECIPES_BY_BEE.getOrDefault(key, List.of()).isEmpty();
	}

	/**
	 * 快速路径：该蜜蜂类型是否注册了物品转化配方（item_conversion）
	 *
	 * @param beeType 蜜蜂类型键
	 * @return true 表示存在至少一个物品转化配方
	 */
	public static boolean hasItemConversionRecipes(@Nonnull ResourceLocation beeType) {
		return !ITEM_RECIPES_BY_BEE.getOrDefault(beeType.toString(), List.of()).isEmpty();
	}

	/**
	 * 快速路径：该蜜蜂类型是否注册了方块转化配方（block_conversion）
	 *
	 * @param beeType 蜜蜂类型键
	 * @return true 表示存在至少一个方块转化配方
	 */
	public static boolean hasBlockConversionRecipes(@Nonnull ResourceLocation beeType) {
		return !BLOCK_RECIPES_BY_BEE.getOrDefault(beeType.toString(), List.of()).isEmpty();
	}

	/**
	 * 检查喂食槽物品是否为该蜜蜂的转化花朵
	 * <br/>
	 * 对齐 PB {@code ProductiveBee.isFlowerItem}（物品转化原料）与
	 * {@code isFlowerBlock}（BlockItem 方块转化原料）：
	 * 转化原料放入饲养板后，蜜蜂无需额外花朵即可工作。
	 *
	 * @param beeType 蜜蜂类型键
	 * @param stack   喂食槽物品
	 * @return true 如果该物品是该蜜蜂的物品转化原料或方块转化原料
	 */
	public static boolean hasFeederConversionFlower(@Nonnull ResourceLocation beeType, @Nonnull ItemStack stack) {
		return findItemConversionRecipe(beeType, stack) != null
				|| findBlockConversionRecipeForItem(beeType, stack) != null;
	}

	/**
	 * 查找匹配的物品转化配方（物品 → 物品）
	 *
	 * @param beeType 蜜蜂类型键
	 * @param stack   待检查物品
	 * @return 匹配的配方，无则 null
	 */
	@Nullable
	public static RecipeHolder<ItemConversionRecipe> findItemConversionRecipe(
			@Nonnull ResourceLocation beeType, @Nonnull ItemStack stack) {
		if (stack.isEmpty() || beeType == null) {
			return null;
		}
		String key = beeType.toString();
		List<RecipeHolder<ItemConversionRecipe>> recipes = ITEM_RECIPES_BY_BEE.get(key);
		if (recipes == null || recipes.isEmpty()) {
			return null;
		}
		for (RecipeHolder<ItemConversionRecipe> holder : recipes) {
			try {
				ItemConversionRecipe recipe = holder.value();
				if (recipe == null || recipe.bees == null || recipe.ingredient == null) {
					continue;
				}
				if (matchesBee(recipe.bees, key) && recipe.ingredient.test(stack)) {
					return holder;
				}
			} catch (Exception e) {
				// 单配方异常隔离：第三方畸形配方不影响其他配方与整体产出流程
				LogThrottle.warn("bee_conversion_item_recipe",
						"物品转化配方 {} 匹配时异常，已跳过该配方", holder.id(), e);
			}
		}
		return null;
	}


	/**
	 * 查找匹配的方块转化配方（方块 → 方块）
	 * <br/>
	 * 复制 PB {@link BlockConversionRecipe#matches} 的判定逻辑（含 input 为空时的
	 * {@code state.equals(stateFrom) || defaultState.equals(stateFrom)} 回退），
	 * 避免为每次判定构造真实蜜蜂实体。
	 *
	 * @param beeType 蜜蜂类型键
	 * @param state   待检查方块状态
	 * @return 匹配的配方，无则 null
	 */
	@Nullable
	public static RecipeHolder<BlockConversionRecipe> findBlockConversionRecipe(
			@Nonnull ResourceLocation beeType, @Nullable BlockState state) {
		if (state == null || beeType == null) {
			return null;
		}
		String key = beeType.toString();
		List<RecipeHolder<BlockConversionRecipe>> recipes = BLOCK_RECIPES_BY_BEE.get(key);
		if (recipes == null || recipes.isEmpty()) {
			return null;
		}
		for (RecipeHolder<BlockConversionRecipe> holder : recipes) {
			try {
				BlockConversionRecipe recipe = holder.value();
				if (recipe == null || recipe.bees == null) {
					continue;
				}
				if (!matchesBee(recipe.bees, key)) {
					continue;
				}
				boolean matchesBlock;
				if (recipe.input != null && !recipe.input.isEmpty()) {
					// 使用 input 原料的配方：目标方块不能与产物相同，且原料匹配
					matchesBlock = !state.getBlock().equals(recipe.stateTo.getBlock())
							&& recipe.input.test(new ItemStack(state.getBlock()));
				} else {
					// 使用 from 方块状态的配方：精确状态或默认状态匹配
					matchesBlock = state.equals(recipe.stateFrom)
							|| state.getBlock().defaultBlockState().equals(recipe.stateFrom);
				}
				if (matchesBlock) {
					return holder;
				}
			} catch (Exception e) {
				// 单配方异常隔离：第三方畸形配方不影响其他配方与整体产出流程
				LogThrottle.warn("bee_conversion_block_recipe",
						"方块转化配方 {} 匹配时异常，已跳过该配方", holder.id(), e);
			}
		}
		return null;
	}

	/**
	 * 查找喂食槽中 BlockItem 对应的方块转化配方
	 * <br/>
	 * 对齐 PB {@code ProductiveBee.postPollinate} 中 Feeder 内 BlockItem 的转化分支：
	 * 用 BlockItem 的默认方块状态匹配配方，产物为 {@code stateTo} 方块的物品。
	 *
	 * @param beeType 蜜蜂类型键
	 * @param stack   待检查物品（必须为 BlockItem）
	 * @return 匹配的配方，无则 null
	 */
	@Nullable
	public static RecipeHolder<BlockConversionRecipe> findBlockConversionRecipeForItem(
			@Nonnull ResourceLocation beeType, @Nonnull ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return null;
		}
		return findBlockConversionRecipe(beeType, blockItem.getBlock().defaultBlockState());
	}

	/**
	 * 判断蜜蜂类型键是否在配方的 bees 列表中
	 *
	 * @param bees       配方蜜蜂列表
	 * @param beeTypeKey 蜜蜂类型键字符串
	 * @return true 如果匹配
	 */
	private static boolean matchesBee(List<Supplier<BeeIngredient>> bees, String beeTypeKey) {
		for (Supplier<BeeIngredient> bee : bees) {
			try {
				BeeIngredient ingredient = bee.get();
				if (ingredient != null && ingredient.getBeeType().toString().equals(beeTypeKey)) {
					return true;
				}
			} catch (Exception ignored) {
				// 单个蜜蜂类型解析失败不影响其他条目
			}
		}
		return false;
	}
}
