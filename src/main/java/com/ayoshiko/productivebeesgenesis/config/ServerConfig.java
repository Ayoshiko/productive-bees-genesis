package com.ayoshiko.productivebeesgenesis.config;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端配置 — 存档级别配置
 * <p>
 * 从 {@link ModConfig} 抽取的独立配置类（Task 21），遵循单一职责原则（SRP）。
 * 随存档保存，不同存档可拥有不同配置。世界加载时自动生效，无需执行 /reload。
 * 实例由 {@link ModConfig#SERVER} 聚合持有，外部访问路径 {@code ModConfig.SERVER.xxx} 保持不变。
 * <p>
 * 校验逻辑（颜色、ResourceLocation、枚举值集合）复用 {@link ModConfig} 中的 package-private
 * validator 方法与常量，保证配置文件 validator 与网络包服务端校验逻辑单一来源（SRP）。
 * <p>
 * <b>职责拆分（Task 19）</b>：原文件 403 行，已将两类大块配置抽取为独立配置段，
 * 本类作为聚合点持有配置段实例，并为向后兼容保留 public final 委托字段：
 * <ul>
 *   <li>{@link BeeAttributeConfigSection} — 万象创世蜜蜂属性覆盖配置（bee_attributes.*）</li>
 *   <li>{@link CentrifugeConfigSection} — MEK 离心机配置（mek_centrifuge.*）</li>
 * </ul>
 * 配置键名、层级、注册顺序与抽取前完全一致，纯重构无行为变更。
 */
public final class ServerConfig {

	// ========== 子配置段实例（Task 19 抽取）==========
	private final BeeAttributeConfigSection beeAttributes;
	private final CentrifugeConfigSection centrifuge;

	/** 获取万象创世蜜蜂属性配置段（供新代码使用，旧代码可继续通过委托字段访问） */
	public BeeAttributeConfigSection beeAttributes() { return beeAttributes; }
	/** 获取 MEK 离心机配置段（供新代码使用，旧代码可继续通过委托字段访问） */
	public CentrifugeConfigSection centrifuge() { return centrifuge; }

	// ========== 开发者模式（服务端控制）==========
	public final ModConfigSpec.BooleanValue devMode;

	// ========== 万象创世蜜蜂总开关（存档级别）==========
	public final ModConfigSpec.BooleanValue myriadCreationsEnabled;

	// ========== 万象创世过滤配置（存档级别）==========
	// 使用枚举类型，ConfigurationScreen自动渲染循环切换按钮
	public final ModConfigSpec.EnumValue<ModConfig.FilterMode> myriadCreationsFilterMode;
	public final ModConfigSpec.ConfigValue<List<? extends String>> myriadCreationsFilteredBeeTypes;

	// ========== 万象创世蜜蜂属性（服务端生效）—— 向后兼容委托字段 ==========
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
	// 注意：isSim() / hasNectar() 缓存是默认开启且不可关闭的内部优化，
	// 不在配置界面暴露，避免玩家误操作导致性能回退。
	public final ModConfigSpec.IntValue advancedBeehiveSimulateCooldown;

	// ========== MEK离心机配置 —— 向后兼容委托字段 ==========
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
		// 开发者模式：模组开发者用来调试正常功能不会用到的物品和调试配置日志等，用户无需开启此设置
		devMode = builder
				.comment("开发者模式", "模组开发者用来调试模组正常功能不会用到的物品和调试配置日志等，用户无需开启此设置。")
				.define("devMode", false);

		// 万象创世蜜蜂总开关
		myriadCreationsEnabled = builder
				.comment("启用万象创世蜜蜂", "设置为false可完全禁用万象创世蜜蜂及其相关功能，仅保留MEK离心机功能。", "适合只想使用MEK离心机而不需要万象创世蜜蜂的玩家。")
				.define("myriadCreationsEnabled", true);

		builder.comment("万象创世蜜蜂过滤配置（存档级别）").push("myriad_creations_filter");

		myriadCreationsFilterMode = builder
				.comment("过滤模式", "DISABLED - 不过滤，万象创世可转化为所有蜜蜂类型", "BLACKLIST - 黑名单，排除列表中的蜜蜂类型", "WHITELIST - 白名单，仅允许列表中的蜜蜂类型")
				.defineEnum("filterMode", ModConfig.FilterMode.DISABLED);

		myriadCreationsFilteredBeeTypes = builder
				.comment("过滤的蜜蜂类型列表", "格式: 模组ID:蜜蜂类型，如 productivebees:iron", "黑名单模式下排除这些类型，白名单模式下仅允许这些类型")
				.defineList("filteredBeeTypes", List.of(), () -> "productivebees:iron", ModConfig::validateResourceLocationElement);

		builder.pop();

		// 万象创世蜜蜂属性配置（抽取至 BeeAttributeConfigSection）
		this.beeAttributes = BeeAttributeConfigSection.create(builder);
		// 向后兼容委托字段赋值（指向同一 ConfigValue 实例，零开销）
		this.primaryColor = this.beeAttributes.primaryColor;
		this.secondaryColor = this.beeAttributes.secondaryColor;
		this.particleColor = this.beeAttributes.particleColor;
		this.glowColor = this.beeAttributes.glowColor;
		this.flowerItem = this.beeAttributes.flowerItem;
		this.weatherTolerance = this.beeAttributes.weatherTolerance;
		this.temper = this.beeAttributes.temper;
		this.behavior = this.beeAttributes.behavior;
		this.endurance = this.beeAttributes.endurance;
		this.productivity = this.beeAttributes.productivity;
		this.createComb = this.beeAttributes.createComb;
		this.size = this.beeAttributes.size;
		this.speed = this.beeAttributes.speed;
		this.attack = this.beeAttributes.attack;
		this.breedingItem = this.beeAttributes.breedingItem;
		this.breedingItemCount = this.beeAttributes.breedingItemCount;
		this.selfbreed = this.beeAttributes.selfbreed;
		this.waterproof = this.beeAttributes.waterproof;
		this.fireproof = this.beeAttributes.fireproof;

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

		advancedBeehiveSimulateCooldown = builder
				.comment("模拟蜜蜂中农夫/囤积/收集行为的查询冷却(tick)",
						"simulateBee() 每 tick 都会扫描附近作物或可拾取物品，设置 1-5 可显著降低高倍加速下的 CPU 开销",
						"0 = 不限制（原版行为）")
				.defineInRange("simulateCooldown", 0, 0, 20);

		builder.pop(); // advanced_beehive

		// MEK离心机配置（抽取至 CentrifugeConfigSection）
		this.centrifuge = CentrifugeConfigSection.create(builder);
		// 向后兼容委托字段赋值（指向同一 ConfigValue 实例，零开销）
		this.mekCentrifugeEnergyPerTick = this.centrifuge.mekCentrifugeEnergyPerTick;
		this.mekCentrifugeProcessingTime = this.centrifuge.mekCentrifugeProcessingTime;
		this.mekCentrifugeEjectDelay = this.centrifuge.mekCentrifugeEjectDelay;
		this.mekCentrifugeEjectDelayActive = this.centrifuge.mekCentrifugeEjectDelayActive;
		this.mekCentrifugeFluidTankCapacity = this.centrifuge.mekCentrifugeFluidTankCapacity;
		this.mekCentrifugeFluidEjectRate = this.centrifuge.mekCentrifugeFluidEjectRate;
		this.mekCentrifugeCombBlockMultiplier = this.centrifuge.mekCentrifugeCombBlockMultiplier;
		this.mekCentrifugeMaxExtractPerTick = this.centrifuge.mekCentrifugeMaxExtractPerTick;
		this.mekCentrifugeEjectBlockedThreshold = this.centrifuge.mekCentrifugeEjectBlockedThreshold;
		this.mekCentrifugeEjectBlockedCooldown = this.centrifuge.mekCentrifugeEjectBlockedCooldown;
		this.mekCentrifugeEjectSkipUnchanged = this.centrifuge.mekCentrifugeEjectSkipUnchanged;
		this.mekCentrifugeEjectSkipTicks = this.centrifuge.mekCentrifugeEjectSkipTicks;
		this.mekCentrifugeEjectMaxSpeedMode = this.centrifuge.mekCentrifugeEjectMaxSpeedMode;
		this.mekCentrifugeEjectMinInterval = this.centrifuge.mekCentrifugeEjectMinInterval;
		this.mekCentrifugeEjectBusyThreshold = this.centrifuge.mekCentrifugeEjectBusyThreshold;
		this.mekCentrifugeEjectBusyCooldown = this.centrifuge.mekCentrifugeEjectBusyCooldown;
		this.mekCentrifugeEjectMaxPerTick = this.centrifuge.mekCentrifugeEjectMaxPerTick;
	}
}