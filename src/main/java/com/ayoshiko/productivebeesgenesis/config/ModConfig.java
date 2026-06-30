package com.ayoshiko.productivebeesgenesis.config;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件 — 万象创世蜜蜂属性覆盖
 * <p>
 * 允许整合包作者通过配置文件修改蜜蜂属性，无需编辑数据包JSON。
 * 客户端配置（CLIENT）仅影响本地渲染/显示；服务端配置（SERVER）按存档生效，
 * 世界加载时自动生效，无需执行 /reload。
 */
public final class ModConfig {

	/**
	 * 过滤模式枚举
	 * <p>
	 * NeoForge ConfigurationScreen 对枚类型会自动渲染循环切换按钮，
	 * 用户可以按顺序切换模式。
	 */
	public enum FilterMode {
		/** 不过滤，万象创世可转化为所有蜜蜂类型 */
		DISABLED,
		/** 黑名单，排除列表中的蜜蜂类型 */
		BLACKLIST,
		/** 白名单，仅允许列表中的蜜蜂类型 */
		WHITELIST
	}

	// ========== Validator 辅助常量 ==========
	/** 十六进制颜色格式：#RRGGBB */
	private static final String COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$";

	/** weatherTolerance 合法值集合 */
	private static final Set<String> WEATHER_TOLERANCE_VALUES = Set.of(
			"weather_tolerance.none", "weather_tolerance.rain", "weather_tolerance.any");
	/** temper 合法值集合 */
	private static final Set<String> TEMPER_VALUES = Set.of(
			"temper.passive", "temper.normal", "temper.hostile", "temper.aggressive");
	/** behavior 合法值集合 */
	private static final Set<String> BEHAVIOR_VALUES = Set.of(
			"behavior.diurnal", "behavior.nocturnal", "behavior.metaturnal");
	/** endurance 合法值集合 */
	private static final Set<String> ENDURANCE_VALUES = Set.of(
			"endurance.weak", "endurance.normal", "endurance.medium", "endurance.strong");
	/** productivity 合法值集合 */
	private static final Set<String> PRODUCTIVITY_VALUES = Set.of(
			"productivity.normal", "productivity.medium", "productivity.high", "productivity.very_high");

	/**
	 * 校验十六进制颜色格式（#RRGGBB）
	 */
	private static boolean validateColor(Object o) {
		return o instanceof String s && s.matches(COLOR_PATTERN);
	}

	/**
	 * 校验字符串是否为合法的 ResourceLocation（如 minecraft:bee）
	 */
	private static boolean validateResourceLocation(Object o) {
		return o instanceof String s && !s.isBlank() && ResourceLocation.tryParse(s) != null;
	}

	/**
	 * 校验群系规格字符串：支持 "minecraft:plains" 或 "#c:is_plains" 标签格式
	 */
	private static boolean validateBiomeSpec(Object o) {
		if (!(o instanceof String s) || s.isBlank()) {
			return false;
		}
		String parsed = s.startsWith("#") ? s.substring(1) : s;
		return ResourceLocation.tryParse(parsed) != null;
	}

	/**
	 * 校验 defineList 元素是否为合法 ResourceLocation 字符串
	 */
	private static boolean validateResourceLocationElement(Object o) {
		return o instanceof String s && !s.isBlank() && ResourceLocation.tryParse(s.trim()) != null;
	}

	public static final ModConfigSpec CLIENT_SPEC;
	public static final ClientConfig CLIENT;

	public static final ModConfigSpec COMMON_SPEC;
	public static final CommonConfig COMMON;

	public static final ModConfigSpec SERVER_SPEC;
	public static final ServerConfig SERVER;

	static {
		var clientPair = new ModConfigSpec.Builder().configure(ClientConfig::new);
		CLIENT = clientPair.getKey();
		CLIENT_SPEC = clientPair.getValue();

		var commonPair = new ModConfigSpec.Builder().configure(CommonConfig::new);
		COMMON = commonPair.getKey();
		COMMON_SPEC = commonPair.getValue();

		var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
		SERVER = serverPair.getKey();
		SERVER_SPEC = serverPair.getValue();
	}

	public static class ClientConfig {

		// ========== 彩虹特效（纯客户端渲染）==========
		public final ModConfigSpec.BooleanValue rainbowMode;
		public final ModConfigSpec.BooleanValue particleEffectEnabled;
		public final ModConfigSpec.BooleanValue glowEnabled;
		public final ModConfigSpec.IntValue particleCount;

		// ========== MEK离心机端口可视化（纯客户端渲染）==========
		public final ModConfigSpec.BooleanValue showPortColors;
		public final ModConfigSpec.IntValue portColorRenderRange;

		ClientConfig(ModConfigSpec.Builder builder) {
			// 彩虹特效配置（仅客户端可见的粒子/光晕开关）
			builder.push("rainbow_effects").comment("万象创世蜜蜂彩虹特效配置（仅客户端生效）");

			rainbowMode = builder
					.comment("启用彩虹模式（颜色会动态变化）")
					.define("rainbowMode", true);

			particleEffectEnabled = builder
					.comment("启用彩虹粒子特效")
					.define("particleEffectEnabled", true);

			particleCount = builder
					.comment("每个tick生成的粒子数量")
					.defineInRange("particleCount", 1, 1, 20);

			glowEnabled = builder
					.comment("启发光晕效果")
					.define("glowEnabled", true);

			builder.pop(); // rainbow_effects

			// MEK离心机端口可视化配置
			builder.comment("MEK离心机端口可视化设置").push("mek_port_visualization");

			showPortColors = builder
					.comment("手持Mekanism配置器或配置卡时显示MEK离心机的端口颜色")
					.define("showPortColors", true);

			portColorRenderRange = builder
					.comment("端口颜色渲染范围（方块距离）")
					.defineInRange("portColorRenderRange", 16, 4, 32);

			builder.pop(); // mek_port_visualization
		}
	}

	/**
	 * 通用配置 — 保留跨端同步且在世界加载前就需要读取的字段。
	 * <p>
	 * 当前所有服务端游戏逻辑字段均已迁移至 {@link ServerConfig}，
	 * 此处仅保留性能监控开关等需要在 common setup 阶段读取的字段。
	 */
	public static class CommonConfig {

		public final ModConfigSpec.BooleanValue enablePerformanceMonitor;

		CommonConfig(ModConfigSpec.Builder builder) {
			builder.comment("通用配置 — 跨端同步且无需按存档区分的参数").push("common");

			enablePerformanceMonitor = builder
					.comment("启用性能监控（兼容 Spark profiler，通过 JMX 暴露数据）")
					.define("enablePerformanceMonitor", false);

			builder.pop(); // common
		}
	}

	/**
	 * 服务端配置 — 存档级别配置
	 * <p>
	 * 随存档保存，不同存档可拥有不同配置。世界加载时自动生效，
	 * 无需执行 /reload。
	 */
	public static class ServerConfig {

		// ========== 万象创世过滤配置（存档级别）==========
		// 使用枚举类型，ConfigurationScreen自动渲染循环切换按钮
		public final ModConfigSpec.EnumValue<FilterMode> myriadCreationsFilterMode;
		public final ModConfigSpec.ConfigValue<List<? extends String>> myriadCreationsFilteredBeeTypes;

		// ========== 万象创世蜜蜂属性（服务端生效）==========
		public final ModConfigSpec.ConfigValue<String> primaryColor;
		public final ModConfigSpec.ConfigValue<String> secondaryColor;
		public final ModConfigSpec.ConfigValue<String> particleColor;
		public final ModConfigSpec.ConfigValue<String> glowColor;
		public final ModConfigSpec.ConfigValue<String> flowerItem;
		public final ModConfigSpec.ConfigValue<String> weatherTolerance;
		public final ModConfigSpec.ConfigValue<String> temper;
		public final ModConfigSpec.ConfigValue<String> behavior;
		public final ModConfigSpec.ConfigValue<String> endurance;
		public final ModConfigSpec.ConfigValue<String> productivity;
		public final ModConfigSpec.BooleanValue createComb;
		public final ModConfigSpec.DoubleValue size;
		public final ModConfigSpec.DoubleValue speed;
		public final ModConfigSpec.DoubleValue attack;
		public final ModConfigSpec.ConfigValue<String> breedingItem;
		public final ModConfigSpec.IntValue breedingItemCount;
		public final ModConfigSpec.BooleanValue selfbreed;
		public final ModConfigSpec.BooleanValue waterproof;
		public final ModConfigSpec.BooleanValue fireproof;

		// ========== 蜜蜂获得方式配置 ==========
		public final ModConfigSpec.BooleanValue fishingEnabled;
		public final ModConfigSpec.DoubleValue fishingChance;
		public final ModConfigSpec.ConfigValue<List<? extends String>> fishingBiomes;
		public final ModConfigSpec.BooleanValue breedingEnabled;
		public final ModConfigSpec.ConfigValue<String> breedingParent1;
		public final ModConfigSpec.ConfigValue<String> breedingParent2;
		public final ModConfigSpec.BooleanValue spawningEnabled;
		public final ModConfigSpec.ConfigValue<String> spawningNest;
		public final ModConfigSpec.ConfigValue<String> spawningBiomes;

		// ========== 蜜蜂转化与产出配置 ==========
		public final ModConfigSpec.BooleanValue conversionEnabled;
		public final ModConfigSpec.ConfigValue<String> conversionSource;
		public final ModConfigSpec.ConfigValue<String> conversionResult;
		public final ModConfigSpec.ConfigValue<String> conversionItem;
		public final ModConfigSpec.DoubleValue conversionChance;
		public final ModConfigSpec.BooleanValue produceEnabled;
		public final ModConfigSpec.ConfigValue<String> produceOutputItem;
		public final ModConfigSpec.IntValue produceOutputMin;
		public final ModConfigSpec.IntValue produceOutputMax;
		public final ModConfigSpec.DoubleValue produceOutputChance;
		public final ModConfigSpec.IntValue myriadProduceThrottlePerTick;

		// ========== 高级蜂箱性能优化配置 ==========
		public final ModConfigSpec.BooleanValue advancedBeehiveCacheIsSim;
		public final ModConfigSpec.BooleanValue advancedBeehiveCacheHasNectar;
		public final ModConfigSpec.IntValue advancedBeehiveSimulateCooldown;

		// ========== MEK离心机配置 ==========
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

		ServerConfig(ModConfigSpec.Builder builder) {
			builder.comment("万象创世蜜蜂过滤配置（存档级别）").push("myriad_creations_filter");

			myriadCreationsFilterMode = builder
					.comment("过滤模式", "DISABLED - 不过滤，万象创世可转化为所有蜜蜂类型", "BLACKLIST - 黑名单，排除列表中的蜜蜂类型", "WHITELIST - 白名单，仅允许列表中的蜜蜂类型")
					.defineEnum("filterMode", FilterMode.DISABLED);

			myriadCreationsFilteredBeeTypes = builder
					.comment("过滤的蜜蜂类型列表", "格式: 模组ID:蜜蜂类型，如 productivebees:iron", "黑名单模式下排除这些类型，白名单模式下仅允许这些类型")
					.defineList("filteredBeeTypes", List.of(), () -> "productivebees:iron", ModConfig::validateResourceLocationElement);

			builder.pop();

			builder.comment("万象创世蜜蜂属性覆盖配置（服务端生效）").push("bee_attributes");

			builder.push("colors").comment("颜色配置（写入蜜蜂数据并在客户端渲染）");
			primaryColor = builder
					.comment("主颜色（十六进制，如 #FFD700）")
					.define("primaryColor", "#FFFFFF", ModConfig::validateColor);
			secondaryColor = builder
					.comment("次要颜色")
					.define("secondaryColor", "#FFFFFF", ModConfig::validateColor);
			particleColor = builder
					.comment("粒子颜色")
					.define("particleColor", "#FFFFFF", ModConfig::validateColor);
			glowColor = builder
					.comment("光晕颜色（十六进制）")
					.define("glowColor", "#FFFFFF", ModConfig::validateColor);
			builder.pop(); // colors

			flowerItem = builder
					.comment("授粉物品ID")
					.define("flowerItem", "productivebees:honey_treat", ModConfig::validateResourceLocation);

			builder.push("pb_attributes").comment("Productive Bees 独有属性");
			weatherTolerance = builder
					.comment("天气耐受性", "可选值: weather_tolerance.none / weather_tolerance.rain / weather_tolerance.any")
					.define("weatherTolerance", "weather_tolerance.any",
							o -> o instanceof String s && WEATHER_TOLERANCE_VALUES.contains(s));
			temper = builder
					.comment("性格", "可选值: temper.passive / temper.normal / temper.hostile / temper.aggressive")
					.define("temper", "temper.passive",
							o -> o instanceof String s && TEMPER_VALUES.contains(s));
			behavior = builder
					.comment("行为", "可选值: behavior.diurnal (昼行) / behavior.nocturnal (夜行) / behavior.metaturnal (昼夜皆可)")
					.define("behavior", "behavior.metaturnal",
							o -> o instanceof String s && BEHAVIOR_VALUES.contains(s));
			endurance = builder
					.comment("耐力", "可选值: endurance.weak / endurance.normal / endurance.medium / endurance.strong")
					.define("endurance", "endurance.strong",
							o -> o instanceof String s && ENDURANCE_VALUES.contains(s));
			productivity = builder
					.comment("产量", "可选值: productivity.normal / productivity.medium / productivity.high / productivity.very_high")
					.define("productivity", "productivity.very_high",
							o -> o instanceof String s && PRODUCTIVITY_VALUES.contains(s));
			builder.pop();

			createComb = builder
					.comment("是否能产出蜜脾", "默认关闭：万象创世使用自定义蜜脾(productivebeesgenesis:myriadcreations_comb)，不自动生成PB的configurable_honeycomb")
					.define("createComb", false);

			size = builder
					.comment("蜜蜂大小")
					.defineInRange("size", 1.2D, 0.1D, 10.0D);

			speed = builder
					.comment("飞行速度")
					.defineInRange("speed", 0.6D, 0.01D, 10.0D);

			attack = builder
					.comment("攻击伤害")
					.defineInRange("attack", 20.0D, 0.0D, 100.0D);

			breedingItem = builder
					.comment("繁殖物品ID")
					.define("breedingItem", "productivebees:honey_treat", ModConfig::validateResourceLocation);

			breedingItemCount = builder
					.comment("繁殖所需物品数量")
					.defineInRange("breedingItemCount", 1, 1, 64);

			selfbreed = builder
					.comment("是否可种内繁殖")
					.define("selfbreed", true);

			waterproof = builder
					.comment("是否防水")
					.define("waterproof", true);

			fireproof = builder
					.comment("是否防火")
					.define("fireproof", true);

			builder.pop(); // bee_attributes

			builder.comment("蜜蜂获得方式配置").push("bee_acquisition");

			builder.push("fishing").comment("钓鱼获得万象创世蜜蜂");
			fishingEnabled = builder
					.comment("是否启用钓鱼获得万象创世蜜蜂")
					.define("enabled", false);
			fishingChance = builder
					.comment("钓鱼获得蜜蜂的概率（0.0~1.0）")
					.defineInRange("chance", 0.1D, 0.0D, 1.0D);
			fishingBiomes = builder
					.comment("可钓鱼获得蜜蜂的群系列表")
					.defineList("biomes", List.of(
							"minecraft:ocean",
							"minecraft:deep_ocean",
							"minecraft:cold_ocean",
							"minecraft:deep_cold_ocean",
							"minecraft:frozen_ocean",
							"minecraft:deep_frozen_ocean",
							"minecraft:warm_ocean",
							"minecraft:lukewarm_ocean",
							"minecraft:deep_lukewarm_ocean"
					), () -> "minecraft:plains", ModConfig::validateResourceLocationElement);
			builder.pop(); // fishing

			builder.push("breeding").comment("繁殖获得万象创世蜜蜂");
			breedingEnabled = builder
					.comment("是否启用繁殖获得万象创世蜜蜂")
					.define("enabled", true);
			breedingParent1 = builder
					.comment("亲代蜜蜂1（注册名，如 productivebees:myriadcreations）")
					.define("parent1", "productivebees:myriadcreations", ModConfig::validateResourceLocation);
			breedingParent2 = builder
					.comment("亲代蜜蜂2（注册名）")
					.define("parent2", "productivebees:myriadcreations", ModConfig::validateResourceLocation);
			builder.pop(); // breeding

			builder.push("spawning").comment("蜂巢生成万象创世蜜蜂");
			spawningEnabled = builder
					.comment("是否启用蜂巢自然生成万象创世蜜蜂")
					.define("enabled", false);
			spawningNest = builder
					.comment("生成蜜蜂的蜂巢方块（如 productivebees:stone_nest）")
					.define("nest", "productivebees:stone_nest", ModConfig::validateResourceLocation);
			spawningBiomes = builder
					.comment("生成蜜蜂的群系（标签或群系ID，如 #c:is_plains）")
					.define("biomes", "#c:is_plains", ModConfig::validateBiomeSpec);
			builder.pop(); // spawning

			builder.pop(); // bee_acquisition

			builder.comment("蜜蜂转化配方配置（用其他物品转化获得万象创世）").push("bee_conversion");
			conversionEnabled = builder
					.comment("是否启用万象创世的物品转化配方")
					.define("enabled", true);
			conversionSource = builder
					.comment("源蜜蜂类型（注册名，如 minecraft:bee）")
					.define("source", "minecraft:bee", ModConfig::validateResourceLocation);
			conversionResult = builder
					.comment("转化目标蜜蜂（注册名，如 productivebees:myriadcreations）")
					.define("result", "productivebees:myriadcreations", ModConfig::validateResourceLocation);
			conversionItem = builder
					.comment("转化所需物品ID（如 minecraft:stick）")
					.define("item", "minecraft:stick", ModConfig::validateResourceLocation);
			conversionChance = builder
					.comment("转化概率（0.0~1.0）")
					.defineInRange("chance", 1.0D, 0.0D, 1.0D);
			builder.pop(); // bee_conversion

			builder.comment("蜜蜂产出配方配置（万象创世蜜脾产出参数）").push("bee_produce");
			produceEnabled = builder
					.comment("是否启用万象创世的蜜脾产出")
					.define("enabled", true);
			produceOutputItem = builder
					.comment("产出物品ID（如 productivebees:configurable_honeycomb）", "使用 configurable_honeycomb 时会自动附加 bee_type 组件")
					.define("outputItem", "productivebees:configurable_honeycomb", ModConfig::validateResourceLocation);
			produceOutputMin = builder
					.comment("最小产出数量")
					.defineInRange("outputMin", 1, 1, 64);
			produceOutputMax = builder
					.comment("最大产出数量")
					.defineInRange("outputMax", 1, 1, 64);
			produceOutputChance = builder
					.comment("产出概率（0.0~1.0）")
					.defineInRange("outputChance", 1.0D, 0.0D, 1.0D);

			myriadProduceThrottlePerTick = builder
					.comment("每游戏刻每只万象创世蜜蜂的最大产物事件数（0=无限制）",
							"在高倍加速/ME接口高频拉取场景下限制调用次数，降低CPU负载")
					.defineInRange("myriadProduceThrottlePerTick", 0, 0, 20);
			builder.pop(); // bee_produce

			builder.comment("高级蜂箱性能优化（缓解大量模拟蜂箱导致的CPU压力）").push("advanced_beehive");

			advancedBeehiveCacheIsSim = builder
					.comment("缓存每tick的 isSim() 结果",
							"高级蜂箱 tickBees() 会对每只蜜蜂调用 isSim()，开启后可避免同一 tick 内重复读取升级栏/配置")
					.define("cacheIsSim", true);

			advancedBeehiveCacheHasNectar = builder
					.comment("缓存 BeeData.hasNectar() 结果",
							"hasNectar() 每次都会读取蜜蜂 NBT，开启后在同一只蜜蜂数据未重建前复用上一次结果")
					.define("cacheHasNectar", true);

			advancedBeehiveSimulateCooldown = builder
					.comment("模拟蜜蜂中农夫/囤积/收集行为的查询冷却(tick)",
							"simulateBee() 每 tick 都会扫描附近作物或可拾取物品，设置 1-5 可显著降低高倍加速下的 CPU 开销",
							"0 = 不限制（原版行为）")
					.defineInRange("simulateCooldown", 0, 0, 20);

			builder.pop(); // advanced_beehive

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
	}
}
