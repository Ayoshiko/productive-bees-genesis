package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
	 * MEK离心机流体罐倍率配置段 — 从 {@link CentrifugeConfigSection} 抽取的独立配置段。
	 * <p>
	 * 子分类：fluid_tank_multiplier。EM 工厂配置项仅在 EM 加载时注册,未加载时对应字段为 null。
	 * <p>
	 * 容量公式：{@code fluidTankCapacity × processes × fluidTankMultiplier}
	 * （最终容量被 {@link Integer#MAX_VALUE} 截断）。
	 *
	 * @since 2.0.0
	 * @see CentrifugeConfigSection 父配置段
	 */
public final class FluidTankMultiplierConfigSection {

	// ========== 流体罐倍率(按离心机等级)==========
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankBasic;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankAdvanced;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankElite;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankUltimate;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankMeAbsolute;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankMeSupreme;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankMeCosmic;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankMeInfinite;
	// EM 工厂流体罐倍率 — EM 未加载时为 null(条件化注册)
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmOverclocked;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmQuantum;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmDense;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmMultiversal;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmCreative;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmeAbsoluteOverclocked;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmeSupremeQuantum;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmeCosmicDense;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankEmeInfiniteMultiversal;

	private FluidTankMultiplierConfigSection(ModConfigSpec.Builder builder) {
		// 基础容量: 256,000 mB (256 桶)/进程 | 单进程容量 = 256,000 × 倍率 | 工厂总容量 = 256,000 × processes × 倍率
		builder.comment("流体罐倍率（按离心机等级）",
				"基础容量 256K mB (256 桶)/进程 | 单进程容量 = 256K × 倍率 | 工厂总容量 = 256K × processes × 倍率",
				"超过 Integer.MAX_VALUE (2,147,483,647 mB ≈ 2.15G mB / 2,147,483 桶) 时截断").push("fluid_tank_multiplier");
		mekCentrifugeFluidTankBasic = builder
				.comment("基础离心机（3 进程）", "默认 1× → 单进程 256K mB (256 桶) | 工厂总 768K mB (768 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.basic")
				.defineInRange("basic", 1, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankAdvanced = builder
				.comment("高级离心机（5 进程）", "默认 2× → 单进程 512K mB (512 桶) | 工厂总 2.56M mB (2,560 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.advanced")
				.defineInRange("advanced", 2, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankElite = builder
				.comment("精英离心机（7 进程）", "默认 4× → 单进程 1.02M mB (1,024 桶) | 工厂总 7.17M mB (7,168 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.elite")
				.defineInRange("elite", 4, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankUltimate = builder
				.comment("终极离心机（9 进程）", "默认 8× → 单进程 2.05M mB (2,048 桶) | 工厂总 18.4M mB (18,432 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.ultimate")
				.defineInRange("ultimate", 8, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankMeAbsolute = builder
				.comment("通用机械:扩展 绝对离心机（11 进程）", "默认 16× → 单进程 4.10M mB (4,096 桶) | 工厂总 45.1M mB (45,056 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.meAbsolute")
				.defineInRange("meAbsolute", 16, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankMeSupreme = builder
				.comment("通用机械:扩展 至尊离心机（13 进程）", "默认 32× → 单进程 8.19M mB (8,192 桶) | 工厂总 106.5M mB (106,496 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.meSupreme")
				.defineInRange("meSupreme", 32, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankMeCosmic = builder
				.comment("通用机械:扩展 寰宇离心机（15 进程）", "默认 64× → 单进程 16.4M mB (16,384 桶) | 工厂总 245.8M mB (245,760 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.meCosmic")
				.defineInRange("meCosmic", 64, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankMeInfinite = builder
				.comment("通用机械:扩展 无限离心机（17 进程）", "默认 128× → 单进程 32.8M mB (32,768 桶) | 工厂总 557.1M mB (557,056 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.meInfinite")
				.defineInRange("meInfinite", 128, 1, Integer.MAX_VALUE);
		if (MekCompatHooks.isEvolvedMekanismLoaded()) {
			mekCentrifugeFluidTankEmOverclocked = builder
					.comment("进化通用机械 超频离心机（11 进程）", "默认 256× → 单进程 65.5M mB (65,536 桶) | 工厂总 720.9M mB (720,896 桶)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emOverclocked")
					.defineInRange("emOverclocked", 256, 1, Integer.MAX_VALUE);
			mekCentrifugeFluidTankEmQuantum = builder
					.comment("进化通用机械 量子离心机（13 进程）", "默认 512× → 单进程 131.1M mB (131,072 桶) | 工厂总 1.70G mB (1,703,936 桶)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emQuantum")
					.defineInRange("emQuantum", 512, 1, Integer.MAX_VALUE);
			mekCentrifugeFluidTankEmDense = builder
					.comment("进化通用机械 致密离心机（15 进程）", "默认 1024× → 单进程 262.1M mB (262,144 桶) | 工厂总 3.93G mB → 截断 2.15G mB (2,147,483 桶)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emDense")
					.defineInRange("emDense", 1024, 1, Integer.MAX_VALUE);
			mekCentrifugeFluidTankEmMultiversal = builder
					.comment("进化通用机械 多元宇宙离心机（17 进程）",
							"默认 2048× → 单进程 524.3M mB (524,288 桶) | 工厂总 8.91G mB → 截断 2.15G mB (2,147,483 桶)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emMultiversal")
					.defineInRange("emMultiversal", 2048, 1, Integer.MAX_VALUE);
			mekCentrifugeFluidTankEmCreative = builder
					.comment("进化通用机械 创造离心机（19 进程）",
							"默认 4096× → 单进程 1.05G mB (1,048,576 桶) | 工厂总 19.92G mB → 截断 2.15G mB (2,147,483 桶)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emCreative")
					.defineInRange("emCreative", 4096, 1, Integer.MAX_VALUE);
		} else {
			mekCentrifugeFluidTankEmOverclocked = null;
			mekCentrifugeFluidTankEmQuantum = null;
			mekCentrifugeFluidTankEmDense = null;
			mekCentrifugeFluidTankEmMultiversal = null;
			mekCentrifugeFluidTankEmCreative = null;
		}
		mekCentrifugeFluidTankEmeAbsoluteOverclocked = builder
				.comment("进化通用机械:扩展 绝对超频离心机（12 进程）",
						"默认 4096× → 单进程 1.05G mB (1,048,576 桶) | 工厂总 12.58G mB → 截断 2.15G mB (2,147,483 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emeAbsoluteOverclocked")
				.defineInRange("emeAbsoluteOverclocked", 4096, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankEmeSupremeQuantum = builder
				.comment("进化通用机械:扩展 至尊量子离心机（14 进程）",
						"默认 8192× → 单进程 2.10G mB (2,097,152 桶) | 工厂总 29.36G mB → 截断 2.15G mB (2,147,483 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emeSupremeQuantum")
				.defineInRange("emeSupremeQuantum", 8192, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankEmeCosmicDense = builder
				.comment("进化通用机械:扩展 寰宇致密离心机（16 进程）",
						"默认 16384× → 单进程 4.19G mB (4,194,304 桶) | 工厂总 67.11G mB → 截断 2.15G mB (2,147,483 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emeCosmicDense")
				.defineInRange("emeCosmicDense", 16384, 1, Integer.MAX_VALUE);
		mekCentrifugeFluidTankEmeInfiniteMultiversal = builder
				.comment("进化通用机械:扩展 无限多元离心机（18 进程）",
						"默认 32768× → 单进程 8.39G mB (8,388,608 桶) | 工厂总 150.99G mB → 截断 2.15G mB (2,147,483 桶)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.fluid_tank_multiplier.emeInfiniteMultiversal")
				.defineInRange("emeInfiniteMultiversal", 32768, 1, Integer.MAX_VALUE);
		builder.pop(); // fluid_tank_multiplier
	}

	/**
	 * 工厂方法:注册流体罐倍率配置项并返回实例。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册流体罐倍率配置项的实例
	 */
	public static FluidTankMultiplierConfigSection create(ModConfigSpec.Builder builder) {
		return new FluidTankMultiplierConfigSection(builder);
	}
}
