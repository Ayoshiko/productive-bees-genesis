package com.ayoshiko.productivebeesgenesis.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * 离心配方索引 — O(1) 蜜脾/蜜脾块配方查找
 * <br/>
 * 替代 findPbRecipe 和 createCombBlockRecipe 中的全量遍历 (O(N))，
 * 通过 bee_type -> 配方 的索引实现 O(1) 查找。
 * 同时维护蜜脾块配方索引：rebuild 时根据蜜脾配方静态生成对应的蜜脾块配方
 * （min/max/流体按 {@link ModConfig.ServerConfig#mekCentrifugeCombBlockMultiplier} 缩放），
 * 消除首次遇到新 bee_type 蜜脾块时的动态构建开销。
 * <p>
 * <b>线程安全</b>：使用 {@link ConcurrentHashMap} 存储索引，{@code volatile} 引用保证原子替换。
 * 重建期间旧索引仍可服务读请求，重建完成后整体替换为新索引。
 * <p>
 * <b>重建时机</b>：由 {@link ProductiveBeesGenesis#onTagsReload} 在 TagsUpdatedEvent 后调用，
 * 与 recipeVersion 递增同步，确保配方重载后索引立即更新。
 * <p>
 * <b>回退策略</b>：索引未命中时调用方回退到全量遍历（防御性），避免索引构建遗漏导致配方丢失。
 */
public final class CentrifugeRecipeIndex {

	/** 蜜脾配方索引 — 全服务端共享 */
	private static volatile Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> index = new ConcurrentHashMap<>();

	/** 蜜脾块配方索引 — rebuild 时由蜜脾配方静态生成，消除运行时动态构建开销 */
	private static volatile Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> combBlockIndex = Map.of();

	private CentrifugeRecipeIndex() {
		// 工具类禁止实例化
	}

	/**
	 * 重建索引 — 遍历所有 CentrifugeRecipe，提取 bee_type 建立蜜脾索引，
	 * 同时为每个 bee_type 静态生成蜜脾块配方。
	 * <br/>
	 * 跳过原生的蜜脾块配方（若有），蜜脾块配方统一由蜜脾配方派生。
	 * 使用局部 Map 构建完成后整体替换 volatile 引用，保证原子性。
	 * 单条配方解析失败不影响整体索引。
	 *
	 * @param level 服务端世界
	 */
	public static void rebuild(ServerLevel level) {
		try {
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> newIndex = new ConcurrentHashMap<>();
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> newCombBlockIndex = new ConcurrentHashMap<>();
			int multiplier = ModConfig.SERVER.mekCentrifugeCombBlockMultiplier.get();

			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get())) {
				try {
					ItemStack[] inputItems = holder.value().ingredient.getItems();
					if (inputItems.length == 0) continue;
					// 跳过原生蜜脾块配方（蜜脾块配方由蜜脾配方派生，不纳入蜜脾索引）
					if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) continue;
					ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());
					if (beeType == null) continue;

					// 同一 bee_type 多个配方时保留首个（putIfAbsent）
					if (newIndex.putIfAbsent(beeType, holder) == null) {
						// 仅在首次插入蜜脾配方时派生蜜脾块配方，避免重复生成
						RecipeHolder<CentrifugeRecipe> blockRecipe = deriveCombBlockRecipe(holder, beeType, multiplier);
						if (blockRecipe != null) {
							newCombBlockIndex.putIfAbsent(beeType, blockRecipe);
						}
					}
				} catch (Exception ignored) {
					// 单条配方解析失败不影响整体索引
				}
			}
			// 原子替换，确保读线程看到一致状态
			index = newIndex;
			combBlockIndex = newCombBlockIndex;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("重建离心配方索引失败", e);
		}
	}

	/**
	 * 由蜜脾配方派生蜜脾块配方
	 * <br/>
	 * 蜜脾块 = 4个蜜脾，输出 min/max 和流体按 multiplier 缩放。
	 * 参考原 createCombBlockRecipe 的逻辑，改为静态预生成。
	 *
	 * @param honeycombRecipe 蜜脾离心配方
	 * @param beeType         蜜蜂类型ID
	 * @param multiplier      蜜脾块倍率（来自配置）
	 * @return 蜜脾块离心配方，派生失败返回 null
	 */
	@Nullable
	private static RecipeHolder<CentrifugeRecipe> deriveCombBlockRecipe(
			RecipeHolder<CentrifugeRecipe> honeycombRecipe,
			ResourceLocation beeType, int multiplier) {
		try {
			CentrifugeRecipe original = honeycombRecipe.value();
			List<ChancedOutput> blockOutputs = new ArrayList<>(original.itemOutput.size());
			for (ChancedOutput chanced : original.itemOutput) {
				blockOutputs.add(new ChancedOutput(chanced.ingredient(),
						chanced.min() * multiplier, chanced.max() * multiplier, chanced.chance()));
			}
			SizedFluidIngredient blockFluid = new SizedFluidIngredient(
					original.fluidOutput.ingredient(), original.fluidOutput.amount() * multiplier);
			CentrifugeRecipe blockRecipe = new CentrifugeRecipe(
					original.ingredient, blockOutputs, blockFluid, original.getProcessingTime());
			return new RecipeHolder<>(honeycombRecipe.id().withSuffix("_block"), blockRecipe);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.debug("派生蜜脾块配方失败: beeType={}", beeType, e);
			return null;
		}
	}

	/**
	 * O(1) 查找指定 bee_type 的蜜脾配方
	 *
	 * @param beeType 蜜蜂类型ID
	 * @return 蜜脾离心配方，未命中返回 null（调用方需回退到全量遍历）
	 */
	@Nullable
	public static RecipeHolder<CentrifugeRecipe> get(ResourceLocation beeType) {
		return index.get(beeType);
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
		return combBlockIndex.get(beeType);
	}

	/** 索引是否为空（未构建或配方列表为空） */
	public static boolean isEmpty() {
		return index.isEmpty();
	}
}
