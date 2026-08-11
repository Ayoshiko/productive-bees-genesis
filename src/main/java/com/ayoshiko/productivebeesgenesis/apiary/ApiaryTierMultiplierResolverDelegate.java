package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import java.util.Optional;
import java.util.function.IntSupplier;

/**
	 * EME 蜂箱等级堆叠倍率解析器（隔离类）
	 * <br/>
	 * 集中引用 EME 可选依赖（{@link EMExtraFactoryTier}），仅在 {@code emextras} 模组加载后
	 * 由 {@link ApiaryTierMultiplierResolver} 通过模组守卫调用，避免 EME 未加载时触发
	 * {@code NoClassDefFoundError}。
	 * <p>
	 * 隔离原理：JVM 延迟类加载，本类的 import 只有在调用方通过守卫后才会被解析，
	 * 从而避免 EME 未安装时类加载失败。
	 *
	 * @since 2.0.0
	 */
final class ApiaryTierMultiplierResolverDelegate {

	private ApiaryTierMultiplierResolverDelegate() {
	}

	/**
	 * 解析 EME 蜂箱工厂的堆叠倍率供应商
	 * <br/>
	 * 使用 {@link Optional} 区分"非 EME 类型"（empty）和"EME 类型但 tier 为 null"（含默认值 supplier）。
	 *
	 * @param tile 蜂箱方块实体（调用方已通过模组守卫确保 emextras 加载）
	 * @return Optional 包装的堆叠倍率供应商；非 EME 类型返回 empty
	 */
	static Optional<IntSupplier> getStackMultiplierForEme(TileEntityMekApiary tile) {
		if (!(tile instanceof TileEntityEMExtraMekApiaryFactory eme)) {
			return Optional.empty();
		}
		EMExtraFactoryTier t = eme.getEMETier();
		if (t != null) {
			return Optional.of(switch (t) {
				case ABSOLUTE_OVERCLOCKED -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmeAbsoluteOverclocked.get());
				case SUPREME_QUANTUM -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmeSupremeQuantum.get());
				case COSMIC_DENSE -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmeCosmicDense.get());
				case INFINITE_MULTIVERSAL -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmeInfiniteMultiversal.get());
				default -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackBasic.get());
			});
		}
		return Optional.of(() -> configStackValue(() -> ModConfig.SERVER.apiaryStackBasic.get()));
	}

	private static int configStackValue(IntSupplier source) {
		if (ModConfig.SERVER == null) return 1;
		return source.getAsInt();
	}
}
