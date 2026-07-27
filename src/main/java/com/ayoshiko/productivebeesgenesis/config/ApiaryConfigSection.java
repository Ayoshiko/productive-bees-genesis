package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.AppliedFluxIntegrationLoader;

/**
 * MEK 通用机械蜂箱配置段 — 从 {@link ServerConfig} 抽取的独立配置段。
 * <p>
 * 子分类：basic / ejection / stack_multiplier / ae2 / pb_upgrade。
 * 条件化注册：AE2 子 section 仅在 AE2 加载时注册；EM 工厂堆叠倍率仅在 EM 加载时注册。
 * 未加载时对应字段为 null，访问处通过模组守卫避免 NPE。
 */
public final class ApiaryConfigSection {

	// ========== 基础参数 ==========
	public final ModConfigSpec.LongValue apiaryEnergyPerTick;
	public final ModConfigSpec.IntValue apiaryProcessingTime;
	public final ModConfigSpec.IntValue apiaryFluidTankCapacity;

	// ========== 弹出策略 ==========
	public final ModConfigSpec.IntValue apiaryEjectDelay;
	public final ModConfigSpec.IntValue apiaryEjectDelayActive;
	public final ModConfigSpec.BooleanValue apiaryEjectMaxSpeedMode;
	public final ModConfigSpec.IntValue apiaryEjectMaxPerTick;
	public final ModConfigSpec.IntValue apiaryEjectBlockedThreshold;
	public final ModConfigSpec.IntValue apiaryEjectBlockedCooldown;

	// ========== 堆叠倍率（按工厂等级，仅作用于输出槽）==========
	public final ModConfigSpec.IntValue apiaryStackBasic;
	public final ModConfigSpec.IntValue apiaryStackAdvanced;
	public final ModConfigSpec.IntValue apiaryStackElite;
	public final ModConfigSpec.IntValue apiaryStackUltimate;
	public final ModConfigSpec.IntValue apiaryStackMeAbsolute;
	public final ModConfigSpec.IntValue apiaryStackMeSupreme;
	public final ModConfigSpec.IntValue apiaryStackMeCosmic;
	public final ModConfigSpec.IntValue apiaryStackMeInfinite;
	public final ModConfigSpec.IntValue apiaryStackEmOverclocked;
	public final ModConfigSpec.IntValue apiaryStackEmQuantum;
	public final ModConfigSpec.IntValue apiaryStackEmDense;
	public final ModConfigSpec.IntValue apiaryStackEmMultiversal;
	public final ModConfigSpec.IntValue apiaryStackEmCreative;
	public final ModConfigSpec.IntValue apiaryStackEmeAbsoluteOverclocked;
	public final ModConfigSpec.IntValue apiaryStackEmeSupremeQuantum;
	public final ModConfigSpec.IntValue apiaryStackEmeCosmicDense;
	public final ModConfigSpec.IntValue apiaryStackEmeInfiniteMultiversal;

	// ========== AE2 集成（条件化注册）==========
	public final ModConfigSpec.BooleanValue apiaryAeOutputEnabled;
	public final ModConfigSpec.BooleanValue apiaryAeFluidOutputEnabled;
	public final ModConfigSpec.BooleanValue apiaryAeEnergyInputEnabled;
	public final ModConfigSpec.BooleanValue apiaryPreferAppliedFluxOverAeEnergy;

	// ========== PB升级上限 ==========
	public final ModConfigSpec.IntValue apiaryPbUpgradeProductivityMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeTimeMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeGeneSamplerMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeBlockMaxCount;

	private ApiaryConfigSection(ModConfigSpec.Builder builder) {
		builder.comment("通用机械蜂箱设置").push("mek_apiary");

		// ===== 基础参数 =====
		builder.comment("基础参数").push("basic");
		apiaryEnergyPerTick = builder
				.comment("每个处理槽每tick的能量消耗(FE)")
				.defineInRange("energyPerTick", 50L, 1L, 1_000_000L);

		apiaryProcessingTime = builder
				.comment("基础处理时间(tick)")
				.defineInRange("processingTime", 1200, 1, 6000);

		// apiaryFluidTankCapacity 仅用于基础版蜂箱（非工厂版）
		// 工厂版流体罐容量按等级硬编码于 FactoryApiaryConfig，每级递增 256K mB (256 桶)
		apiaryFluidTankCapacity = builder
				.comment("基础版蜂箱流体罐容量(mB，仅作用于基础版蜂箱)",
						"工厂版按等级硬编码：每级 +256K mB（基础 256K → EME 无限多元 3.07M）")
				.defineInRange("fluidTankCapacity", 256_000, 1000, 1_000_000);
		builder.pop(); // basic

		// ===== 弹出策略 =====
		builder.comment("弹出策略").push("ejection");
		apiaryEjectDelay = builder
				.comment("输出槽自动弹出延迟(tick，推荐 2=0.1 秒)")
				.defineInRange("ejectDelay", 2, 0, 20);

		apiaryEjectDelayActive = builder
				.comment("输出槽仍有物品时的弹出延迟(tick，推荐 1=0.05 秒)")
				.defineInRange("ejectDelayActive", 1, 0, 20);

		apiaryEjectMaxSpeedMode = builder
				.comment("最大弹出速度模式（跳过节流逻辑，需目标容器空间充足）")
				.define("ejectMaxSpeedMode", false);

		apiaryEjectMaxPerTick = builder
				.comment("单 tick 最大弹出次数（0=无限制，最大速度模式下跳过）")
				.defineInRange("ejectMaxPerTick", 64, 0, 4096);

		apiaryEjectBlockedThreshold = builder
				.comment("连续未弹出物品多少次后进入冷却")
				.defineInRange("ejectBlockedThreshold", 3, 1, 20);

		apiaryEjectBlockedCooldown = builder
				.comment("阻塞冷却跳过的 tick 数（0=关闭）")
				.defineInRange("ejectBlockedCooldown", 15, 0, 200);
		builder.pop(); // ejection

		// ===== 输出槽堆叠倍率（按工厂等级）=====
		// 蜂箱仅作用于输出槽（蜜蜂产物），无输入槽倍率配置
		// 基础堆叠 64/槽 | 单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × outputSlotCount
		// outputSlotCount 按等级递增：基础 9 / 高级 12 / 精英 15 / 终极 18 / 扩展等级 +3 每级
		builder.comment("输出槽堆叠倍率（按工厂等级，仅作用于产物输出槽）",
				"基础堆叠 64/槽 | 单槽容量 = 64 × 倍率 | 工厂总容量 = 64 × 倍率 × outputSlotCount",
				"outputSlotCount：基础 9 / 高级 12 / 精英 15 / 终极 18 / 扩展等级每级 +3").push("stack_multiplier");
		apiaryStackBasic = builder
				.comment("基础蜂箱（3 进程，9 输出槽）", "默认 1× → 单槽 64 (64) | 工厂总 576 (576)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.basic")
				.defineInRange("basic", 1, 1, 100_000);
		apiaryStackAdvanced = builder
				.comment("高级蜂箱（5 进程，12 输出槽）", "默认 2× → 单槽 128 (128) | 工厂总 1,536 (1.5K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.advanced")
				.defineInRange("advanced", 2, 1, 100_000);
		apiaryStackElite = builder
				.comment("精英蜂箱（7 进程，15 输出槽）", "默认 4× → 单槽 256 (256) | 工厂总 3,840 (3.8K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.elite")
				.defineInRange("elite", 4, 1, 100_000);
		apiaryStackUltimate = builder
				.comment("终极蜂箱（9 进程，18 输出槽）", "默认 8× → 单槽 512 (512) | 工厂总 9,216 (9.2K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.ultimate")
				.defineInRange("ultimate", 8, 1, 100_000);
		apiaryStackMeAbsolute = builder
				.comment("通用机械:扩展 绝对蜂箱（11 进程，21 输出槽）", "默认 16× → 单槽 1,024 (1.0K) | 工厂总 21,504 (21.5K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.meAbsolute")
				.defineInRange("meAbsolute", 16, 1, 100_000);
		apiaryStackMeSupreme = builder
				.comment("通用机械:扩展 至尊蜂箱（13 进程，24 输出槽）", "默认 32× → 单槽 2,048 (2.0K) | 工厂总 49,152 (49.2K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.meSupreme")
				.defineInRange("meSupreme", 32, 1, 100_000);
		apiaryStackMeCosmic = builder
				.comment("通用机械:扩展 寰宇蜂箱（15 进程，27 输出槽）", "默认 64× → 单槽 4,096 (4.1K) | 工厂总 110,592 (110.6K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.meCosmic")
				.defineInRange("meCosmic", 64, 1, 100_000);
		apiaryStackMeInfinite = builder
				.comment("通用机械:扩展 无限蜂箱（17 进程，30 输出槽）", "默认 128× → 单槽 8,192 (8.2K) | 工厂总 245,760 (245.8K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.meInfinite")
				.defineInRange("meInfinite", 128, 1, 100_000);
		// EM 工厂（条件化注册，仅在 EvolvedMekanism 加载时）
		// EM 通过 Mixin 扩展 FactoryTier 枚举，添加 OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE（ordinal 4-8）
		if (com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks.isEvolvedMekanismLoaded()) {
			apiaryStackEmOverclocked = builder
					.comment("进化通用机械 超频蜂箱（11 进程，21 输出槽）", "默认 16× → 单槽 1,024 (1.0K) | 工厂总 21,504 (21.5K)")
					.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emOverclocked")
					.defineInRange("emOverclocked", 16, 1, 100_000);
			apiaryStackEmQuantum = builder
					.comment("进化通用机械 量子蜂箱（13 进程，24 输出槽）", "默认 32× → 单槽 2,048 (2.0K) | 工厂总 49,152 (49.2K)")
					.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emQuantum")
					.defineInRange("emQuantum", 32, 1, 100_000);
			apiaryStackEmDense = builder
					.comment("进化通用机械 致密蜂箱（15 进程，27 输出槽）", "默认 64× → 单槽 4,096 (4.1K) | 工厂总 110,592 (110.6K)")
					.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emDense")
					.defineInRange("emDense", 64, 1, 100_000);
			apiaryStackEmMultiversal = builder
					.comment("进化通用机械 多元宇宙蜂箱（17 进程，30 输出槽）", "默认 128× → 单槽 8,192 (8.2K) | 工厂总 245,760 (245.8K)")
					.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emMultiversal")
					.defineInRange("emMultiversal", 128, 1, 100_000);
			apiaryStackEmCreative = builder
					.comment("进化通用机械 创造蜂箱（19 进程，33 输出槽）", "默认 256× → 单槽 16,384 (16.4K) | 工厂总 540,672 (540.7K)")
					.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emCreative")
					.defineInRange("emCreative", 256, 1, 100_000);
		} else {
			apiaryStackEmOverclocked = null;
			apiaryStackEmQuantum = null;
			apiaryStackEmDense = null;
			apiaryStackEmMultiversal = null;
			apiaryStackEmCreative = null;
		}
		apiaryStackEmeAbsoluteOverclocked = builder
				.comment("进化通用机械:扩展 绝对超频蜂箱（12 进程，33 输出槽）", "默认 256× → 单槽 16,384 (16.4K) | 工厂总 540,672 (540.7K)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emeAbsoluteOverclocked")
				.defineInRange("emeAbsoluteOverclocked", 256, 1, 100_000);
		apiaryStackEmeSupremeQuantum = builder
				.comment("进化通用机械:扩展 至尊量子蜂箱（14 进程，36 输出槽）", "默认 512× → 单槽 32,768 (32.8K) | 工厂总 1,179,648 (1.2M)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emeSupremeQuantum")
				.defineInRange("emeSupremeQuantum", 512, 1, 100_000);
		apiaryStackEmeCosmicDense = builder
				.comment("进化通用机械:扩展 寰宇致密蜂箱（16 进程，39 输出槽）", "默认 1024× → 单槽 65,536 (65.5K) | 工厂总 2,555,904 (2.6M)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emeCosmicDense")
				.defineInRange("emeCosmicDense", 1024, 1, 100_000);
		apiaryStackEmeInfiniteMultiversal = builder
				.comment("进化通用机械:扩展 无限多元蜂箱（18 进程，42 输出槽）", "默认 4096× → 单槽 262,144 (262.1K) | 工厂总 11,010,048 (11.0M)")
				.translation("productivebeesgenesis.configuration.mek_apiary.stack_multiplier.emeInfiniteMultiversal")
				.defineInRange("emeInfiniteMultiversal", 4096, 1, 1_000_000);
		builder.pop(); // stack_multiplier

		// ===== AE2 集成（条件化注册）=====
		// 蜂箱 AE2 物品输出和能量输入默认开启（与离心机默认关闭不同），
		// 因为蜂箱产物需要主动输出避免阻塞，且蜂箱能量消耗较低。
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			builder.comment("AE2 集成").push("ae2");
			apiaryAeOutputEnabled = builder
					.comment("启用 AE2 直接输出（推送输出槽物品到 AE2 网络）")
					.define("aeOutputEnabled", true);

			apiaryAeFluidOutputEnabled = builder
					.comment("启用 AE2 流体输出（推送蜂蜜流体到 AE2 网络）")
					.define("aeFluidOutputEnabled", true);

			apiaryAeEnergyInputEnabled = builder
					.comment("启用 AE 网络能量输入（从 ME 网络提取 FE 注入本地能量容器）")
					.define("aeEnergyInputEnabled", true);

			if (AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) {
				apiaryPreferAppliedFluxOverAeEnergy = builder
						.comment("AE 网络能量优先级（true=优先 AppliedFlux，false=优先 AE2 原生能量）")
						.define("preferAppliedFluxOverAeEnergy", true);
			} else {
				apiaryPreferAppliedFluxOverAeEnergy = null;
			}
			builder.pop(); // ae2
		} else {
			apiaryAeOutputEnabled = null;
			apiaryAeFluidOutputEnabled = null;
			apiaryAeEnergyInputEnabled = null;
			apiaryPreferAppliedFluxOverAeEnergy = null;
		}

		// ===== PB升级上限 =====
		builder.comment("PB升级上限").push("pb_upgrade");
		apiaryPbUpgradeProductivityMaxCount = builder
				.comment("产量升级（α/β/γ/Ω）最大安装数量")
				.defineInRange("productivityMaxCount", 8, 1, 64);
		apiaryPbUpgradeTimeMaxCount = builder
				.comment("时间升级最大安装数量")
				.defineInRange("timeMaxCount", 8, 1, 64);
		apiaryPbUpgradeGeneSamplerMaxCount = builder
				.comment("基因采样升级最大安装数量")
				.defineInRange("geneSamplerMaxCount", 4, 1, 20);
		apiaryPbUpgradeBlockMaxCount = builder
				.comment("蜜脾块升级最大安装数量")
				.defineInRange("blockMaxCount", 1, 1, 1);
		builder.pop(); // pb_upgrade

		builder.pop(); // mek_apiary
	}

	/**
	 * 工厂方法：注册全部 MEK 通用机械蜂箱配置项并返回实例。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册全部蜂箱配置项的实例
	 */
	public static ApiaryConfigSection create(ModConfigSpec.Builder builder) {
		return new ApiaryConfigSection(builder);
	}
}
