package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

/**
 * MEK离心机堆叠倍率配置段 — 从 {@link CentrifugeConfigSection} 抽取的独立配置段。
 * <p>
 * 子分类:stack_multiplier(输出槽) + input_stack_multiplier(输入槽)。
 * EM 工厂配置项仅在 EM 加载时注册,未加载时对应字段为 null。
 * <p>
 * 容量公式:
 * <ul>
 *   <li>输出槽:单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × processes × 3(每进程 3 输出槽)</li>
 *   <li>输入槽:单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × processes</li>
 * </ul>
 * 输出槽倍率默认为输入槽的 4 倍(蜜脾处理成倍产出)。
 *
 * @since 1.13.0
 * @see CentrifugeConfigSection 父配置段
 */
public final class StackMultiplierConfigSection {

	// ========== 输出槽堆叠倍率(按离心机等级)==========
	public final ModConfigSpec.IntValue mekCentrifugeStackBasic;
	public final ModConfigSpec.IntValue mekCentrifugeStackAdvanced;
	public final ModConfigSpec.IntValue mekCentrifugeStackElite;
	public final ModConfigSpec.IntValue mekCentrifugeStackUltimate;
	public final ModConfigSpec.IntValue mekCentrifugeStackMeAbsolute;
	public final ModConfigSpec.IntValue mekCentrifugeStackMeSupreme;
	public final ModConfigSpec.IntValue mekCentrifugeStackMeCosmic;
	public final ModConfigSpec.IntValue mekCentrifugeStackMeInfinite;
	// EM 工厂输出槽堆叠倍率 — EM 未加载时为 null(条件化注册)
	public final ModConfigSpec.IntValue mekCentrifugeStackEmOverclocked;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmQuantum;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmDense;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmMultiversal;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmCreative;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmeAbsoluteOverclocked;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmeSupremeQuantum;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmeCosmicDense;
	public final ModConfigSpec.IntValue mekCentrifugeStackEmeInfiniteMultiversal;

	// ========== 输入槽堆叠倍率(按离心机等级,默认为输出槽的 1/4)==========
	public final ModConfigSpec.IntValue mekCentrifugeInputStackBasic;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackAdvanced;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackElite;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackUltimate;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackMeAbsolute;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackMeSupreme;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackMeCosmic;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackMeInfinite;
	// EM 工厂输入槽堆叠倍率 — EM 未加载时为 null(条件化注册)
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmOverclocked;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmQuantum;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmDense;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmMultiversal;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmCreative;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmeAbsoluteOverclocked;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmeSupremeQuantum;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmeCosmicDense;
	public final ModConfigSpec.IntValue mekCentrifugeInputStackEmeInfiniteMultiversal;

	private StackMultiplierConfigSection(ModConfigSpec.Builder builder) {
		// ===== 输出槽堆叠倍率(stack_multiplier section)=====
		// 基础堆叠 64/槽 | 单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × processes × 3(每进程 3 输出槽)
		// 输出槽倍率默认为输入槽的 4 倍(蜜脾处理成倍产出)
		builder.comment("输出槽堆叠倍率（按离心机等级）",
				"基础堆叠 64/槽 | 单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × processes × 3（每进程 3 输出槽）",
				"输出槽倍率默认为输入槽的 4 倍（蜜脾处理成倍产出）").push("stack_multiplier");
		mekCentrifugeStackBasic = builder
				.comment("基础离心机（3 进程，9 输出槽）", "默认 65536× → 单槽 4,194,304 (4.2M) | 工厂总 37,748,736 (37.7M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.basic")
				.defineInRange("basic", 65536, 1, 10_000_000);
		mekCentrifugeStackAdvanced = builder
				.comment("高级离心机（5 进程，15 输出槽）", "默认 327680× → 单槽 20,971,520 (21.0M) | 工厂总 314,572,800 (314.6M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.advanced")
				.defineInRange("advanced", 327680, 1, 10_000_000);
		mekCentrifugeStackElite = builder
				.comment("精英离心机（7 进程，21 输出槽）", "默认 458752× → 单槽 29,360,128 (29.4M) | 工厂总 616,562,688 (616.6M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.elite")
				.defineInRange("elite", 458752, 1, 10_000_000);
		mekCentrifugeStackUltimate = builder
				.comment("终极离心机（9 进程，27 输出槽）", "默认 589824× → 单槽 37,748,736 (37.7M) | 工厂总 1,019,215,872 (1.0G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.ultimate")
				.defineInRange("ultimate", 589824, 1, 10_000_000);
		mekCentrifugeStackMeAbsolute = builder
				.comment("通用机械:扩展 绝对离心机（11 进程，33 输出槽）", "默认 720896× → 单槽 46,137,344 (46.1M) | 工厂总 1,522,532,352 (1.5G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.meAbsolute")
				.defineInRange("meAbsolute", 720896, 1, 10_000_000);
		mekCentrifugeStackMeSupreme = builder
				.comment("通用机械:扩展 至尊离心机（13 进程，39 输出槽）", "默认 851968× → 单槽 54,525,952 (54.5M) | 工厂总 2,126,562,048 (2.1G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.meSupreme")
				.defineInRange("meSupreme", 851968, 1, 10_000_000);
		mekCentrifugeStackMeCosmic = builder
				.comment("通用机械:扩展 寰宇离心机（15 进程，45 输出槽）", "默认 983040× → 单槽 62,914,560 (62.9M) | 工厂总 2,831,155,200 (2.8G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.meCosmic")
				.defineInRange("meCosmic", 983040, 1, 10_000_000);
		mekCentrifugeStackMeInfinite = builder
				.comment("通用机械:扩展 无限离心机（17 进程，51 输出槽）", "默认 1114112× → 单槽 71,303,168 (71.3M) | 工厂总 3,636,461,568 (3.6G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.meInfinite")
				.defineInRange("meInfinite", 1114112, 1, 10_000_000);
		if (MekCompatHooks.isEvolvedMekanismLoaded()) {
			mekCentrifugeStackEmOverclocked = builder
					.comment("进化通用机械 超频离心机（11 进程，33 输出槽）", "默认 720896× → 单槽 46,137,344 (46.1M) | 工厂总 1,522,532,352 (1.5G)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emOverclocked")
					.defineInRange("emOverclocked", 720896, 1, 10_000_000);
			mekCentrifugeStackEmQuantum = builder
					.comment("进化通用机械 量子离心机（13 进程，39 输出槽）", "默认 851968× → 单槽 54,525,952 (54.5M) | 工厂总 2,126,562,048 (2.1G)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emQuantum")
					.defineInRange("emQuantum", 851968, 1, 10_000_000);
			mekCentrifugeStackEmDense = builder
					.comment("进化通用机械 致密离心机（15 进程，45 输出槽）", "默认 983040× → 单槽 62,914,560 (62.9M) | 工厂总 2,831,155,200 (2.8G)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emDense")
					.defineInRange("emDense", 983040, 1, 10_000_000);
			mekCentrifugeStackEmMultiversal = builder
					.comment("进化通用机械 多元宇宙离心机（17 进程，51 输出槽）", "默认 1114112× → 单槽 71,303,168 (71.3M) | 工厂总 3,636,461,568 (3.6G)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emMultiversal")
					.defineInRange("emMultiversal", 1114112, 1, 10_000_000);
			mekCentrifugeStackEmCreative = builder
					.comment("进化通用机械 创造离心机（19 进程，57 输出槽）", "默认 1245184× → 单槽 79,691,776 (79.7M) | 工厂总 4,542,431,232 (4.5G)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emCreative")
					.defineInRange("emCreative", 1245184, 1, 10_000_000);
		} else {
			mekCentrifugeStackEmOverclocked = null;
			mekCentrifugeStackEmQuantum = null;
			mekCentrifugeStackEmDense = null;
			mekCentrifugeStackEmMultiversal = null;
			mekCentrifugeStackEmCreative = null;
		}
		mekCentrifugeStackEmeAbsoluteOverclocked = builder
				.comment("进化通用机械:扩展 绝对超频离心机（12 进程，36 输出槽）", "默认 786432× → 单槽 50,331,648 (50.3M) | 工厂总 1,815,713,792 (1.8G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emeAbsoluteOverclocked")
				.defineInRange("emeAbsoluteOverclocked", 786432, 1, 10_000_000);
		mekCentrifugeStackEmeSupremeQuantum = builder
				.comment("进化通用机械:扩展 至尊量子离心机（14 进程，42 输出槽）", "默认 917504× → 单槽 58,720,256 (58.7M) | 工厂总 2,466,916,608 (2.5G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emeSupremeQuantum")
				.defineInRange("emeSupremeQuantum", 917504, 1, 10_000_000);
		mekCentrifugeStackEmeCosmicDense = builder
				.comment("进化通用机械:扩展 寰宇致密离心机（16 进程，48 输出槽）", "默认 1048576× → 单槽 67,108,864 (67.1M) | 工厂总 3,221,225,472 (3.2G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emeCosmicDense")
				.defineInRange("emeCosmicDense", 1048576, 1, 10_000_000);
		mekCentrifugeStackEmeInfiniteMultiversal = builder
				.comment("进化通用机械:扩展 无限多元离心机（18 进程，54 输出槽）", "默认 1179648× → 单槽 75,497,472 (75.5M) | 工厂总 4,076,866,560 (4.1G)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.stack_multiplier.emeInfiniteMultiversal")
				.defineInRange("emeInfiniteMultiversal", 1179648, 1, 10_000_000);
		builder.pop(); // stack_multiplier

		// ===== 输入槽堆叠倍率(input_stack_multiplier section)=====
		// 基础堆叠 64/槽 | 单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × processes
		// 蜜脾处理成倍产出,输入槽默认倍率为输出槽的 1/4
		builder.comment("输入槽堆叠倍率（按离心机等级）",
				"基础堆叠 64/槽 | 单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × processes",
				"蜜脾处理成倍产出，输入槽默认倍率为输出槽的 1/4").push("input_stack_multiplier");
		mekCentrifugeInputStackBasic = builder
				.comment("基础离心机（3 进程）", "默认 16384× → 单槽 1,048,576 (1.0M) | 工厂总 3,145,728 (3.1M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.basic")
				.defineInRange("basic", 16384, 1, 10_000_000);
		mekCentrifugeInputStackAdvanced = builder
				.comment("高级离心机（5 进程）", "默认 81920× → 单槽 5,242,880 (5.2M) | 工厂总 26,214,400 (26.2M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.advanced")
				.defineInRange("advanced", 81920, 1, 10_000_000);
		mekCentrifugeInputStackElite = builder
				.comment("精英离心机（7 进程）", "默认 114688× → 单槽 7,340,032 (7.3M) | 工厂总 51,380,224 (51.4M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.elite")
				.defineInRange("elite", 114688, 1, 10_000_000);
		mekCentrifugeInputStackUltimate = builder
				.comment("终极离心机（9 进程）", "默认 147456× → 单槽 9,437,184 (9.4M) | 工厂总 84,934,656 (84.9M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.ultimate")
				.defineInRange("ultimate", 147456, 1, 10_000_000);
		mekCentrifugeInputStackMeAbsolute = builder
				.comment("通用机械:扩展 绝对离心机（11 进程）", "默认 180224× → 单槽 11,534,336 (11.5M) | 工厂总 126,877,696 (126.9M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.meAbsolute")
				.defineInRange("meAbsolute", 180224, 1, 10_000_000);
		mekCentrifugeInputStackMeSupreme = builder
				.comment("通用机械:扩展 至尊离心机（13 进程）", "默认 212992× → 单槽 13,631,488 (13.6M) | 工厂总 177,209,344 (177.2M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.meSupreme")
				.defineInRange("meSupreme", 212992, 1, 10_000_000);
		mekCentrifugeInputStackMeCosmic = builder
				.comment("通用机械:扩展 寰宇离心机（15 进程）", "默认 245760× → 单槽 15,728,640 (15.7M) | 工厂总 235,929,600 (235.9M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.meCosmic")
				.defineInRange("meCosmic", 245760, 1, 10_000_000);
		mekCentrifugeInputStackMeInfinite = builder
				.comment("通用机械:扩展 无限离心机（17 进程）", "默认 278528× → 单槽 17,825,792 (17.8M) | 工厂总 303,038,464 (303.0M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.meInfinite")
				.defineInRange("meInfinite", 278528, 1, 10_000_000);
		if (MekCompatHooks.isEvolvedMekanismLoaded()) {
			mekCentrifugeInputStackEmOverclocked = builder
					.comment("进化通用机械 超频离心机（11 进程）", "默认 180224× → 单槽 11,534,336 (11.5M) | 工厂总 126,877,696 (126.9M)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emOverclocked")
					.defineInRange("emOverclocked", 180224, 1, 10_000_000);
			mekCentrifugeInputStackEmQuantum = builder
					.comment("进化通用机械 量子离心机（13 进程）", "默认 212992× → 单槽 13,631,488 (13.6M) | 工厂总 177,209,344 (177.2M)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emQuantum")
					.defineInRange("emQuantum", 212992, 1, 10_000_000);
			mekCentrifugeInputStackEmDense = builder
					.comment("进化通用机械 致密离心机（15 进程）", "默认 245760× → 单槽 15,728,640 (15.7M) | 工厂总 235,929,600 (235.9M)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emDense")
					.defineInRange("emDense", 245760, 1, 10_000_000);
			mekCentrifugeInputStackEmMultiversal = builder
					.comment("进化通用机械 多元宇宙离心机（17 进程）", "默认 278528× → 单槽 17,825,792 (17.8M) | 工厂总 303,038,464 (303.0M)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emMultiversal")
					.defineInRange("emMultiversal", 278528, 1, 10_000_000);
			mekCentrifugeInputStackEmCreative = builder
					.comment("进化通用机械 创造离心机（19 进程）", "默认 311296× → 单槽 19,922,944 (19.9M) | 工厂总 378,535,936 (378.5M)")
					.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emCreative")
					.defineInRange("emCreative", 311296, 1, 10_000_000);
		} else {
			mekCentrifugeInputStackEmOverclocked = null;
			mekCentrifugeInputStackEmQuantum = null;
			mekCentrifugeInputStackEmDense = null;
			mekCentrifugeInputStackEmMultiversal = null;
			mekCentrifugeInputStackEmCreative = null;
		}
		mekCentrifugeInputStackEmeAbsoluteOverclocked = builder
				.comment("进化通用机械:扩展 绝对超频离心机（12 进程）", "默认 196608× → 单槽 12,582,912 (12.6M) | 工厂总 150,994,944 (151.0M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emeAbsoluteOverclocked")
				.defineInRange("emeAbsoluteOverclocked", 196608, 1, 10_000_000);
		mekCentrifugeInputStackEmeSupremeQuantum = builder
				.comment("进化通用机械:扩展 至尊量子离心机（14 进程）", "默认 229376× → 单槽 14,680,064 (14.7M) | 工厂总 205,520,896 (205.5M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emeSupremeQuantum")
				.defineInRange("emeSupremeQuantum", 229376, 1, 10_000_000);
		mekCentrifugeInputStackEmeCosmicDense = builder
				.comment("进化通用机械:扩展 寰宇致密离心机（16 进程）", "默认 262144× → 单槽 16,777,216 (16.8M) | 工厂总 268,435,456 (268.4M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emeCosmicDense")
				.defineInRange("emeCosmicDense", 262144, 1, 10_000_000);
		mekCentrifugeInputStackEmeInfiniteMultiversal = builder
				.comment("进化通用机械:扩展 无限多元离心机（18 进程）", "默认 294912× → 单槽 18,874,368 (18.9M) | 工厂总 339,738,624 (339.7M)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.input_stack_multiplier.emeInfiniteMultiversal")
				.defineInRange("emeInfiniteMultiversal", 294912, 1, 10_000_000);
		builder.pop(); // input_stack_multiplier
	}

	/**
	 * 工厂方法:注册输出/输入槽堆叠倍率配置项并返回实例。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册堆叠倍率配置项的实例
	 */
	public static StackMultiplierConfigSection create(ModConfigSpec.Builder builder) {
		return new StackMultiplierConfigSection(builder);
	}
}
