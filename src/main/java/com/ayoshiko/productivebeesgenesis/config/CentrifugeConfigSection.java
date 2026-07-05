package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.AppliedFluxIntegrationLoader;

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
 * <b>v1.8.1 子分类结构</b>：原扁平 {@code mek_centrifuge.*} 拆分为 4 个子 section，
 * 提升 NeoForge 配置界面的可读性与导航效率：
 * <ul>
 *   <li>{@code mek_centrifuge.basic} — 基础参数（能量、处理时间、流体罐、产物倍率）</li>
 *   <li>{@code mek_centrifuge.ejection} — 弹出策略（延迟、跳过、限流、阻塞/高负载冷却）</li>
 *   <li>{@code mek_centrifuge.io_limit} — IO 限流（外部拉取上限）</li>
 *   <li>{@code mek_centrifuge.ae2} — AE2 集成（直接输出 + 网络能量输入，条件化注册）</li>
 * </ul>
 * <b>配置项键名兼容</b>：所有配置项的 key（如 {@code energyPerTick}、{@code ejectDelay}）
 * 保持不变，仅 section 路径变化。NeoForge 配置文件迁移由模组加载时自动处理。
 * <p>
 * <b>v1.8.1 条件化注册</b>：AE2 子 section 仅在 {@link Ae2IntegrationLoader#isAe2Loaded()}
 * 为 true 时注册；其中的 {@code preferAppliedFluxOverAeEnergy} 仅在
 * {@link AppliedFluxIntegrationLoader#isAppliedFluxLoaded()} 为 true 时注册。
 * 未加载时对应字段为 null，访问处通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫避免 NPE。
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

	// AE2 直接输出集成：离心机作为 AE2 网格节点主动推送输出
	// v1.8.1：AE2 未加载时为 null（条件化注册）
	public final ModConfigSpec.BooleanValue mekCentrifugeAeOutputEnabled;

	// v1.8.0: AE 网络能量输入集成 — 离心机从 ME 网络提取 FE/AE 能量注入本地容器
	// v1.8.1：AE2 未加载时为 null（条件化注册）
	public final ModConfigSpec.BooleanValue mekCentrifugeAeEnergyInputEnabled;
	// v1.8.1：AppliedFlux 未加载时为 null（条件化注册）
	public final ModConfigSpec.BooleanValue mekCentrifugePreferAppliedFluxOverAeEnergy;

	private CentrifugeConfigSection(ModConfigSpec.Builder builder) {
		builder.comment("MEK离心机设置").push("mek_centrifuge");

		// ===== 基础参数 =====
		builder.comment("基础参数").push("basic");
		mekCentrifugeEnergyPerTick = builder
				.comment("每个处理槽每tick的能量消耗(FE)")
				.defineInRange("energyPerTick", 50, 1, 10000);

		mekCentrifugeProcessingTime = builder
				.comment("基础处理时间(tick)")
				.defineInRange("processingTime", 200, 1, 6000);

		mekCentrifugeFluidTankCapacity = builder
				.comment("流体输出罐基础容量(mB)", "工厂版按并行数倍增，默认256000（256桶）")
				.defineInRange("fluidTankCapacity", 256000, 1000, 1_000_000);

		mekCentrifugeFluidEjectRate = builder
				.comment("流体自动弹出速率(mB/tick)", "覆盖Mekanism默认1024，默认16384")
				.defineInRange("mekCentrifugeFluidEjectRate", 16384, 1, Integer.MAX_VALUE);

		mekCentrifugeCombBlockMultiplier = builder
				.comment("万象创世蜜脾块相对于蜜脾的产物倍率")
				.defineInRange("combBlockMultiplier", 4, 1, 16);
		builder.pop(); // basic

		// ===== 弹出策略 =====
		builder.comment("弹出策略").push("ejection");
		mekCentrifugeEjectDelay = builder
				.comment("输出槽自动弹出延迟(tick)", "原版Mekanism=10，推荐2(0.1秒)")
				.defineInRange("ejectDelay", 2, 0, 20);

		mekCentrifugeEjectDelayActive = builder
				.comment("输出槽仍有物品时的弹出延迟(tick)", "推荐1(0.05秒)，运行时不超过ejectDelay")
				.defineInRange("ejectDelayActive", 1, 0, 20);

		mekCentrifugeEjectSkipUnchanged = builder
				.comment("输出槽内容未变化时跳过 Ejector 输出，降低CPU开销")
				.define("ejectSkipUnchanged", true);

		mekCentrifugeEjectSkipTicks = builder
				.comment("输出未变化时连续跳过的tick数（0=不跳过）")
				.defineInRange("ejectSkipTicks", 1, 0, 20);

		mekCentrifugeEjectMaxSpeedMode = builder
				.comment("最大弹出速度模式",
						"跳过节流逻辑仅保留阻塞冷却，需目标容器空间充足")
				.define("ejectMaxSpeedMode", false);

		mekCentrifugeEjectMinInterval = builder
				.comment("输出持续变化时两次调用的最小间隔（0=关闭）")
				.defineInRange("ejectMinInterval", 0, 0, 20);

		mekCentrifugeEjectBusyThreshold = builder
				.comment("连续未减少输出总量多少次后进入长冷却", "默认5次")
				.defineInRange("ejectBusyThreshold", 5, 1, 50);

		mekCentrifugeEjectBusyCooldown = builder
				.comment("高负载长冷却跳过的tick数（0=关闭）", "默认40(2秒)")
				.defineInRange("ejectBusyCooldown", 40, 0, 600);

		mekCentrifugeEjectMaxPerTick = builder
				.comment("单tick最大outputItems调用次数（0=无限制）", "默认64，最大速度模式下跳过")
				.defineInRange("ejectMaxPerTick", 64, 0, 4096);

		// Task 14: Ejector 输出阻塞冷却 — 输出侧无法接收物品时降低尝试频率
		mekCentrifugeEjectBlockedThreshold = builder
				.comment("连续未弹出物品多少次后进入冷却", "默认3次")
				.defineInRange("mekCentrifugeEjectBlockedThreshold", 3, 1, 20);

		mekCentrifugeEjectBlockedCooldown = builder
				.comment("阻塞冷却跳过的tick数", "默认15(0.75秒)，0=关闭")
				.defineInRange("mekCentrifugeEjectBlockedCooldown", 15, 0, 200);
		builder.pop(); // ejection

		// ===== IO 限流 =====
		builder.comment("IO 限流").push("io_limit");
		// Task 13: AE2/管道拉取限流 — 默认0=无限制，不影响正常游戏
		mekCentrifugeMaxExtractPerTick = builder
				.comment("每tick外部通过管道/AE2拉取的最大物品数（0=无限制）")
				.defineInRange("mekCentrifugeMaxExtractPerTick", 0, 0, 1024);
		builder.pop(); // io_limit

		// ===== AE2 集成（条件化注册）=====
		// v1.8.1：AE2 未加载时不注册任何 AE2 配置项，配置界面不显示 ae2 子 section
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			builder.comment("AE2 集成").push("ae2");
			mekCentrifugeAeOutputEnabled = builder
					.comment("启用 AE2 直接输出",
							"推送输出槽物品到 AE2 网络",
							"未安装 AE2 时无效",
							"默认关闭")
					.define("aeOutputEnabled", false);

			mekCentrifugeAeEnergyInputEnabled = builder
					.comment("启用 AE 网络能量输入",
							"从 ME 网络提取 FE 注入本地能量容器",
							"支持 AppliedFlux 与 AE2 原生能量",
							"默认关闭")
					.define("aeEnergyInputEnabled", false);

			// v1.8.1：preferAppliedFluxOverAeEnergy 仅在 AppliedFlux 已加载时注册
			// AppliedFlux 未加载时该字段为 null，访问处通过 Ae2IntegrationLoader.isAe2Loaded() 守卫避免 NPE
			if (AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) {
				mekCentrifugePreferAppliedFluxOverAeEnergy = builder
						.comment("AE 网络能量优先级",
								"true（默认）：优先 AppliedFlux",
								"false：优先 AE2 原生能量")
						.define("preferAppliedFluxOverAeEnergy", true);
			} else {
				mekCentrifugePreferAppliedFluxOverAeEnergy = null;
			}
			builder.pop(); // ae2
		} else {
			// AE2 未加载：所有 AE2 相关字段为 null，访问处通过 Ae2IntegrationLoader.isAe2Loaded() 守卫
			mekCentrifugeAeOutputEnabled = null;
			mekCentrifugeAeEnergyInputEnabled = null;
			mekCentrifugePreferAppliedFluxOverAeEnergy = null;
		}

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
