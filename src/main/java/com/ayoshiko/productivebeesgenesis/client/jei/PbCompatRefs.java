package com.ayoshiko.productivebeesgenesis.client.jei;

import java.lang.reflect.Field;

import com.ayoshiko.productivebeesgenesis.util.DevLog;

/**
 * ProductiveBees (PB) compat 包引用封装类
 * <br/>
 * PB 的 {@code cy.jdkdigital.productivebees.compat.jei.ProductiveBeesJeiPlugin} 类位于 compat 包，
 * 非稳定 API。直接引用会在 PB 重构 compat 包时触发 {@link NoClassDefFoundError}。
 * 本类通过反射获取相关字段和类，缓存结果，异常时降级处理（返回 null）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责 PB compat 包的反射访问，不涉及业务逻辑</li>
 *   <li>DIP — 调用方通过本类间接访问 PB compat 类，降低耦合</li>
 *   <li>性能 — 反射结果缓存，避免重复反射</li>
 * </ul>
 * <p>
 * 线程安全：使用 volatile + 双重检查锁定保证反射结果缓存的安全。
 */
public final class PbCompatRefs {

	/** PB ProductiveBeesJeiPlugin 类的全限定名 */
	private static final String PB_JEI_PLUGIN_CLASS =
			"cy.jdkdigital.productivebees.compat.jei.ProductiveBeesJeiPlugin";

	/** 缓存的 ProductiveBeesJeiPlugin Class 对象，null 表示未初始化或加载失败 */
	private static volatile Class<?> pbJeiPluginClass;
	/** 标记 pbJeiPluginClass 反射是否已执行（避免重复反射，区分"未加载"与"未初始化"） */
	private static volatile boolean pbJeiPluginClassResolved;

	/** 缓存的 ADVANCED_BEEHIVE_TYPE 字段值 */
	private static volatile Object advancedBeehiveType;
	/** 标记 ADVANCED_BEEHIVE_TYPE 反射是否已执行 */
	private static volatile boolean advancedBeehiveTypeResolved;

	/** 缓存的 BEE_INGREDIENT 字段值 */
	private static volatile Object beeIngredientType;
	/** 标记 BEE_INGREDIENT 反射是否已执行 */
	private static volatile boolean beeIngredientTypeResolved;

	private PbCompatRefs() {}

	/**
	 * 获取 PB 的 ProductiveBeesJeiPlugin 类
	 * <br/>
	 * 通过反射加载类，避免直接引用 PB compat 包。
	 * 类加载失败（PB 未安装或重构）时返回 null。
	 *
	 * @return ProductiveBeesJeiPlugin Class 对象，加载失败返回 null
	 */
	public static Class<?> getPbJeiPluginClass() {
		if (!pbJeiPluginClassResolved) {
			synchronized (PbCompatRefs.class) {
				if (!pbJeiPluginClassResolved) {
					try {
						pbJeiPluginClass = Class.forName(PB_JEI_PLUGIN_CLASS);
					} catch (ClassNotFoundException e) {
						DevLog.warn("jei", "无法加载 PB JEI 插件类: {}", e.getMessage());
						pbJeiPluginClass = null;
					}
					pbJeiPluginClassResolved = true;
				}
			}
		}
		return pbJeiPluginClass;
	}

	/**
	 * 获取 PB 的 ADVANCED_BEEHIVE_TYPE 字段值
	 * <br/>
	 * 通过反射获取 {@code ProductiveBeesJeiPlugin.ADVANCED_BEEHIVE_TYPE} 静态字段。
	 * 反射失败时返回 null。调用方应使用 {@code instanceof RecipeType<?>} 进行类型检查。
	 *
	 * @return ADVANCED_BEEHIVE_TYPE 字段值，获取失败返回 null
	 */
	public static Object getAdvancedBeehiveType() {
		if (!advancedBeehiveTypeResolved) {
			synchronized (PbCompatRefs.class) {
				if (!advancedBeehiveTypeResolved) {
					advancedBeehiveType = resolveStaticField("ADVANCED_BEEHIVE_TYPE");
					advancedBeehiveTypeResolved = true;
				}
			}
		}
		return advancedBeehiveType;
	}

	/**
	 * 获取 PB 的 BEE_INGREDIENT 字段值
	 * <br/>
	 * 通过反射获取 {@code ProductiveBeesJeiPlugin.BEE_INGREDIENT} 静态字段。
	 * 反射失败时返回 null。调用方应使用 {@code instanceof IIngredientType<?>} 进行类型检查。
	 *
	 * @return BEE_INGREDIENT 字段值，获取失败返回 null
	 */
	public static Object getBeeIngredientType() {
		if (!beeIngredientTypeResolved) {
			synchronized (PbCompatRefs.class) {
				if (!beeIngredientTypeResolved) {
					beeIngredientType = resolveStaticField("BEE_INGREDIENT");
					beeIngredientTypeResolved = true;
				}
			}
		}
		return beeIngredientType;
	}

	/**
	 * 反射获取 ProductiveBeesJeiPlugin 的静态字段
	 *
	 * @param fieldName 字段名
	 * @return 字段值，获取失败返回 null
	 */
	private static Object resolveStaticField(String fieldName) {
		Class<?> pluginClass = getPbJeiPluginClass();
		if (pluginClass == null) {
			return null;
		}
		try {
			Field field = pluginClass.getField(fieldName);
			return field.get(null);
		} catch (Exception e) {
			DevLog.warn("jei", "无法获取 PB JEI 插件字段 {}: {}", fieldName, e.getMessage());
			return null;
		}
	}
}
