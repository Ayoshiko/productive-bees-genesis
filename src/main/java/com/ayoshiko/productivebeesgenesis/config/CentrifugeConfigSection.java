package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * MEK离心机配置段 — 从 {@link ServerConfig} 抽取的独立配置段（Task 19）。
 * <p>
 * 遵循单一职责原则（SRP）：将离心机相关的全部配置项（能量、处理时间、弹出策略、
 * 阻塞冷却、AE2 限流、最大速度模式等）集中管理。
 * <p>
 * 通过 {@link #create(ModConfigSpec.Builder)} 工厂方法注册所有配置项并返回实例，
 * 由 {@link ServerConfig} 聚合持有。外部访问路径 {@code ModConfig.SERVER.mekCentrifugeXxx}
 * （向后兼容委托字段）保持不变。
 * <p>
 * 配置键名与层级（mek_centrifuge.*）与抽取前完全一致，纯重构无行为变更。
 */
public final class CentrifugeConfigSection {

	// ========== MEK离心机基础配置 ==========
	public final ModConfigSpec.IntValue mekCentrifugeEnergyPerTick;
	public final ModConfigSpec.IntValue mekCentrifugeProcessingTime;
	public final ModConfigSpec.IntValue mekCentrifugeEjectDelay;
	public final ModConfigSpec.IntValue mekCentrifugeEjectDelayActive;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankCapacity;
	/** 流体自动弹出速率（mB/tick），覆盖 Mekanism 默认的 1024 */
	public final ModConfigSpec.IntValue mekCentrifugeFluidEjectRate;
	public final ModConfigSpec.IntValue mekCentrifugeCombBlockMultiplier;
	// Task 13: AE2/管道拉取限流（防止 ME 接口过载拉取触发全量排序扫描）
	public final ModConfigSpec.IntValue mekCentrifugeMaxExtractPerTick;

	// Task 14: Ejector 输出阻塞冷却参数（解决输出侧阻塞时 outputItems 高频尝试导致 TPS 暴跌）
	public final ModConfigSpec.IntValue mekCentrifugeEjectBlockedThreshold;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBlockedCooldown;

	// Task 16: 输出槽内容未变化时跳过 outputItems，降低高倍加速下的 CPU 开销
	public final ModConfigSpec.BooleanValue mekCentrifugeEjectSkipUnchanged;
	public final ModConfigSpec.IntValue mekCentrifugeEjectSkipTicks;

	// Task 24: 最大弹出速度模式：关闭 Ejector 节流以最大化物品弹出速度
	public final ModConfigSpec.BooleanValue mekCentrifugeEjectMaxSpeedMode;

	// Task 23: Ejector 持续高负载下降频：最小调用间隔与长冷却
	public final ModConfigSpec.IntValue mekCentrifugeEjectMinInterval;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBusyThreshold;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBusyCooldown;

	// Step 5: 单 tick 最大弹出次数上限（0=无限制），限制 256× 加速下高频 outputItems 调用
	public final ModConfigSpec.IntValue mekCentrifugeEjectMaxPerTick;

	private CentrifugeConfigSection(ModConfigSpec.Builder builder) {
		builder.comment("MEK离心机设置").push("mek_centrifuge");

		mekCentrifugeEnergyPerTick = builder
				.comment("每个处理槽每tick的能量消耗(FE)")
				.defineInRange("energyPerTick", 50, 1, 10000);

		mekCentrifugeProcessingTime = builder
				.comment("基础处理时间(tick)")
				.defineInRange("processingTime", 200, 1, 6000);

		mekCentrifugeEjectDelay = builder
				.comment("输出槽自动弹出延迟(tick)", "原版Mekanism为10(0.5秒)", "减小值可加快多种物品弹出速度", "推荐值: 2(0.1秒) - 平衡性能与响应速度", "最小值0表示每tick弹出(高负载)", "最大值20(1秒)")
				.defineInRange("ejectDelay", 2, 0, 20);

		mekCentrifugeEjectDelayActive = builder
				.comment("输出槽仍有物品时(活动状态)的弹出延迟(tick)", "独立于ejectDelay, 仅在输出槽非空时使用", "推荐值: 1(0.05秒) - 最大化高产出场景吞吐", "最小值0表示每tick弹出(高负载)", "最大值20(1秒)", "注意: 运行时会被自动限制为不超过ejectDelay, 避免活动延迟大于空闲延迟的反直觉组合")
				.defineInRange("ejectDelayActive", 1, 0, 20);

		mekCentrifugeFluidTankCapacity = builder
				.comment("流体输出罐基础容量(mB)", "工厂版会按并行数倍增此值", "默认值：256000（256桶）")
				.defineInRange("fluidTankCapacity", 256000, 1000, 1_000_000);

		mekCentrifugeFluidEjectRate = builder
				.comment("流体自动弹出速率(mB/tick)",
						"覆盖Mekanism默认的1024 mB/tick，提升工厂高产出时的流体输出速度",
						"默认值：16384")
				.defineInRange("mekCentrifugeFluidEjectRate", 16384, 1, Integer.MAX_VALUE);

		mekCentrifugeCombBlockMultiplier = builder
				.comment("万象创世蜜脾块相对于蜜脾的产物倍率")
				.defineInRange("combBlockMultiplier", 4, 1, 16);

		// Task 13: AE2/管道拉取限流 — 默认0=无限制，不影响正常游戏
		mekCentrifugeMaxExtractPerTick = builder
				.comment("每游戏刻外部通过管道/AE2从离心机输出槽拉取的最大物品总数（0=无限制）",
						"防止ME接口过载拉取导致主线程卡顿")
				.defineInRange("mekCentrifugeMaxExtractPerTick", 0, 0, 1024);

		// Task 14: Ejector 输出阻塞冷却 — 输出侧无法接收物品时降低尝试频率
		mekCentrifugeEjectBlockedThreshold = builder
				.comment("连续多少次 outputItems 未弹出物品后进入冷却",
						"默认3次，过小会导致冷却过于敏感，过大会降低缓解效果")
				.defineInRange("mekCentrifugeEjectBlockedThreshold", 3, 1, 20);

		mekCentrifugeEjectBlockedCooldown = builder
				.comment("进入阻塞冷却跳过的 tick 数",
						"默认15 tick（0.75秒），0=关闭冷却（不推荐）",
						"冷却结束后会再次尝试弹出，保证物品不会永久卡住")
				.defineInRange("mekCentrifugeEjectBlockedCooldown", 15, 0, 200);

		// Task 16: 输出槽内容未变化时跳过 outputItems，降低高倍加速下的 CPU 开销
		mekCentrifugeEjectSkipUnchanged = builder
				.comment("当输出槽内容未变化时跳过 Ejector 输出尝试，降低高倍加速下的 CPU 开销")
				.define("ejectSkipUnchanged", true);

		mekCentrifugeEjectSkipTicks = builder
				.comment("输出槽内容未变化时连续跳过的 tick 数（0=不跳过）", "默认值：1")
				.defineInRange("ejectSkipTicks", 1, 0, 20);

		// Task 24: 最大弹出速度模式
		mekCentrifugeEjectMaxSpeedMode = builder
				.comment("启用最大弹出速度模式",
						"开启后跳过 Ejector 的未变化跳过、最小调用间隔和高负载长冷却逻辑，",
						"仅在输出侧完全阻塞时保留阻塞冷却，以最大化物品弹出速度",
						"适合目标容器有充足空间且服务器性能冗余的场景")
				.define("ejectMaxSpeedMode", false);

		// Task 23: Ejector 持续高负载下降频
		mekCentrifugeEjectMinInterval = builder
				.comment("输出槽内容持续变化时，两次 outputItems 调用之间的最小 tick 间隔（0=关闭）",
						"用于产出速度高于弹出速度、内容未变化跳过失效时的兜底降频",
						"默认值：0")
				.defineInRange("ejectMinInterval", 0, 0, 20);

		mekCentrifugeEjectBusyThreshold = builder
				.comment("连续多少次 outputItems 未减少输出槽物品总量后进入长冷却",
						"默认 5 次；过小会过于敏感，过大会降低缓解效果")
				.defineInRange("ejectBusyThreshold", 5, 1, 50);

		mekCentrifugeEjectBusyCooldown = builder
				.comment("进入长冷却后跳过的 tick 数（0=关闭）",
						"默认 40 tick（2 秒）；冷却结束后会再次尝试弹出")
				.defineInRange("ejectBusyCooldown", 40, 0, 600);

		// Step 5: 单 tick 最大弹出次数上限
		mekCentrifugeEjectMaxPerTick = builder
				.comment("单 tick 内 outputItems 的最大调用次数上限（0=无限制）",
						"限制 256× 加速下单 tick 产生海量物品时反复调用 outputItems",
						"tickServer 每 tick 对 ITEM + FLUID 各调用一次 outputItems，上限应 ≥ 2",
						"默认 64；最大速度模式下跳过此上限")
				.defineInRange("ejectMaxPerTick", 64, 0, 4096);

		builder.pop(); // mek_centrifuge
	}

	/**
	 * 工厂方法：注册全部 MEK离心机配置项并返回实例。
	 * <p>
	 * 调用此方法会执行 {@code builder.push("mek_centrifuge")} ... {@code builder.pop()}，
	 * 调用方需保证在合适的层级顺序中调用，以维持配置文件中节的顺序与抽取前一致。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册全部离心机配置项的实例
	 */
	public static CentrifugeConfigSection create(ModConfigSpec.Builder builder) {
		return new CentrifugeConfigSection(builder);
	}
}