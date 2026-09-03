package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
	 * MEK离心机配置段 — 从 {@link ServerConfig} 抽取的独立配置段。
	 * <p>
	 * 子分类:basic / ejection / io_limit / ae2 / pb_upgrade / me_upgrade。
	 * 堆叠倍率和流体罐倍率已抽取至 {@link StackMultiplierConfigSection} / {@link FluidTankMultiplierConfigSection}。
	 * <p>
	 * AE2 与 AppliedFlux 配置键始终注册，运行时访问仍由依赖检测保护。
	 *
	 * @since 2.0.0
	 * @see StackMultiplierConfigSection 堆叠倍率子段
	 * @see FluidTankMultiplierConfigSection 流体罐倍率子段
	 */
public final class CentrifugeConfigSection {

	// ========== MEK离心机基础配置 ==========
	public final ModConfigSpec.LongValue mekCentrifugeEnergyPerTick;
	public final ModConfigSpec.LongValue mekCentrifugeEnergyStorage;
	public final ModConfigSpec.IntValue mekCentrifugeProcessingTime;
	public final ModConfigSpec.IntValue mekCentrifugeEjectDelay;
	public final ModConfigSpec.IntValue mekCentrifugeEjectDelayActive;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankCapacity;
	/** 多流体槽模式开关:false=单槽共享(默认),true=按流体类型动态分配独立槽位 */
	public final ModConfigSpec.BooleanValue mekCentrifugeMultiFluidTank;
	/**
	 * v2.0.9: 每种流体类型最大占用槽位数
	 * <br/>
	 * 防止高产出流体（如蜂蜜）占用所有槽位,为其他流体预留空位。
	 * 0=自动计算为 Math.max(1, maxTanks/2),确保至少 2 种流体可共存;
	 * >0=手动指定配额。
	 */
	public final ModConfigSpec.IntValue mekCentrifugeMaxTanksPerFluid;
	/**
	 * Task 6: 流体弹出速率(mB/tick),控制 Ejector/侧面配置每次弹出的流体量上限
	 * <br/>
	 * 默认 256,范围 1-Integer.MAX_VALUE。允许玩家根据工厂等级调整弹出速率,
	 * 由 {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidSideConfigHandler#getCachedEjectRate}
	 * 通过 100-tick CAS 缓存读取,避免 TPS 退化。
	 */
	public final ModConfigSpec.IntValue mekCentrifugeFluidEjectRate;
	public final ModConfigSpec.IntValue mekCentrifugeCombBlockMultiplier;
	public final ModConfigSpec.IntValue mekCentrifugeMaxExtractPerTick;
	public final ModConfigSpec.IntValue mekCentrifugeMaxOpsPerTick;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBlockedThreshold;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBlockedCooldown;
	public final ModConfigSpec.BooleanValue mekCentrifugeEjectSkipUnchanged;
	public final ModConfigSpec.IntValue mekCentrifugeEjectSkipTicks;
	public final ModConfigSpec.BooleanValue mekCentrifugeEjectMaxSpeedMode;
	public final ModConfigSpec.IntValue mekCentrifugeEjectMinInterval;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBusyThreshold;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBusyCooldown;
	public final ModConfigSpec.IntValue mekCentrifugeEjectMaxPerTick;

	// ========== 子配置段引用(组合关系,保持外部访问兼容)==========
	/** 输出槽 + 输入槽堆叠倍率子段(stack_multiplier + input_stack_multiplier section) */
	public final StackMultiplierConfigSection stackMultiplier;
	/** 流体罐倍率子段(fluid_tank_multiplier section) */
	public final FluidTankMultiplierConfigSection fluidTankMultiplier;

	// ========== AE2 集成 ==========
	public final ModConfigSpec.BooleanValue mekCentrifugeAeOutputEnabled;
	public final ModConfigSpec.BooleanValue mekCentrifugeAeFluidOutputEnabled;
	public final ModConfigSpec.BooleanValue mekCentrifugeAeEnergyInputEnabled;
	public final ModConfigSpec.BooleanValue mekCentrifugePreferAppliedFluxOverAeEnergy;
	/** 允许提取 AE2 原生能量；关闭后仅从 AppliedFlux FE 提取。 */
	public final ModConfigSpec.BooleanValue mekCentrifugeAeNativeEnergyInputEnabled;
	public final ModConfigSpec.BooleanValue mekCentrifugeAeInputEnabled;
	public final ModConfigSpec.IntValue mekCentrifugeAeInputRatePerTick;
	public final ModConfigSpec.IntValue mekCentrifugeAeInputIntervalTicks;
	public final ModConfigSpec.IntValue mekCentrifugeAeInputMinPages;

	// ========== PB/ME 升级上限 ==========
	public final ModConfigSpec.IntValue mekCentrifugePbUpgradeProductivityMaxCount;
	public final ModConfigSpec.IntValue mekCentrifugePbUpgradeTimeMaxCount;
	/** 稳定性升级最大安装数量（仅离心机生效，对齐 PB 原版上限 7） */
	public final ModConfigSpec.IntValue mekCentrifugePbUpgradeStabilityMaxCount;
	public final ModConfigSpec.IntValue mekCentrifugeMaxStackUpgrades;

	// ========== 熔炉配方兼容（总开关） ==========
	public final ModConfigSpec.BooleanValue mekCentrifugeSmeltingCompatEnabled;

	private CentrifugeConfigSection(
			ModConfigSpec.Builder builder,
			ModConfigSpec.Builder capacityBuilder) {
		builder.comment("通用机械离心机设置").push("mek_centrifuge");
		// ===== 基础参数 =====
		builder.comment("基础参数").push("basic");
		mekCentrifugeEnergyPerTick = builder
				.comment("每个处理槽每tick的配置能耗(FE)",
						"机器注册时应用 1/5 内置平衡系数，不能整除时向上取整")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.energyPerTick")
				.defineInRange("energyPerTick", 50L, 1L, Long.MAX_VALUE);
		mekCentrifugeEnergyStorage = builder.comment("机械离心机配置基础容量(FE)",
						"机器注册时应用 1/2 内置平衡系数；工厂再按进程数倍增")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.energyStorage")
				.defineInRange("energyStorage", 100_000L, 1L, Long.MAX_VALUE);
		mekCentrifugeProcessingTime = builder
				.comment("基础处理时间(tick)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.processingTime")
				.defineInRange("processingTime", 200, 1, 6000);
		mekCentrifugeFluidTankCapacity = builder
				.comment("流体输出罐基础容量(mB，仅作用于基础离心机)",
						"工厂总容量 = 此值 × processes × fluid_tank_multiplier（超过 2.15G mB 时截断）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.fluidTankCapacity")
				.defineInRange("fluidTankCapacity", 256000, 1000, Integer.MAX_VALUE);
		// 布尔配置避免枚举值翻译问题:NeoForge 配置屏幕对枚举值显示 name() 原文(SINGLE/MULTI_PER_FLUID),
		// .translation() 仅翻译 label 不翻译枚举值;布尔值显示本地化 True/False 开关,无需翻译枚举值
		mekCentrifugeMultiFluidTank = builder
				.comment("是否启用多流体槽模式(按流体类型动态分配独立槽位)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.multiFluidTank")
				.define("multiFluidTank", true);
		// v2.0.9: 每种流体类型最大占用槽位数（配额机制）
		mekCentrifugeMaxTanksPerFluid = builder
				.comment("每种流体最多占用的槽位数；0=自动分配")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.maxTanksPerFluid")
				.defineInRange("maxTanksPerFluid", 0, 0, 64);
		// Task 3: 移除 mekCentrifugeMaxFluidTanks 配置,maxTanks 直接使用 tier.processes(作为上限,按需创建)
		// 原理:MultiFluidTankHolder 的 maxTanks 是上限,槽位通过 getTankForInsert 按需创建
		// Tab 窗口显示当前已分配槽位数(通过同步值 fluidOutputTankCount),而非 tier.processes
		mekCentrifugeFluidEjectRate = builder
				.comment("每 tick 流体弹出量（mB）", "数值越高，物流传输开销越大")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.fluidEjectRate")
				.defineInRange("fluidEjectRate", 256, 1, Integer.MAX_VALUE);
		mekCentrifugeCombBlockMultiplier = builder
				.comment("万象创世蜜脾块相对于蜜脾的产物倍率")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.combBlockMultiplier")
				.defineInRange("combBlockMultiplier", 4, 1, 16);
		mekCentrifugeMaxOpsPerTick = builder
				.comment("单tick最大PB配方操作数（0=无限制，允许堆叠升级 2^N 并行效果完整生效）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.basic.maxOpsPerTick")
				.defineInRange("maxOpsPerTick", 0, 0, Integer.MAX_VALUE);
		builder.pop(); // basic

		// ===== 弹出策略 =====
		builder.comment("弹出策略").push("ejection");
		mekCentrifugeEjectDelay = builder
				.comment("输出槽自动弹出延迟(tick，原版 10，推荐 2)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectDelay")
				.defineInRange("ejectDelay", 2, 0, 20);
		mekCentrifugeEjectDelayActive = builder
				.comment("输出槽仍有物品时的弹出延迟(tick，推荐 1，不超过 ejectDelay)")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectDelayActive")
				.defineInRange("ejectDelayActive", 1, 0, 20);
		mekCentrifugeEjectSkipUnchanged = builder
				.comment("输出槽内容未变化时跳过 Ejector 输出以降低 CPU 开销")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectSkipUnchanged")
				.define("ejectSkipUnchanged", true);
		mekCentrifugeEjectSkipTicks = builder
				.comment("输出未变化时连续跳过的 tick 数（0=不跳过）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectSkipTicks")
				.defineInRange("ejectSkipTicks", 1, 0, 20);
		mekCentrifugeEjectMaxSpeedMode = builder
				.comment("最大弹出速度模式（跳过节流逻辑，需目标容器空间充足）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectMaxSpeedMode")
				.define("ejectMaxSpeedMode", false);
		mekCentrifugeEjectMinInterval = builder
				.comment("输出持续变化时两次调用的最小间隔（0=关闭）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectMinInterval")
				.defineInRange("ejectMinInterval", 0, 0, 20);
		mekCentrifugeEjectBusyThreshold = builder
				.comment("连续未减少输出总量多少次后进入长冷却")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectBusyThreshold")
				.defineInRange("ejectBusyThreshold", 5, 1, 50);
		mekCentrifugeEjectBusyCooldown = builder
				.comment("高负载长冷却跳过的 tick 数（0=关闭）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectBusyCooldown")
				.defineInRange("ejectBusyCooldown", 40, 0, 600);
		mekCentrifugeEjectMaxPerTick = builder
				.comment("单 tick 最大 outputItems 调用次数（0=无限制，最大速度模式下跳过）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectMaxPerTick")
				.defineInRange("ejectMaxPerTick", 64, 0, 4096);
		mekCentrifugeEjectBlockedThreshold = builder
				.comment("连续未弹出物品多少次后进入冷却")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectBlockedThreshold")
				.defineInRange("ejectBlockedThreshold", 3, 1, 20);
		mekCentrifugeEjectBlockedCooldown = builder
				.comment("阻塞冷却跳过的 tick 数（0=关闭）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ejection.ejectBlockedCooldown")
				.defineInRange("ejectBlockedCooldown", 15, 0, 200);
		builder.pop(); // ejection

		// ===== IO 限流 =====
		builder.comment("IO 限流").push("io_limit");
		mekCentrifugeMaxExtractPerTick = builder
				.comment("每tick外部通过管道/AE2拉取的最大物品数（0=无限制）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.io_limit.maxExtractPerTick")
				.defineInRange("maxExtractPerTick", 0, 0, 1024);
		builder.pop(); // io_limit

		// 配置键始终注册，依赖缺失时仅由运行时集成层忽略。
		builder.comment("AE2 集成（未安装对应模组时保留配置但不生效）").push("ae2");
		mekCentrifugeAeOutputEnabled = builder
				.comment("启用 AE2 直接输出（推送输出槽物品到 AE2 网络）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeOutputEnabled")
				.define("aeOutputEnabled", true);
		mekCentrifugeAeFluidOutputEnabled = builder
				.comment("启用 AE2 流体输出（推送蜂蜜到 AE2 网络，独立于物品输出）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeFluidOutputEnabled")
				.define("aeFluidOutputEnabled", true);
		mekCentrifugeAeEnergyInputEnabled = builder
				.comment("启用 AE 网络能量输入（从 ME 网络提取 FE 注入本地能量容器）", "默认开启")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeEnergyInputEnabled")
				.define("aeEnergyInputEnabled", true);
		mekCentrifugePreferAppliedFluxOverAeEnergy = builder
				.comment("AE 网络能量优先级（true=优先 AppliedFlux，false=优先 AE2 原生能量）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.preferAppliedFluxOverAeEnergy")
				.define("preferAppliedFluxOverAeEnergy", true);
		mekCentrifugeAeNativeEnergyInputEnabled = builder
				.comment("允许提取 AE2 原生能量（关闭后仅从 AppliedFlux 存储的 FE 提取，",
						"避免网络 FE 不足时过量抽取 AE 原生能量导致 ME 网络断电）", "默认开启")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeNativeEnergyInputEnabled")
				.define("aeNativeEnergyInputEnabled", true);
		mekCentrifugeAeInputEnabled = builder
				.comment("启用 AE2 输入拉取（离心机主动从 ME 网络拉取输入物品）",
						"默认开启；每台离心机默认关闭，需在机器上单独开启")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeInputEnabled")
				.define("aeInputEnabled", true);
		mekCentrifugeAeInputRatePerTick = builder
				.comment("每次拉取最大物品数量（1-2147483647，过大可能增加 CPU 开销）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeInputRatePerTick")
				.defineInRange("aeInputRatePerTick", 1024, 1, Integer.MAX_VALUE);
		mekCentrifugeAeInputIntervalTicks = builder
				.comment("拉取触发间隔（游戏刻，值越大 CPU 开销越低但响应越慢）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeInputIntervalTicks")
				.defineInRange("aeInputIntervalTicks", 10, 1, 200);
		mekCentrifugeAeInputMinPages = builder
				.comment("AE2 输入过滤窗口最小页数（1-16）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.ae2.aeInputMinPages")
				.defineInRange("aeInputMinPages", 4, 1, 16);
		builder.pop(); // ae2

		// ===== PB升级上限 =====
		builder.comment("PB升级上限").push("pb_upgrade");
		mekCentrifugePbUpgradeProductivityMaxCount = builder
				.comment("产量升级（α/β/γ/Ω）最大安装数量")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.pb_upgrade.productivityMaxCount")
				.defineInRange("productivityMaxCount",
						BalanceConfig.DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT, 1, 64);
		mekCentrifugePbUpgradeTimeMaxCount = builder
				.comment("时间升级最大安装数量")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.pb_upgrade.timeMaxCount")
				.defineInRange("timeMaxCount",
						BalanceConfig.DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT, 1, 64);
		mekCentrifugePbUpgradeStabilityMaxCount = builder
				.comment("稳定性升级最大安装数量（仅离心机生效，对齐 PB 原版上限 7）",
						"每级 +0.15 非保底产物概率加成，满槽（7个）时所有概率产物变保底")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.pb_upgrade.stabilityMaxCount")
				.defineInRange("stabilityMaxCount", 7, 1, 7);
		builder.pop(); // pb_upgrade

		// ===== 通用机械:扩展 升级上限 =====
		builder.comment("通用机械:扩展 升级上限").push("me_upgrade");
		mekCentrifugeMaxStackUpgrades = builder
				.comment("通用机械:扩展 堆叠升级最大数量（2^N 倍并行，仅作用于本模组离心机工厂）")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.me_upgrade.maxStackUpgrades")
				.defineInRange("maxStackUpgrades",
						BalanceConfig.DEFAULT_CONFIGURED_STACK_UPGRADE_LIMIT, 8, 32);
		builder.pop(); // me_upgrade

		// ===== 熔炉配方兼容（总开关）=====
		builder.comment("离心机电力熔炼炉配方兼容（总开关）").push("smelting_compat");
		mekCentrifugeSmeltingCompatEnabled = builder
				.comment("允许离心机处理电力熔炼炉（SMELTING）配方",
						"默认开启；关闭后所有离心机的熔炉配方兼容开关无法使用",
						"每台离心机是否兼容熔炉配方由 GUI 中的 per-tile 开关控制，默认关闭")
				.translation("productivebeesgenesis.configuration.mek_centrifuge.smelting_compat.smeltingCompatEnabled")
				.define("smeltingCompatEnabled", true);
		builder.pop(); // smelting_compat

		builder.pop(); // mek_centrifuge

		capacityBuilder.comment("通用机械离心机容量矩阵").push("mek_centrifuge");
		this.stackMultiplier = StackMultiplierConfigSection.create(capacityBuilder);
		this.fluidTankMultiplier = FluidTankMultiplierConfigSection.create(capacityBuilder);
		capacityBuilder.pop();
	}

	/**
	 * 工厂方法:注册全部 MEK离心机配置项并返回实例。
	 *
	 * @param builder 机器参数配置构建器
	 * @param capacityBuilder 容量矩阵配置构建器
	 * @return 已注册全部离心机配置项的实例
	 */
	public static CentrifugeConfigSection create(
			ModConfigSpec.Builder builder,
			ModConfigSpec.Builder capacityBuilder) {
		return new CentrifugeConfigSection(builder, capacityBuilder);
	}
}
