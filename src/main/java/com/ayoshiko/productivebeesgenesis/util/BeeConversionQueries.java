package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.BlockConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 索引在游戏线程读取当前 RecipeManager，局部构建成功后一次发布不可变快照。
 * 整体读取失败不发布本轮结果；单条坏配方隔离并保留重试，失效与停服释放管理器引用。
 * <p>
 * 失效时机：{@link #invalidate()} 在 {@code ProductiveBeesGenesis.acceptRecipeManager} 接收成功代际后调用，
 * 与 {@link BeeProduceQueries#invalidate()} 生命周期一致。
 */
public final class BeeConversionQueries {

	private record ConversionIndex(
			RecipeManager manager,
			Map<String, List<RecipeHolder<ItemConversionRecipe>>> items,
			Map<String, List<RecipeHolder<BlockConversionRecipe>>> blocks,
			boolean complete) {
		private static final ConversionIndex EMPTY = new ConversionIndex(null, Map.of(), Map.of(), false);
	}

	private static volatile ConversionIndex index = ConversionIndex.EMPTY;
	private static volatile int version;
	private static long lastFailedTick = Long.MIN_VALUE;
	private static final long RETRY_INTERVAL_TICKS = 100L;

	private BeeConversionQueries() {
	}

	/** 游戏线程按当前管理器构建；同一失败代际最多每 100 tick 重试一次。 */
	public static void ensureLoaded(@Nullable Level level) {
		if (level == null) {
			return;
		}
		RecipeManager manager = level.getRecipeManager();
		if (manager == null) {
			return;
		}
		ConversionIndex current = index;
		if (current.manager == manager && current.complete) {
			return;
		}
		synchronized (BeeConversionQueries.class) {
			if (index.manager != manager) {
				index = new ConversionIndex(manager, Map.of(), Map.of(), false);
				lastFailedTick = Long.MIN_VALUE;
				version++;
			}
			if (index.complete) {
				return;
			}
			long tick = level.getGameTime();
			if (lastFailedTick != Long.MIN_VALUE && tick >= lastFailedTick
					&& tick - lastFailedTick < RETRY_INTERVAL_TICKS) {
				return;
			}
			try {
				Map<String, List<RecipeHolder<ItemConversionRecipe>>> items = new HashMap<>();
				Map<String, List<RecipeHolder<BlockConversionRecipe>>> blocks = new HashMap<>();
				boolean complete = true;
				for (RecipeHolder<ItemConversionRecipe> holder :
						manager.getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get())) {
					ItemConversionRecipe recipe = holder.value();
					if (recipe == null || recipe.bees == null) {
						continue;
					}
					try {
						for (String beeType : beeTypes(recipe.bees)) {
							items.computeIfAbsent(beeType, key -> new ArrayList<>(1)).add(holder);
						}
					} catch (RuntimeException failure) {
						complete = false;
						LogThrottle.warn("bee_conversion_item_index",
								"物品转化配方 {} 的蜜蜂解析失败，保留其它配方并等待重试", holder.id(), failure);
					}
				}
				for (RecipeHolder<BlockConversionRecipe> holder :
						manager.getAllRecipesFor(ModRecipeTypes.BLOCK_CONVERSION_TYPE.get())) {
					BlockConversionRecipe recipe = holder.value();
					if (recipe == null || recipe.bees == null) {
						continue;
					}
					try {
						for (String beeType : beeTypes(recipe.bees)) {
							blocks.computeIfAbsent(beeType, key -> new ArrayList<>(1)).add(holder);
						}
					} catch (RuntimeException failure) {
						complete = false;
						LogThrottle.warn("bee_conversion_block_index",
								"方块转化配方 {} 的蜜蜂解析失败，保留其它配方并等待重试", holder.id(), failure);
					}
				}
				items.replaceAll((key, value) -> List.copyOf(value));
				blocks.replaceAll((key, value) -> List.copyOf(value));
				index = new ConversionIndex(manager, Map.copyOf(items), Map.copyOf(blocks), complete);
				lastFailedTick = complete ? Long.MIN_VALUE : tick;
				version++;
			} catch (RuntimeException failure) {
				lastFailedTick = tick;
				LogThrottle.error("bee_conversion_index_build",
						"BeeConversionQueries 转化配方索引构建失败，将在 5 秒后重试", failure);
			}
		}
	}

	private static List<String> beeTypes(List<Supplier<BeeIngredient>> bees) {
		List<String> types = new ArrayList<>(bees.size());
		for (Supplier<BeeIngredient> bee : bees) {
			BeeIngredient ingredient = bee.get();
			if (ingredient != null) {
				types.add(ingredient.getBeeType().toString());
			}
		}
		return types;
	}

	/** 接收新配方代际或停服时释放快照，旧世界失败时间不得阻止下一代重建。 */
	public static synchronized void invalidate() {
		index = ConversionIndex.EMPTY;
		lastFailedTick = Long.MIN_VALUE;
		version++;
	}

	/**
	 * 获取当前配方版本号
	 * <br/>
	 * {@link FeederSlotManager} 的花朵有效性缓存据此在配方重载后失效，
	 * 避免转化原料类花朵判定使用过期结果。
	 *
	 * @return 展示版本号（失效、管理器切换及成功构建后递增）
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
		ConversionIndex current = index;
		return !current.items.getOrDefault(key, List.of()).isEmpty()
				|| !current.blocks.getOrDefault(key, List.of()).isEmpty();
	}

	/**
	 * 快速路径：该蜜蜂类型是否注册了物品转化配方（item_conversion）
	 *
	 * @param beeType 蜜蜂类型键
	 * @return true 表示存在至少一个物品转化配方
	 */
	public static boolean hasItemConversionRecipes(@Nonnull ResourceLocation beeType) {
		return !index.items.getOrDefault(beeType.toString(), List.of()).isEmpty();
	}

	/**
	 * 快速路径：该蜜蜂类型是否注册了方块转化配方（block_conversion）
	 *
	 * @param beeType 蜜蜂类型键
	 * @return true 表示存在至少一个方块转化配方
	 */
	public static boolean hasBlockConversionRecipes(@Nonnull ResourceLocation beeType) {
		return !index.blocks.getOrDefault(beeType.toString(), List.of()).isEmpty();
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
		List<RecipeHolder<ItemConversionRecipe>> recipes = index.items.get(key);
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
		List<RecipeHolder<BlockConversionRecipe>> recipes = index.blocks.get(key);
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
			BeeIngredient ingredient = bee.get();
			if (ingredient != null && ingredient.getBeeType().toString().equals(beeTypeKey)) {
				return true;
			}
		}
		return false;
	}
}
