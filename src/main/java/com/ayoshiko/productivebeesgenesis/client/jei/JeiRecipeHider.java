package com.ayoshiko.productivebeesgenesis.client.jei;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.util.DevLog;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

/**
 * JEI 配方隐藏工具类
 * <p>
 * 将 {@link ProductiveBeesGenesisJEI} 中通过反射隐藏蜜蜂相关配方的逻辑抽取为独立工具类，
 * 消除 4 个高度重复的 try-catch 块，遵循下述设计原则：
 * <ul>
 *   <li>SRP — 仅负责配方隐藏的反射逻辑，不涉及 JEI 插件生命周期</li>
 *   <li>DIP — 通过参数传入 {@link IRecipeManager}、插件类与字段名，不依赖具体插件实现</li>
 *   <li>性能 — 使用 {@link #METHODS_CACHE} 缓存反射方法数组，避免每个配方重复调用 {@link Class#getMethods()}</li>
 * </ul>
 * <br/>
 * 线程安全：{@link #METHODS_CACHE} 使用 {@link ConcurrentHashMap}，JEI 初始化与配方重载并发安全。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class JeiRecipeHider {

	/**
	 * 反射方法数组缓存
	 * <p>
	 * {@link Class#getMethods()} 涉及大量反射拷贝，每个配方重复调用开销较大。
	 * 缓存按配方 Class 复用 Method[]，避免重复反射。
	 * 使用 {@link ConcurrentHashMap} 保证 JEI 初始化与配方重载并发安全。
	 */
	private static final Map<Class<?>, Method[]> METHODS_CACHE = new ConcurrentHashMap<>();

	/** 工具类禁止实例化 */
	private JeiRecipeHider() {
	}

	/**
	 * 通过反射从 ProductiveBeesJeiPlugin 获取指定 RecipeType 并隐藏匹配配方
	 * <p>
	 * 反射获取 {@code pluginClass} 的静态字段 {@code fieldName}，若结果为 {@link RecipeType}
	 * 则调用 {@link #hideRecipesByBeeType} 隐藏输出为目标蜜蜂的所有配方。
	 * 反射异常仅记录日志，不向上抛出，避免单个字段失败影响其他字段处理。
	 *
	 * @param recipeManager JEI 配方管理器
	 * @param pluginClass   ProductiveBeesJeiPlugin 类
	 * @param fieldName     RecipeType 静态字段名
	 * @param recipeLabel   配方类型中文名（用于日志，如 "钓鱼"、"繁殖"）
	 * @param beeTypeString 要隐藏的蜜蜂类型字符串
	 */
	public static void hideRecipesByReflection(
			IRecipeManager recipeManager,
			Class<?> pluginClass,
			String fieldName,
			String recipeLabel,
			String beeTypeString) {
		try {
			var recipeType = pluginClass.getField(fieldName).get(null);
			if (recipeType instanceof RecipeType<?> type) {
				hideRecipesByBeeType(recipeManager, type, beeTypeString);
			}
		} catch (Exception e) {
			DevLog.warn("jei", "无法隐藏万象创世{}配方: {}", recipeLabel, e.getMessage());
		}
	}

	/**
	 * 隐藏指定 RecipeType 中输出为目标蜜蜂的所有配方
	 * <p>
	 * 遍历该类型的所有配方，通过 {@link #isRecipeForBeeType} 过滤出涉及目标蜜蜂的配方并隐藏。
	 * 异常仅记录日志，不向上抛出。
	 *
	 * @param recipeManager JEI 配方管理器
	 * @param recipeType    JEI 配方类型
	 * @param beeTypeString 目标蜜蜂类型字符串
	 */
	@SuppressWarnings("unchecked")
	public static <T> void hideRecipesByBeeType(
			IRecipeManager recipeManager,
			RecipeType<T> recipeType,
			String beeTypeString) {
		try {
			var recipeLookup = recipeManager.createRecipeLookup(recipeType);
			if (recipeLookup == null) {
				return;
			}

			List<T> recipesToHide = recipeLookup.get()
					.filter(recipe -> isRecipeForBeeType(recipe, beeTypeString))
					.map(recipe -> (T) recipe)
					.toList();

			if (!recipesToHide.isEmpty()) {
				recipeManager.hideRecipes(recipeType, recipesToHide);
			}
		} catch (Exception e) {
			DevLog.warn("jei", "隐藏配方时出错: {}", e.getMessage());
		}
	}

	/**
	 * 反射检查配方是否涉及目标蜜蜂类型
	 * <p>
	 * 遍历配方的所有 public 方法，查找返回 {@link BeeIngredient} 的 getter
	 * （getResultBee/getOutputBee/getBee/result/output），若其返回值与目标蜜蜂类型匹配则返回 true。
	 * 同时兼容 {@code Supplier<BeeIngredient>} 返回类型。
	 * <p>
	 * 性能优化：使用 {@link #METHODS_CACHE} 缓存 {@link Class#getMethods()} 结果，
	 * 避免每个配方重复反射拷贝方法数组。
	 *
	 * @param recipe        配方对象
	 * @param beeTypeString 目标蜜蜂类型字符串
	 * @return true 表示配方涉及目标蜜蜂
	 */
	public static boolean isRecipeForBeeType(Object recipe, String beeTypeString) {
		if (recipe == null) {
			return false;
		}

		try {
			Method[] methods = METHODS_CACHE.computeIfAbsent(recipe.getClass(), Class::getMethods);
			for (Method method : methods) {
				String methodName = method.getName();
				if (methodName.equals("getResultBee") || methodName.equals("getOutputBee")
						|| methodName.equals("getBee") || methodName.equals("result")
						|| methodName.equals("output")) {
					Object result = method.invoke(recipe);
					if (result instanceof BeeIngredient beeIngredient) {
						if (beeTypeString.equals(beeIngredient.getBeeType().toString())) {
							return true;
						}
					}
					// 检查 Supplier<BeeIngredient>
					if (result instanceof java.util.function.Supplier<?> supplier) {
						Object supplied = supplier.get();
						if (supplied instanceof BeeIngredient beeIngredient) {
							if (beeTypeString.equals(beeIngredient.getBeeType().toString())) {
								return true;
							}
						}
					}
				}
			}
		} catch (Exception e) {
			// DevLog 节流日志便于排查（JEI 反射路径，避免刷屏）
			DevLog.warn("jei", "isRecipeForBeeType 反射检查异常: {}", e.toString());
		}

		return false;
	}
}
