package com.ayoshiko.productivebeesgenesis.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * 离心配方索引 — O(1) 蜜脾/蜜脾块配方查找
 * <br/>
 * 替代 findPbRecipe 和 createCombBlockRecipe 中的全量遍历 (O(N))，
 * 通过 bee_type -> 配方 的索引实现 O(1) 查找。
 * 同时维护蜜脾块配方索引：rebuild 时根据蜜脾配方静态生成对应的蜜脾块配方
 * （min/max/流体按 {@link com.ayoshiko.productivebeesgenesis.config.ServerConfig#mekCentrifugeCombBlockMultiplier} 缩放），
 * 消除首次遇到新 bee_type 蜜脾块时的动态构建开销。
 * <p>
 * <b>线程安全</b>：使用不可变快照 + 单一 volatile 引用，保证读线程看到一致状态。
 * 重建期间旧快照仍可服务读请求，重建完成后整体替换为新快照。
 * <p>
 * <b>重建时机</b>：由 {@link ProductiveBeesGenesis#onTagsReload} 在 TagsUpdatedEvent 后调用，
 * 与 RECIPE_VERSION 递增同步，确保配方重载后索引立即更新。
 * <p>
 * <b>回退策略</b>：索引未命中时调用方回退到全量遍历（防御性），避免索引构建遗漏导致配方丢失。
 */
public final class CentrifugeRecipeIndex {

	/** 不可变快照：封装蜜脾索引、蜜脾块索引和特殊蜜脾块索引，保证原子替换 */
	private record RecipeIndexSnapshot(
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> index,
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> combBlockIndex,
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> specialCombBlockIndex) {
		static final RecipeIndexSnapshot EMPTY =
				new RecipeIndexSnapshot(Map.of(), Map.of(), Map.of());
	}

	/**
	 * Wax 物品标签 — 复刻 PB 热力离心机 stripWax 行为
	 * <br/>
	 * PB 原版 ModTags.Common.WAXES = c:waxes，蜜脾块离心时过滤此标签的产出。
	 * 蜜脾离心保留 Wax（符合 PB 原版），仅蜜脾块配方派生时过滤。
	 */
	private static final TagKey<Item> WAXES_TAG = TagKey.create(
			Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "waxes"));

	/**
	 * 蜜脾块存储标签 — 复刻 PB ModTags.Common.STORAGE_BLOCK_HONEYCOMBS
	 * <br/>
	 * 标签 {@code c:storage_blocks/honeycombs} 包含 vanilla/PB 所有蜜脾块物品，
	 * 用于识别蜜脾块输入以走特殊蜜脾块配方查找路径。
	 */
	private static final TagKey<Item> STORAGE_BLOCKS_HONEYCOMBS = TagKey.create(
			Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "storage_blocks/honeycombs"));

	/** 当前快照 — volatile 引用保证原子替换 */
	private static volatile RecipeIndexSnapshot snapshot = RecipeIndexSnapshot.EMPTY;

	private CentrifugeRecipeIndex() {
		// 工具类禁止实例化
	}

	/**
	 * 清空索引 — 服务器停止时调用，防止跨存档数据泄漏
	 * <br/>
	 * 将快照原子替换为 EMPTY，释放蜜脾/蜜脾块配方索引引用。
	 * 服务器停止后这些索引不再有用，主动清空可：
	 * <ul>
	 *   <li>防止旧存档的配方数据泄漏到新存档</li>
	 *   <li>释放对 RecipeManager/ServerLevel 的间接引用，便于 GC</li>
	 * </ul>
	 */
	public static void clear() {
		snapshot = RecipeIndexSnapshot.EMPTY;
	}

	/**
	 * 重建索引 — 遍历所有 CentrifugeRecipe，提取 bee_type 建立蜜脾索引，
	 * 同时为每个 bee_type 静态生成蜜脾块配方。
	 * <br/>
	 * 模块 6 修复（v2.4）：识别 PB 原生蜜脾块配方（configurable_comb 输入 + bee_type），
	 * 过滤 Wax 后索引到 combBlockIndex，优先于派生配方。PB 原版有独立的蜜脾块配方
	 * （如 comb_blazing.json 产出 blaze_rod），与单蜜脾配方（honeycomb_blazing.json 产出 blaze_powder）
	 * 产物不同，必须分别索引，不能统一走派生路径。
	 * <p>
	 * 模块 1：无 bee_type 的特殊蜜脾配方（honeycomb_ghostly/milky/powdery/vanilla honeycomb）
	 * 派生为特殊蜜脾块配方，按输入物品 ResourceLocation 索引到 specialCombBlockIndex。
	 * 使用局部 Map 构建完成后整体替换 volatile 引用，保证原子性。
	 * 单条配方解析失败不影响整体索引。
	 *
	 * @param recipeManager 配方管理器（服务端或客户端均可）
	 */
	public static void rebuild(net.minecraft.world.item.crafting.RecipeManager recipeManager) {
		try {
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> newIndex = new HashMap<>();
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> newCombBlockIndex = new HashMap<>();
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> newSpecialCombBlockIndex = new HashMap<>();
			// Bug 1 修复：客户端可能无法访问 SERVER 配置，try-catch 降级到默认值 4
			int multiplier;
			try {
				multiplier = ModConfig.SERVER.mekCentrifugeCombBlockMultiplier.get();
			} catch (Throwable ignored) {
				multiplier = 4;
			}

			// 模块 6 修复（v2.4 最终版）：两遍扫描消除 HashMap 迭代顺序依赖
		// v2.3 单遍扫描 + putIfAbsent 存在 Bug：若单蜜脾配方先于原生蜜脾块配方被处理，
		// 派生配方会先写入 combBlockIndex，后续原生配方因 putIfAbsent 被丢弃，导致产出错误。
		// 两遍扫描确保原生蜜脾块配方始终优先于派生配方。
		List<RecipeHolder<CentrifugeRecipe>> allRecipes = recipeManager
				.getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get());

		// 第一遍：收集所有原生蜜脾块配方（configurable_comb 输入 + bee_type），stripWax 后存入 combBlockIndex
		for (RecipeHolder<CentrifugeRecipe> holder : allRecipes) {
			try {
				ItemStack[] inputItems = holder.value().ingredient.getItems();
				if (inputItems.length == 0) continue;
				ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());
				if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get() && beeType != null) {
					RecipeHolder<CentrifugeRecipe> strippedRecipe = stripWaxFromRecipe(holder);
					if (strippedRecipe != null) {
						// put 覆盖：同 bee_type 多个原生配方时保留最后一个（理论不应出现，但防御性处理）
						newCombBlockIndex.put(beeType, strippedRecipe);
					}
				}
			} catch (Exception e) {
				ProductiveBeesGenesis.LOGGER.warn("离心配方索引：第一遍扫描跳过无法解析的配方 {}", holder.id(), e);
			}
		}

		// 第二遍：处理单蜜脾配方和特殊蜜脾配方
		for (RecipeHolder<CentrifugeRecipe> holder : allRecipes) {
			try {
				ItemStack[] inputItems = holder.value().ingredient.getItems();
				if (inputItems.length == 0) continue;
				ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());

				// 跳过原生蜜脾块配方（第一遍已处理）
				if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get() && beeType != null) {
					continue;
				}

				if (beeType == null) {
					// 模块 1：识别特殊蜜脾配方（无 bee_type 的 honeycomb_ghostly/milky/powdery/vanilla honeycomb）
					Item inputItem = inputItems[0].getItem();
					if (isSpecialHoneycombItem(inputItem)) {
						ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(inputItem);
						if (!newSpecialCombBlockIndex.containsKey(itemId)) {
							// 模块 5 修复：构造对应的蜜脾块 ingredient，而非使用单蜜脾
							Ingredient blockIngredient = getCombBlockIngredient(inputItem);
							if (blockIngredient != null) {
								RecipeHolder<CentrifugeRecipe> blockRecipe =
										deriveCombBlockRecipe(holder, itemId, multiplier, blockIngredient);
								if (blockRecipe != null) {
									newSpecialCombBlockIndex.put(itemId, blockRecipe);
								}
							}
						}
					}
					continue;
				}

				// 同一 bee_type 多个配方时保留首个（putIfAbsent）
				if (newIndex.putIfAbsent(beeType, holder) == null) {
					// 仅当第一遍未收集到原生蜜脾块配方时才派生
					if (!newCombBlockIndex.containsKey(beeType)) {
						// 模块 5 修复：构造带 bee_type 的 configurable_comb 蜜脾块 ingredient
						ItemStack combBlockStack = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
						combBlockStack.set(ModDataComponents.BEE_TYPE.get(), beeType);
						Ingredient blockIngredient = Ingredient.of(combBlockStack);
						RecipeHolder<CentrifugeRecipe> blockRecipe =
								deriveCombBlockRecipe(holder, beeType, multiplier, blockIngredient);
						if (blockRecipe != null) {
							newCombBlockIndex.putIfAbsent(beeType, blockRecipe);
						}
					}
				}
			} catch (Exception e) {
				ProductiveBeesGenesis.LOGGER.warn("离心配方索引：第二遍扫描跳过无法解析的配方 {}", holder.id(), e);
			}
		}
			// 原子替换：使用不可变快照，保证读线程看到一致状态
			snapshot = new RecipeIndexSnapshot(
					Map.copyOf(newIndex), Map.copyOf(newCombBlockIndex), Map.copyOf(newSpecialCombBlockIndex));
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("重建离心配方索引失败", e);
		}
	}

	/**
	 * 过滤蜜脾块配方中的 Wax 产出 — 模块 6 修复
	 * <br/>
	 * PB 原版 {@code HeatedCentrifugeBlockEntity.completeRecipeProcessing} 第 4 参数 stripWax=true，
	 * 蜜脾块离心时过滤 c:waxes 标签的产出（无论直接配方还是 fallback 配方）。
	 * 本方法复刻该行为，对 PB 原生蜜脾块配方过滤 Wax 后重新构建配方。
	 * <p>
	 * 与 {@link #deriveCombBlockRecipe} 的区别：本方法不乘 multiplier（原生配方已是蜜脾块配方），
	 * 仅过滤 Wax；{@code deriveCombBlockRecipe} 用于派生蜜脾块配方（乘 multiplier + 过滤 Wax）。
	 *
	 * @param originalHolder PB 原生蜜脾块配方
	 * @return 过滤 Wax 后的蜜脾块配方，失败返回 null
	 */
	@Nullable
	private static RecipeHolder<CentrifugeRecipe> stripWaxFromRecipe(RecipeHolder<CentrifugeRecipe> originalHolder) {
		try {
			CentrifugeRecipe original = originalHolder.value();
			List<ChancedOutput> strippedOutputs = new ArrayList<>(original.itemOutput.size());
			for (ChancedOutput chanced : original.itemOutput) {
				// 模块 6：过滤 Wax 产出，复刻 PB 热力离心机 stripWax=true 行为
				if (isWaxOutput(chanced)) continue;
				strippedOutputs.add(chanced);
			}
			CentrifugeRecipe strippedRecipe = new CentrifugeRecipe(
					original.ingredient, strippedOutputs, original.fluidOutput, original.getProcessingTime());
			return new RecipeHolder<>(originalHolder.id(), strippedRecipe);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("过滤蜜脾块配方 Wax 失败: recipe={}", originalHolder.id(), e);
			return null;
		}
	}

	/**
	 * 判断物品是否为特殊蜜脾 — 模块 1：用于识别无 bee_type 的特殊蜜脾配方
	 * <br/>
	 * 包含 PB 的 honeycomb_ghostly/milky/powdery 和原版 honeycomb。
	 * configurable_honeycomb 不在此列（它一定有 bee_type，走 normal 索引路径）。
	 *
	 * @param item 待检测物品
	 * @return true 表示为特殊蜜脾
	 */
	private static boolean isSpecialHoneycombItem(Item item) {
		return item == ModItems.HONEYCOMB_GHOSTLY.get()
				|| item == ModItems.HONEYCOMB_MILKY.get()
				|| item == ModItems.HONEYCOMB_POWDERY.get()
				|| item == Items.HONEYCOMB;
	}

	/**
	 * 由特殊单蜜脾物品获取对应的蜜脾块 ingredient — 模块 5 修复
	 * <br/>
	 * 与 {@link SpecialCombBlockRecipeHandler#getSingleComb} 互逆：单蜜脾 → 蜜脾块。
	 * 用于派生特殊蜜脾块配方时构造正确的输入 ingredient，避免 JEI 显示错误的输入物品。
	 *
	 * @param singleCombItem 单蜜脾物品
	 * @return 蜜脾块 ingredient，无法映射返回 null
	 */
	@Nullable
	private static Ingredient getCombBlockIngredient(Item singleCombItem) {
		Item blockItem = null;
		if (singleCombItem == ModItems.HONEYCOMB_GHOSTLY.get()) {
			blockItem = cy.jdkdigital.productivebees.init.ModBlocks.COMB_GHOSTLY.get().asItem();
		} else if (singleCombItem == ModItems.HONEYCOMB_MILKY.get()) {
			blockItem = cy.jdkdigital.productivebees.init.ModBlocks.COMB_MILKY.get().asItem();
		} else if (singleCombItem == ModItems.HONEYCOMB_POWDERY.get()) {
			blockItem = cy.jdkdigital.productivebees.init.ModBlocks.COMB_POWDERY.get().asItem();
		} else if (singleCombItem == Items.HONEYCOMB) {
			blockItem = Items.HONEYCOMB_BLOCK;
		}
		return blockItem != null ? Ingredient.of(blockItem) : null;
	}

	/**
	 * 由蜜脾配方派生蜜脾块配方
	 * <br/>
	 * 蜜脾块 = 4个蜜脾，输出 min/max 和流体按 multiplier 缩放。
	 * 参考原 createCombBlockRecipe 的逻辑，改为静态预生成。
	 * <p>
	 * 模块 3 修复：复刻 PB 热力离心机 stripWax 行为，派生蜜脾块配方时过滤 c:waxes 标签的产出。
	 * PB 原版 HeatedCentrifugeBlockEntity 处理蜜脾块时 stripWax=true，蜜脾块离心不产出 Wax。
	 * 蜜脾配方保留 Wax 不变（蜜脾离心产出 Wax 是正常的）。
	 * <p>
	 * 模块 5 修复（v2.4）：使用传入的 blockIngredient 作为蜜脾块配方的输入，而非 original.ingredient
	 * （单蜜脾）。修复前 JEI 显示蜜脾块配方时输入物品错误地显示为单蜜脾而非蜜脾块。
	 *
	 * @param honeycombRecipe  蜜脾离心配方
	 * @param beeType          蜜蜂类型ID（或特殊蜜脾的物品ID）
	 * @param multiplier       蜜脾块倍率（来自配置）
	 * @param blockIngredient  蜜脾块输入 ingredient（由调用方构造）
	 * @return 蜜脾块离心配方，派生失败返回 null
	 */
	@Nullable
	private static RecipeHolder<CentrifugeRecipe> deriveCombBlockRecipe(
			RecipeHolder<CentrifugeRecipe> honeycombRecipe,
			ResourceLocation beeType, int multiplier,
			Ingredient blockIngredient) {
		try {
			CentrifugeRecipe original = honeycombRecipe.value();
			List<ChancedOutput> blockOutputs = new ArrayList<>(original.itemOutput.size());
			for (ChancedOutput chanced : original.itemOutput) {
				// 模块 3：过滤 Wax 产出，复刻 PB 热力离心机 stripWax=true 行为
				if (isWaxOutput(chanced)) continue;
				blockOutputs.add(new ChancedOutput(chanced.ingredient(),
						chanced.min() * multiplier, chanced.max() * multiplier, chanced.chance()));
			}
			SizedFluidIngredient blockFluid = new SizedFluidIngredient(
					original.fluidOutput.ingredient(), original.fluidOutput.amount() * multiplier);
			CentrifugeRecipe blockRecipe = new CentrifugeRecipe(
					blockIngredient, blockOutputs, blockFluid, original.getProcessingTime());
			return new RecipeHolder<>(honeycombRecipe.id().withSuffix("_block"), blockRecipe);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("派生蜜脾块配方失败: beeType={}", beeType, e);
			return null;
		}
	}

	/**
	 * 判断 ChancedOutput 是否为 Wax 产出
	 * <br/>
	 * 通过 ingredient 的所有可能物品检查 c:waxes 标签。
	 * 性能：getItems() 在配方加载时已缓存，且仅在 rebuild 时调用一次（静态过滤），
	 * 运行时零开销。
	 *
	 * @param chanced 配方输出条目
	 * @return true 表示该产出是 Wax（应在蜜脾块配方中过滤）
	 */
	private static boolean isWaxOutput(ChancedOutput chanced) {
		ItemStack[] items = chanced.ingredient().getItems();
		for (ItemStack stack : items) {
			if (stack.is(WAXES_TAG)) return true;
		}
		return false;
	}

	/**
	 * O(1) 查找指定 bee_type 的蜜脾配方
	 *
	 * @param beeType 蜜蜂类型ID
	 * @return 蜜脾离心配方，未命中返回 null（调用方需回退到全量遍历）
	 */
	@Nullable
	public static RecipeHolder<CentrifugeRecipe> get(ResourceLocation beeType) {
		return snapshot.index.get(beeType);
	}

	/**
	 * O(1) 查找指定 bee_type 的蜜脾块配方
	 * <br/>
	 * 蜜脾块配方在 rebuild 时由蜜脾配方静态派生，运行时无需动态构建。
	 *
	 * @param beeType 蜜蜂类型ID
	 * @return 蜜脾块离心配方，未命中返回 null
	 */
	@Nullable
	public static RecipeHolder<CentrifugeRecipe> getCombBlock(ResourceLocation beeType) {
		return snapshot.combBlockIndex.get(beeType);
	}

	/**
	 * O(1) 查找指定输入物品的特殊蜜脾块配方 — 模块 1
	 * <br/>
	 * 特殊蜜脾块（ghostly/milky/powdery/vanilla）的蜜脾配方无 bee_type，
	 * 因此不能用 beeType 作索引键，改用单蜜脾物品 ResourceLocation 作键。
	 * 蜜脾块配方在 rebuild 时由对应单蜜脾配方静态派生（含 4 倍产出 + 过滤 Wax）。
	 * <p>
	 * 查找时先用 {@link SpecialCombBlockRecipeHandler#getSingleComb} 将蜜脾块拆分为单蜜脾，
	 * 再用单蜜脾物品 ID 查找（与 rebuild 时的索引键一致）。
	 *
	 * @param stack 输入物品（应为特殊蜜脾块）
	 * @return 派生的特殊蜜脾块离心配方，未命中返回 null（调用方需回退到全量遍历）
	 */
	@Nullable
	public static RecipeHolder<CentrifugeRecipe> getSpecialCombBlock(ItemStack stack) {
		if (stack.isEmpty()) return null;
		// 蜜脾块 → 单蜜脾（与 rebuild 时索引键统一为单蜜脾物品ID）
		ItemStack singleComb = SpecialCombBlockRecipeHandler.getSingleComb(stack);
		if (singleComb.isEmpty()) return null;
		ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(singleComb.getItem());
		return snapshot.specialCombBlockIndex.get(itemId);
	}

	/**
	 * 判断物品是否在 c:storage_blocks/honeycombs 标签中 — 模块 1
	 * <br/>
	 * 复刻 PB ModTags.Common.STORAGE_BLOCK_HONEYCOMBS，
	 * 用于在配方查找路径中识别蜜脾块输入（包含 vanilla 和 PB 所有蜜脾块）。
	 *
	 * @param stack 输入物品
	 * @return true 表示为蜜脾块（在 c:storage_blocks/honeycombs 标签中）
	 */
	public static boolean isStorageBlockHoneycomb(ItemStack stack) {
		return stack.is(STORAGE_BLOCKS_HONEYCOMBS);
	}

	/** 索引是否为空（未构建或配方列表为空） */
	public static boolean isEmpty() {
		return snapshot.index.isEmpty();
	}
}
