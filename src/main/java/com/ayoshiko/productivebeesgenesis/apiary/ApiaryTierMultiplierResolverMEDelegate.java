package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.Optional;
import java.util.function.IntSupplier;

import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import com.jerry.mekextras.common.tier.ExtraFactoryTier;

/**
 * ME 蜂箱等级堆叠倍率解析器（隔离类）
 * <br/>
 * 集中引用 ME 可选依赖（{@link ExtraFactoryTier}），仅在 {@code mekanism_extras} 模组加载后
 * 由 {@link ApiaryTierMultiplierResolver} 通过模组守卫调用，避免 ME 未加载时触发
 * {@code NoClassDefFoundError}。
 *
 * @since 1.9.0
 */
final class ApiaryTierMultiplierResolverMEDelegate {

	private ApiaryTierMultiplierResolverMEDelegate() {
	}

	/**
	 * 解析 ME 蜂箱工厂的堆叠倍率供应商
	 * <br/>
	 * 使用 {@link Optional} 区分"非 ME 类型"（empty）和"ME 类型但 tier 为 null"（含默认值 supplier）。
	 *
	 * @param tile 蜂箱方块实体（调用方已通过模组守卫确保 mekanism_extras 加载）
	 * @return Optional 包装的堆叠倍率供应商；非 ME 类型返回 empty
	 */
	static Optional<IntSupplier> getStackMultiplierForMe(TileEntityMekApiary tile) {
		if (!(tile instanceof TileEntityExtraMekApiaryFactory me)) {
			return Optional.empty();
		}
		ExtraFactoryTier t = me.getMETier();
		if (t != null) {
			return Optional.of(switch (t) {
				case ABSOLUTE -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackMeAbsolute.get());
				case SUPREME -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackMeSupreme.get());
				case COSMIC -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackMeCosmic.get());
				case INFINITE -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackMeInfinite.get());
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
