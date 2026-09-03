package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 通用机械蜂箱配置段。 */
public final class ApiaryConfigSection {

	public final ModConfigSpec.LongValue apiaryEnergyPerTick;
	public final ModConfigSpec.IntValue apiaryProcessingTime;
	public final ModConfigSpec.IntValue apiaryFluidTankCapacity;
	public final ModConfigSpec.IntValue apiaryEjectDelay;
	public final ModConfigSpec.IntValue apiaryEjectDelayActive;
	public final ModConfigSpec.BooleanValue apiaryEjectMaxSpeedMode;
	public final ModConfigSpec.IntValue apiaryEjectMaxPerTick;
	public final ModConfigSpec.IntValue apiaryEjectBlockedThreshold;
	public final ModConfigSpec.IntValue apiaryEjectBlockedCooldown;

	/** 蜂箱产物输出槽倍率。 */
	public final FactoryTierConfigValues stackMultiplier;

	public final ModConfigSpec.BooleanValue apiaryAeOutputEnabled;
	public final ModConfigSpec.BooleanValue apiaryAeFluidOutputEnabled;
	public final ModConfigSpec.BooleanValue apiaryAeEnergyInputEnabled;
	public final ModConfigSpec.BooleanValue apiaryPreferAppliedFluxOverAeEnergy;
	public final ModConfigSpec.BooleanValue apiaryAeNativeEnergyInputEnabled;

	public final ModConfigSpec.IntValue apiaryPbUpgradeProductivityMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeTimeMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeGeneSamplerMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeBlockMaxCount;

	private ApiaryConfigSection(
			ModConfigSpec.Builder builder,
			ModConfigSpec.Builder capacityBuilder) {
		builder.comment("通用机械蜂箱设置").push("mek_apiary");

		builder.comment("基础参数").push("basic");
		apiaryEnergyPerTick = builder.comment("每个处理槽每 tick 的配置能耗（FE）",
				"机器注册时应用 1/5 内置平衡系数，不能整除时向上取整")
				.translation("productivebeesgenesis.configuration.mek_apiary.basic.energyPerTick")
				.defineInRange("energyPerTick", 50L, 1L, Long.MAX_VALUE);
		apiaryProcessingTime = builder.comment("基础处理时间（tick）")
				.translation("productivebeesgenesis.configuration.mek_apiary.basic.processingTime")
				.defineInRange("processingTime", 1200, 1, 6000);
		apiaryFluidTankCapacity = builder.comment("基础版蜂箱流体罐容量（mB，仅作用于基础版蜂箱）",
				"工厂版容量按等级计算")
				.translation("productivebeesgenesis.configuration.mek_apiary.basic.fluidTankCapacity")
				.defineInRange("fluidTankCapacity", 256_000, 1000, Integer.MAX_VALUE);
		builder.pop();

		builder.comment("弹出策略").push("ejection");
		apiaryEjectDelay = builder.comment("输出槽自动弹出延迟（tick，推荐 2）")
				.translation("productivebeesgenesis.configuration.mek_apiary.ejection.ejectDelay")
				.defineInRange("ejectDelay", 2, 0, 20);
		apiaryEjectDelayActive = builder.comment("输出槽仍有物品时的弹出延迟（tick，推荐 1）")
				.translation("productivebeesgenesis.configuration.mek_apiary.ejection.ejectDelayActive")
				.defineInRange("ejectDelayActive", 1, 0, 20);
		apiaryEjectMaxSpeedMode = builder.comment("最大弹出速度模式（跳过节流逻辑）")
				.translation("productivebeesgenesis.configuration.mek_apiary.ejection.ejectMaxSpeedMode")
				.define("ejectMaxSpeedMode", false);
		apiaryEjectMaxPerTick = builder.comment("单 tick 最大弹出次数（0=无限制）")
				.translation("productivebeesgenesis.configuration.mek_apiary.ejection.ejectMaxPerTick")
				.defineInRange("ejectMaxPerTick", 64, 0, 4096);
		apiaryEjectBlockedThreshold = builder.comment("连续未弹出物品多少次后进入冷却")
				.translation("productivebeesgenesis.configuration.mek_apiary.ejection.ejectBlockedThreshold")
				.defineInRange("ejectBlockedThreshold", 3, 1, 20);
		apiaryEjectBlockedCooldown = builder.comment("阻塞冷却跳过的 tick 数（0=关闭）")
				.translation("productivebeesgenesis.configuration.mek_apiary.ejection.ejectBlockedCooldown")
				.defineInRange("ejectBlockedCooldown", 15, 0, 200);
		builder.pop();

		capacityBuilder.comment("通用机械蜂箱容量矩阵").push("mek_apiary");
		capacityBuilder.comment(
				"输出槽堆叠倍率（按工厂等级，仅作用于产物输出槽）",
				"单槽容量 = 64 × 倍率；工厂总容量按进程数和输出槽数量计算")
				.push("stack_multiplier");
		stackMultiplier = FactoryTierConfigValues.register(
				capacityBuilder,
				"productivebeesgenesis.configuration.mek_apiary.stack_multiplier",
				FactoryTierKey::apiaryOutputStackDefault);
		capacityBuilder.pop();
		capacityBuilder.pop();

		// 配置键始终注册，避免用户临时移除可选依赖时丢失自定义值。
		builder.comment("AE2 集成（未安装对应模组时保留配置但不生效）").push("ae2");
		apiaryAeOutputEnabled = builder.comment("启用 AE2 直接输出")
				.translation("productivebeesgenesis.configuration.mek_apiary.ae2.aeOutputEnabled")
				.define("aeOutputEnabled", true);
		apiaryAeFluidOutputEnabled = builder.comment("启用 AE2 流体输出")
				.translation("productivebeesgenesis.configuration.mek_apiary.ae2.aeFluidOutputEnabled")
				.define("aeFluidOutputEnabled", true);
		apiaryAeEnergyInputEnabled = builder.comment("启用 AE 网络能量输入")
				.translation("productivebeesgenesis.configuration.mek_apiary.ae2.aeEnergyInputEnabled")
				.define("aeEnergyInputEnabled", true);
		apiaryPreferAppliedFluxOverAeEnergy = builder.comment("AE 网络能量优先级")
				.translation("productivebeesgenesis.configuration.mek_apiary.ae2.preferAppliedFluxOverAeEnergy")
				.define("preferAppliedFluxOverAeEnergy", true);
		apiaryAeNativeEnergyInputEnabled = builder.comment("允许提取 AE2 原生能量")
				.translation("productivebeesgenesis.configuration.mek_apiary.ae2.aeNativeEnergyInputEnabled")
				.define("aeNativeEnergyInputEnabled", true);
		builder.pop();

		builder.comment("PB 升级上限").push("pb_upgrade");
		apiaryPbUpgradeProductivityMaxCount = builder.comment("产量升级（α/β/γ/Ω）最大安装数量")
				.translation("productivebeesgenesis.configuration.mek_apiary.pb_upgrade.productivityMaxCount")
				.defineInRange("productivityMaxCount", BalanceConfig.DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT, 1, 64);
		apiaryPbUpgradeTimeMaxCount = builder.comment("时间升级最大安装数量")
				.translation("productivebeesgenesis.configuration.mek_apiary.pb_upgrade.timeMaxCount")
				.defineInRange("timeMaxCount", BalanceConfig.DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT, 1, 64);
		apiaryPbUpgradeGeneSamplerMaxCount = builder.comment("基因采样升级最大安装数量")
				.translation("productivebeesgenesis.configuration.mek_apiary.pb_upgrade.geneSamplerMaxCount")
				.defineInRange("geneSamplerMaxCount", 4, 1, 20);
		apiaryPbUpgradeBlockMaxCount = builder.comment("蜜脾块升级最大安装数量")
				.translation("productivebeesgenesis.configuration.mek_apiary.pb_upgrade.blockMaxCount")
				.defineInRange("blockMaxCount", 1, 1, 1);
		builder.pop();
		builder.pop();
	}

	public static ApiaryConfigSection create(
			ModConfigSpec.Builder builder,
			ModConfigSpec.Builder capacityBuilder) {
		return new ApiaryConfigSection(builder, capacityBuilder);
	}
}
