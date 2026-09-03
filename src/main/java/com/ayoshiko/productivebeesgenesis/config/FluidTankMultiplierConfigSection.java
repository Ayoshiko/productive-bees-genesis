package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 离心机流体罐容量倍率配置。 */
public final class FluidTankMultiplierConfigSection {

	/** 按工厂等级索引的流体罐倍率。 */
	public final FactoryTierConfigValues values;

	private FluidTankMultiplierConfigSection(ModConfigSpec.Builder builder) {
		builder.comment(
				"流体罐倍率（按离心机等级）",
				"基础容量 256K mB/进程；总容量 = 256K × 倍率 × 进程数",
				"超过 Integer.MAX_VALUE 时截断")
				.push("fluid_tank_multiplier");
		values = FactoryTierConfigValues.register(
				builder,
				"productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier",
				FactoryTierKey::centrifugeFluidTankDefault);
		builder.pop();
	}

	public static FluidTankMultiplierConfigSection create(ModConfigSpec.Builder builder) {
		return new FluidTankMultiplierConfigSection(builder);
	}
}
