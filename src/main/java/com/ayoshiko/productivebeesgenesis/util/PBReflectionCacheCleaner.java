package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;

import java.lang.reflect.Field;
import java.util.Map;

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
	 * <p>
	 * 每个字段独立 try-catch：单个字段清理失败不影响另一个，避免半清理状态。
	 */
	public static void clearBeeFishingCaches() {
		clearField("cachedBiomes");
		clearField("cachedRecipes");
	}

	/**
	 * 清理 BeeFishingRecipe 的指定静态 Map 字段
	 * <br/>
	 * 独立 try-catch 隔离失败：字段名变更或字段类型变化时仅影响该字段，不污染其他字段的清理结果。
	 *
	 * @param fieldName 待清理的静态 Map 字段名
	 */
	@SuppressWarnings("unchecked")
	private static void clearField(String fieldName) {
		try {
			Field field = BeeFishingRecipe.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			Map<?, ?> cache = (Map<?, ?>) field.get(null);
			if (cache != null) {
				cache.clear();
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("清理 BeeFishingRecipe.{} 失败", fieldName, e);
		}
	}
}
