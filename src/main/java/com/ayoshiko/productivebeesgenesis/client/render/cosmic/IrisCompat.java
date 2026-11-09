package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.world.item.ItemDisplayContext;

import java.lang.reflect.Method;

/** Iris 的可选 API 边界；阴影状态会在同一帧内变化，不能使用跨 pass 的 TTL 缓存。 */
public final class IrisCompat {
	public enum Pass {
		VANILLA,
		MAIN,
		SHADOW,
		UNAVAILABLE
	}

	private static boolean queryFailed;

	private IrisCompat() {
	}

	private static final class Holder {
		static final Object API;
		static final Method IS_SHADER_PACK_IN_USE;
		static final Method IS_RENDERING_SHADOW_PASS;
		static final Pass FALLBACK;

		static {
			Object api = null;
			Method shaderPack = null;
			Method shadowPass = null;
			Pass fallback = Pass.UNAVAILABLE;
			try {
				Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				api = irisApi.getMethod("getInstance").invoke(null);
				shaderPack = irisApi.getMethod("isShaderPackInUse");
				shadowPass = irisApi.getMethod("isRenderingShadowPass");
			} catch (ClassNotFoundException exception) {
				// 未安装 Iris 时不加载任何 Iris 类型，继续原生绘制。
				fallback = Pass.VANILLA;
			} catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
				ProductiveBeesGenesis.LOGGER.warn("Iris 渲染阶段 API 不可用，停用附加光效", exception);
			}
			API = api;
			IS_SHADER_PACK_IN_USE = shaderPack;
			IS_RENDERING_SHADOW_PASS = shadowPass;
			FALLBACK = fallback;
		}
	}

	public static Pass currentPass() {
		if (Holder.API == null || Holder.IS_SHADER_PACK_IN_USE == null || Holder.IS_RENDERING_SHADOW_PASS == null) {
			return Holder.FALLBACK;
		}
		if (queryFailed) {
			return Pass.UNAVAILABLE;
		}
		try {
			if ((Boolean) Holder.IS_RENDERING_SHADOW_PASS.invoke(Holder.API)) {
				return Pass.SHADOW;
			}
			return (Boolean) Holder.IS_SHADER_PACK_IN_USE.invoke(Holder.API) ? Pass.MAIN : Pass.VANILLA;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
			queryFailed = true;
			ProductiveBeesGenesis.LOGGER.warn("Iris 渲染阶段查询失败，本次资源代际停用附加光效", exception);
			return Pass.UNAVAILABLE;
		}
	}

	public static void reset() {
		queryFailed = false;
	}

	public static boolean isShaderPackEnabled() {
		Pass pass = currentPass();
		return pass == Pass.MAIN || pass == Pass.SHADOW;
	}

	public static boolean shouldDefer(ItemDisplayContext context) {
		return shouldDefer(context, currentPass());
	}

	static boolean shouldDefer(ItemDisplayContext context, Pass pass) {
		if (pass != Pass.MAIN || CosmicShaders.cosmicInventoryRender) {
			return false;
		}
		return switch (context) {
			case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> true;
			default -> false;
		};
	}
}
