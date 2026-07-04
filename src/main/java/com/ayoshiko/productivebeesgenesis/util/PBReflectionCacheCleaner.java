package com.ayoshiko.productivebeesgenesis.util;

import java.lang.reflect.Field;
import java.util.Map;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;

/**
 * ProductiveBees 内部静态缓存反射清理器
 * <br/>
 * PB 在 {@link BeeFishingRecipe} 中维护了两个静态缓存（cachedBiomes、cachedRecipes），
 * 替换配方后旧缓存会引用过期的 Recipe 实例，必须清理避免数据不一致。
 * <p>
 * 抽离为独立类便于后续扩展清理其他 PB 静态缓存。
 */
public final class PBReflectionCacheCleaner {

	private PBReflectionCacheCleaner() {}

	/**
	 * 清理 BeeFishingRecipe 的静态缓存
	 * <br/>
	 * 清理 cachedBiomes 和 cachedRecipes 两个静态 Map，防止替换配方后引用过期 Recipe 实例。
	 * 反射失败时仅记录警告，不抛出异常（PB 版本变更可能导致字段名变化）。
	 */
	@SuppressWarnings("unchecked")
	public static void clearBeeFishingCaches() {
		try {
			Field cachedBiomesField = BeeFishingRecipe.class.getDeclaredField("cachedBiomes");
			cachedBiomesField.setAccessible(true);
			Map<?, ?> cachedBiomes = (Map<?, ?>) cachedBiomesField.get(null);
			if (cachedBiomes != null) {
				cachedBiomes.clear();
			}

			Field cachedRecipesField = BeeFishingRecipe.class.getDeclaredField("cachedRecipes");
			cachedRecipesField.setAccessible(true);
			Map<?, ?> cachedRecipes = (Map<?, ?>) cachedRecipesField.get(null);
			if (cachedRecipes != null) {
				cachedRecipes.clear();
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("清理 BeeFishingRecipe 静态缓存失败", e);
		}
	}
}
