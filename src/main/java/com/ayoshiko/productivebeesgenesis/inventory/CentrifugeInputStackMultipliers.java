package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.config.FactoryTierConfigService;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierKey;

import java.util.function.IntSupplier;

/** 按工厂等级读取离心机输入槽堆叠倍率。 */
public final class CentrifugeInputStackMultipliers {

	private CentrifugeInputStackMultipliers() {
	}

	private static IntSupplier forTier(FactoryTierKey tier) {
		return () -> FactoryTierConfigService.current().centrifugeInputStack(tier);
	}

	public static IntSupplier forBasic() {
		return forTier(FactoryTierKey.BASIC);
	}

	public static IntSupplier forVanillaFactory(int ordinal) {
		return forTier(FactoryTierKey.vanillaFactory(ordinal));
	}

	public static IntSupplier forMEFactory(int ordinal) {
		return forTier(FactoryTierKey.mekanismExtrasFactory(ordinal));
	}

	public static IntSupplier forEMEFactory(int ordinal) {
		return forTier(FactoryTierKey.evolvedMekanismExtrasFactory(ordinal));
	}

	/** ordinal 0-4 对应 EM 的 OVERCLOCKED 到 CREATIVE。 */
	public static IntSupplier forEMFactory(int ordinal) {
		return forTier(FactoryTierKey.evolvedMekanismFactory(ordinal));
	}
}
