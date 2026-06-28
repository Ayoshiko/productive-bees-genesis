package com.ayoshiko.productivebeesgenesis.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * 离心配方索引 — O(1) 蜜脾配方查找
 * <br/>
 * 替代 findPbRecipe 和 createCombBlockRecipe 中的全量遍历 (O(N))，
 * 通过 bee_type -> 蜜脾配方 的索引实现 O(1) 查找。
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

	/** 单例索引 — 全服务端共享，所有方块实体共用同一份 */
	private static volatile Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> index = new ConcurrentHashMap<>();

	private CentrifugeRecipeIndex() {
		// 工具类禁止实例化
	}

	/**
	 * 重建索引 — 遍历所有 CentrifugeRecipe，提取 bee_type 建立索引
	 * <br/>
	 * 仅索引蜜脾配方（跳过蜜脾块配方，蜜脾块配方由 createCombBlockRecipe 动态生成）。
	 * 使用局部 Map 构建完成后整体替换 volatile 引用，保证原子性。
	 * 单条配方解析失败不影响整体索引。
	 *
	 * @param level 服务端世界
	 */
	public static void rebuild(ServerLevel level) {
		try {
			Map<ResourceLocation, RecipeHolder<CentrifugeRecipe>> newIndex = new ConcurrentHashMap<>();
			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get())) {
				try {
					ItemStack[] inputItems = holder.value().ingredient.getItems();
					if (inputItems.length == 0) continue;
					// 跳过蜜脾块配方（蜜脾块配方由 createCombBlockRecipe 动态生成，不纳入索引）
					if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) continue;
					ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());
					if (beeType == null) continue;
					// 同一 bee_type 多个配方时保留首个（putIfAbsent）
					newIndex.putIfAbsent(beeType, holder);
				} catch (Exception ignored) {
					// 单条配方解析失败不影响整体索引
				}
			}
			// 原子替换，确保读线程看到一致状态
			index = newIndex;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("重建离心配方索引失败", e);
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

	/** 索引是否为空（未构建或配方列表为空） */
	public static boolean isEmpty() {
		return index.isEmpty();
	}
}
