package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.world.item.ItemDisplayContext;

import java.lang.reflect.Method;

/**
	 * Iris 光影兼容工具类
	 * <br/>
	 * 通过反射检测 Iris 是否启用光影包；若启用则将第一/第三人称手持视角的 cosmic 光晕渲染延迟到世界渲染结束后执行。
	 * <p>
	 * 性能优化：
	 * <ul>
	 *   <li>反射获取的 Method 对象在 Holder 中缓存，避免每帧重复查找。</li>
	 *   <li>isShaderPackEnabled 结果带 1 秒 TTL 缓存，避免每帧通过反射 invoke IrisApi。
	 *       光影开关状态变化频率极低（用户手动切换），1 秒延迟可接受。</li>
	 * </ul>
	 */
public final class IrisCompat {

	/** 反射结果缓存 — null 表示未缓存，避免 Boolean 装箱歧义 */
	private static volatile Boolean cachedShaderPackEnabled = null;
	/** 缓存到期时间戳（毫秒） */
	private static volatile long cacheExpiry = 0L;
	/** 缓存 TTL — 1 秒，平衡响应性与反射开销 */
	private static final long CACHE_TTL_MS = 1000L;

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
			} catch (Throwable t) {
				// 此处需捕获 Throwable 而非 Exception：Iris API 不兼容变更会抛出
				// LinkageError/NoClassDefFoundError 等 Error 子类；静态初始化块若不捕获会导致类初始化失败
				// 一次性 ERROR 日志（静态初始化仅执行一次，无需节流）
				ProductiveBeesGenesis.LOGGER.warn(
						"Iris 光影兼容初始化失败, cosmic 光晕将不延迟渲染: {}", t.toString());
			}
			INITIALIZED = initialized;
			GET_INSTANCE = getInstance;
			IS_SHADER_PACK_IN_USE = isShaderPackInUse;
		}
	}

	/**
	 * 检测 Iris 是否启用了光影包
	 * <br/>
	 * 使用 1 秒 TTL 缓存避免每帧通过反射 invoke IrisApi。
	 * 缓存未命中或已过期时执行反射查询并刷新缓存。
	 * <p>
	 * 线程安全：cachedShaderPackEnabled 与 cacheExpiry 均为 volatile，
	 * 读取为原子操作；并发刷新时最坏情况是多执行一次反射查询，结果一致无危害。
	 *
	 * @return true 如果 Iris 已安装且当前启用了光影包
	 */
	public static boolean isShaderPackEnabled() {
		// 命中缓存直接返回（volatile 读保证可见性）
		Boolean cached = cachedShaderPackEnabled;
		if (cached != null && System.currentTimeMillis() < cacheExpiry) {
			return cached;
		}
		// 缓存未命中或已过期 — 执行反射查询
		boolean result = queryShaderPackEnabled();
		cachedShaderPackEnabled = result;
		cacheExpiry = System.currentTimeMillis() + CACHE_TTL_MS;
		return result;
	}

	/**
	 * 通过反射查询 IrisApi.isShaderPackInUse()
	 * <br/>
	 * 仅在缓存未命中时调用，避免每帧反射开销。
	 */
	private static boolean queryShaderPackEnabled() {
		if (!Holder.INITIALIZED) {
			return false;
		}
		try {
			Object api = Holder.GET_INSTANCE.invoke(null);
			return (Boolean) Holder.IS_SHADER_PACK_IN_USE.invoke(api);
		} catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
			// LinkageError 覆盖 Iris 版本不兼容场景；
			// ReflectiveOperationException 覆盖反射调用受检异常（InvocationTargetException 等）；
			// RuntimeException 覆盖 NPE/ClassCastException 等运行时异常。
			// 不捕获 Throwable 以避免吞没 OOM 等严重错误。
			LogThrottle.warn("iris_shader_pack_query",
					"Iris 光影包状态查询异常, 视为未启用: {}", e.toString());
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
