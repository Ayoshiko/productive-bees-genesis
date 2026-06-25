package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.lang.reflect.Method;

import net.minecraft.world.item.ItemDisplayContext;

/**
 * Iris 光影兼容工具类
 * <br/>
 * 通过反射检测 Iris 是否启用光影包；若启用则将第一/第三人称手持视角的 cosmic 光晕渲染延迟到世界渲染结束后执行。
 * <p>
 * 性能优化：反射获取的 Method 对象在 Holder 中缓存，避免每帧重复查找。
 */
public final class IrisCompat {

	private IrisCompat() {
	}

	/**
	 * Holder 模式 — 线程安全的懒加载反射 Method 缓存
	 * <br/>
	 * JVM 类初始化阶段保证原子性，Method 对象在首次调用时获取后常驻。
	 * INITIALIZATION_FAILED 标记反射初始化失败，后续调用直接跳过。
	 */
	private static final class Holder {
		static final boolean INITIALIZED;
		static final Method GET_INSTANCE;
		static final Method IS_SHADER_PACK_IN_USE;

		static {
			Method getInstance = null;
			Method isShaderPackInUse = null;
			boolean initialized = false;
			try {
				Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				getInstance = irisApi.getMethod("getInstance");
				isShaderPackInUse = irisApi.getMethod("isShaderPackInUse");
				initialized = true;
			} catch (Throwable ignored) {
				// Iris 未安装或API变更，初始化失败
			}
			INITIALIZED = initialized;
			GET_INSTANCE = getInstance;
			IS_SHADER_PACK_IN_USE = isShaderPackInUse;
		}
	}

	public static boolean isShaderPackEnabled() {
		if (!Holder.INITIALIZED) {
			return false;
		}
		try {
			Object api = Holder.GET_INSTANCE.invoke(null);
			return (Boolean) Holder.IS_SHADER_PACK_IN_USE.invoke(api);
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static boolean shouldDefer(ItemDisplayContext ctx) {
		if (!isShaderPackEnabled()) {
			return false;
		}
		return switch (ctx) {
			case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> true;
			default -> false;
		};
	}
}
