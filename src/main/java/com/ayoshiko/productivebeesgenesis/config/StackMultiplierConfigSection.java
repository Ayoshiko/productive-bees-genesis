package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 离心机输入槽和输出槽的等级倍率配置。 */
public final class StackMultiplierConfigSection {

	/** 输出槽倍率，默认是输入槽倍率的四倍。 */
	public final FactoryTierConfigValues outputStack;
	/** 输入槽倍率。 */
	public final FactoryTierConfigValues inputStack;

	private StackMultiplierConfigSection(ModConfigSpec.Builder builder) {
		builder.comment(
				"输出槽堆叠倍率（按离心机等级）",
				"基础堆叠 64/槽；单槽容量 = 64 × 倍率；工厂总容量按进程数和输出槽数量计算",
				"输出槽倍率默认为输入槽的 4 倍（蜜脾处理成倍产出）")
				.push("stack_multiplier");
		outputStack = FactoryTierConfigValues.register(
				builder,
				"productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier",
				FactoryTierKey::centrifugeOutputStackDefault);
		builder.pop();

		builder.comment(
				"输入槽堆叠倍率（按离心机等级）",
				"基础堆叠 64/槽；单槽容量 = 64 × 倍率；工厂总容量按进程数计算",
				"蜜脾处理成倍产出，输入槽倍率默认为输出槽的 1/4")
				.push("input_stack_multiplier");
		inputStack = FactoryTierConfigValues.register(
				builder,
				"productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier",
				FactoryTierKey::centrifugeInputStackDefault);
		builder.pop();
	}

	public static StackMultiplierConfigSection create(ModConfigSpec.Builder builder) {
		return new StackMultiplierConfigSection(builder);
	}
}
