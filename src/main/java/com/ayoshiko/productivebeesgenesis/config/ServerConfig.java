package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
	 * 服务端配置 — 存档级别配置
	 * <p>
	 * 从 {@link ModConfig} 抽取的独立配置类(Task 21),遵循单一职责原则(SRP)。
	 * 随存档保存,不同存档可拥有不同配置。世界加载时自动生效,无需执行 /reload。
	 * 实例由 {@link ModConfig#SERVER} 聚合持有,外部访问路径 {@code ModConfig.SERVER.xxx} 保持不变。
	 * <p>
	 * 校验逻辑(颜色、ResourceLocation、枚举值集合)复用 {@link ModConfig} 中的 package-private
	 * validator 方法与常量,保证配置文件 validator 与网络包服务端校验逻辑单一来源(SRP)。
	 * <p>
	 * <b>职责拆分(Task 12 / Task 19)</b>:本类作为聚合入口持有 {@link ConfigSectionRegistry},
	 * 子配置段创建/查找逻辑委托至注册表,本类仅保留 Builder 入口与基础配置定义。
	 * 为向后兼容保留 public final 委托字段(指向同一 ConfigValue 实例,零开销):
	 * <ul>
	 *   <li>{@link BeeAttributeConfigSection} — 万象创世蜜蜂属性覆盖配置(bee_attributes.*)</li>
	 *   <li>{@link CentrifugeConfigSection} — MEK 离心机配置(mek_centrifuge.*)</li>
	 *   <li>{@link ApiaryConfigSection} — MEK 通用机械蜂箱配置(mek_apiary.*,Task 18 新增)</li>
	 * </ul>
	 * 配置键名、层级、注册顺序与抽取前完全一致,纯重构无行为变更。
	 * <p>
	 * <b>v2.0.0 条件化注册与 null 守卫</b>:AE2/EM 相关委托字段在对应附属未加载时为 null。
	 * 访问处需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 或
	 * {@code MekCompatHooks.isEvolvedMekanismLoaded()} 守卫避免 NPE。
	 * <p>
	 * <b>v2.0.0 子段抽取</b>:离心机堆叠倍率/流体罐倍率已抽取至
	 * {@link StackMultiplierConfigSection} / {@link FluidTankMultiplierConfigSection}。
	 * 外部访问需通过 {@code ModConfig.SERVER.centrifuge().stackMultiplier.xxx.get()}
	 * 或 {@code ModConfig.SERVER.centrifuge().fluidTankMultiplier.xxx.get()}。
	 */
public final class ServerConfig {

	// ========== 配置段注册表(Task 12 抽取)==========
	private final ConfigSectionRegistry sections;

	/** 获取万象创世蜜蜂属性配置段(供新代码使用,旧代码可继续通过委托字段访问) */
	public BeeAttributeConfigSection beeAttributes() { return sections.beeAttributes(); }
	/** 获取 MEK 离心机配置段(供新代码使用,旧代码可继续通过委托字段访问) */
	public CentrifugeConfigSection centrifuge() { return sections.centrifuge(); }
	/** 获取 MEK 通用机械蜂箱配置段(Task 18 新增) */
	public ApiaryConfigSection apiary() { return sections.apiary(); }

	// ========== 万象创世蜜蜂总开关(存档级别)==========
	public final ModConfigSpec.BooleanValue myriadCreationsEnabled;

	// ========== 万象创世过滤配置(存档级别)==========
	// 使用枚举类型,ConfigurationScreen自动渲染循环切换按钮
	public final ModConfigSpec.EnumValue<ModConfig.FilterMode> myriadCreationsFilterMode;
	public final ModConfigSpec.ConfigValue<List<? extends String>> myriadCreationsFilteredBeeTypes;

	// ========== 万象创世蜜蜂属性(服务端生效)—— 向后兼容委托字段 ==========
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
	public final ModConfigSpec.BooleanValue apiaryItemConversionEnabled;
	public final ModConfigSpec.BooleanValue apiaryBlockConversionEnabled;
	public final ModConfigSpec.BooleanValue produceEnabled;
	public final ModConfigSpec.ConfigValue<String> produceOutputItem;
	public final ModConfigSpec.IntValue produceOutputMin;
	public final ModConfigSpec.IntValue produceOutputMax;
	public final ModConfigSpec.DoubleValue produceOutputChance;
	public final ModConfigSpec.IntValue myriadProduceThrottlePerTick;

	// ========== 高级蜂箱性能优化配置 ==========
	// 注意:isSim() / hasNectar() 缓存是默认开启且不可关闭的内部优化,
	// 不在配置界面暴露,避免玩家误操作导致性能回退。
	public final ModConfigSpec.IntValue advancedBeehiveSimulateCooldown;
	// 高级蜂箱 NBT 保存间隔(tick),降低高倍加速下的 CompoundTag 序列化开销
	public final ModConfigSpec.IntValue advancedBeehiveSaveInterval;
	public final ModConfigSpec.IntValue maxBatchTicksPerTick;

	// ========== MEK离心机配置 —— 向后兼容委托字段(基础参数,堆叠/流体倍率已迁移至子段)==========
	public final ModConfigSpec.IntValue mekCentrifugeEnergyPerTick;
	/** 能量存储容量(FE),工厂版按并行数倍增。Task 3 从硬编码 20000L 改为 config */
	public final ModConfigSpec.LongValue mekCentrifugeEnergyStorage;
	public final ModConfigSpec.IntValue mekCentrifugeProcessingTime;
	public final ModConfigSpec.IntValue mekCentrifugeEjectDelay;
	public final ModConfigSpec.IntValue mekCentrifugeEjectDelayActive;
	public final ModConfigSpec.IntValue mekCentrifugeFluidTankCapacity;
	/** 多流体槽模式开关:false=单槽共享(默认),true=按流体类型动态分配独立槽位 */
	public final ModConfigSpec.BooleanValue mekCentrifugeMultiFluidTank;
	/**
	 * v2.0.9: 每种流体类型最大占用槽位数(委托自 CentrifugeConfigSection)
	 * <br/>
	 * 0=自动计算 maxTanks/2,>0=手动指定配额,防止高产出流体占用所有槽位
	 */
	public final ModConfigSpec.IntValue mekCentrifugeMaxTanksPerFluid;
	/**
	 * Task 6: 流体弹出速率(mB/tick),默认 256,范围 1-10240
	 * <br/>
	 * 委托自 CentrifugeConfigSection,由 AbstractMekCentrifugeFactory 构造函数注入 Ejector。
	 * 100-tick CAS 缓存读取避免 TPS 退化(参考 MultiFluidSideConfigHandler.getCachedEjectRate)。
	 */
	public final ModConfigSpec.IntValue mekCentrifugeFluidEjectRate;
	public final ModConfigSpec.IntValue mekCentrifugeCombBlockMultiplier;
	// Task 13: AE2/管道拉取限流(防止 ME 接口过载拉取触发全量排序扫描)
	public final ModConfigSpec.IntValue mekCentrifugeMaxExtractPerTick;
	// Task 14: Ejector 输出阻塞冷却参数(解决输出侧阻塞时 outputItems 高频尝试导致 TPS 暴跌)
	public final ModConfigSpec.IntValue mekCentrifugeEjectBlockedThreshold;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBlockedCooldown;
	// Task 16: 输出槽内容未变化时跳过 outputItems,降低高倍加速下的 CPU 开销
	public final ModConfigSpec.BooleanValue mekCentrifugeEjectSkipUnchanged;
	public final ModConfigSpec.IntValue mekCentrifugeEjectSkipTicks;
	// Task 24: 最大弹出速度模式:关闭 Ejector 节流以最大化物品弹出速度
	public final ModConfigSpec.BooleanValue mekCentrifugeEjectMaxSpeedMode;
	// Task 23: Ejector 持续高负载下降频:最小调用间隔与长冷却
	public final ModConfigSpec.IntValue mekCentrifugeEjectMinInterval;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBusyThreshold;
	public final ModConfigSpec.IntValue mekCentrifugeEjectBusyCooldown;
	// Step 5: 单 tick 最大弹出次数上限(0=无限制),限制 256× 加速下高频 outputItems 调用
	public final ModConfigSpec.IntValue mekCentrifugeEjectMaxPerTick;
	// Task 2: 单 tick 最大 PB 配方操作数上限(0=无限制),防止 256× 加速下 CPU 过载
	public final ModConfigSpec.IntValue mekCentrifugeMaxOpsPerTick;
	// AE2 直接输出集成开关 — AE2 未加载时为 null(条件化注册)
	public final ModConfigSpec.BooleanValue mekCentrifugeAeOutputEnabled;
	// AE2 流体输出集成开关(独立于物品输出)— AE2 未加载时为 null
	public final ModConfigSpec.BooleanValue mekCentrifugeAeFluidOutputEnabled;
	// v2.0.0: AE 网络能量输入集成 — AE2/AppliedFlux 未加载时为 null(条件化注册)
	public final ModConfigSpec.BooleanValue mekCentrifugeAeEnergyInputEnabled;
	public final ModConfigSpec.BooleanValue mekCentrifugePreferAppliedFluxOverAeEnergy;
	// AE2 输入拉取集成 — AE2 未加载时为 null(条件化注册)
	public final ModConfigSpec.BooleanValue mekCentrifugeAeInputEnabled;
	public final ModConfigSpec.IntValue mekCentrifugeAeInputRatePerTick;
	public final ModConfigSpec.IntValue mekCentrifugeAeInputIntervalTicks;
	public final ModConfigSpec.IntValue mekCentrifugeAeInputMinPages;
	public final ModConfigSpec.IntValue mekCentrifugePbUpgradeProductivityMaxCount;
	public final ModConfigSpec.IntValue mekCentrifugePbUpgradeTimeMaxCount;
	/** 稳定性升级最大安装数量(委托自 CentrifugeConfigSection,仅离心机生效,对齐 PB 原版上限 7) */
	public final ModConfigSpec.IntValue mekCentrifugePbUpgradeStabilityMaxCount;
	/** 通用机械:扩展 堆叠升级最大数量(委托自 CentrifugeConfigSection,由 ExtraUpgradeStackMixin 读取) */
	public final ModConfigSpec.IntValue mekCentrifugeMaxStackUpgrades;
	/** 离心机电力熔炼炉配方兼容总开关（委托自 CentrifugeConfigSection） */
	public final ModConfigSpec.BooleanValue mekCentrifugeSmeltingCompatEnabled;

	// ========== MEK通用机械蜂箱配置 —— 向后兼容委托字段 ==========
	public final ModConfigSpec.LongValue apiaryEnergyPerTick;
	public final ModConfigSpec.IntValue apiaryProcessingTime;
	public final ModConfigSpec.IntValue apiaryFluidTankCapacity;
	public final ModConfigSpec.IntValue apiaryEjectDelay;
	public final ModConfigSpec.IntValue apiaryEjectDelayActive;
	public final ModConfigSpec.BooleanValue apiaryEjectMaxSpeedMode;
	public final ModConfigSpec.IntValue apiaryEjectMaxPerTick;
	public final ModConfigSpec.IntValue apiaryEjectBlockedThreshold;
	public final ModConfigSpec.IntValue apiaryEjectBlockedCooldown;
	public final ModConfigSpec.IntValue apiaryStackBasic;
	public final ModConfigSpec.IntValue apiaryStackAdvanced;
	public final ModConfigSpec.IntValue apiaryStackElite;
	public final ModConfigSpec.IntValue apiaryStackUltimate;
	public final ModConfigSpec.IntValue apiaryStackMeAbsolute;
	public final ModConfigSpec.IntValue apiaryStackMeSupreme;
	public final ModConfigSpec.IntValue apiaryStackMeCosmic;
	public final ModConfigSpec.IntValue apiaryStackMeInfinite;
	// EM 工厂蜂箱堆叠倍率 — EM 未加载时为 null(条件化注册)
	public final ModConfigSpec.IntValue apiaryStackEmOverclocked;
	public final ModConfigSpec.IntValue apiaryStackEmQuantum;
	public final ModConfigSpec.IntValue apiaryStackEmDense;
	public final ModConfigSpec.IntValue apiaryStackEmMultiversal;
	public final ModConfigSpec.IntValue apiaryStackEmCreative;
	public final ModConfigSpec.IntValue apiaryStackEmeAbsoluteOverclocked;
	public final ModConfigSpec.IntValue apiaryStackEmeSupremeQuantum;
	public final ModConfigSpec.IntValue apiaryStackEmeCosmicDense;
	public final ModConfigSpec.IntValue apiaryStackEmeInfiniteMultiversal;
	// AE2 集成 — AE2 未加载时为 null(条件化注册)
	public final ModConfigSpec.BooleanValue apiaryAeOutputEnabled;
	public final ModConfigSpec.BooleanValue apiaryAeFluidOutputEnabled;
	public final ModConfigSpec.BooleanValue apiaryAeEnergyInputEnabled;
	// AE 网络能量优先级 — AppliedFlux 未加载时为 null(条件化注册)
	public final ModConfigSpec.BooleanValue apiaryPreferAppliedFluxOverAeEnergy;
	// PB升级上限
	public final ModConfigSpec.IntValue apiaryPbUpgradeProductivityMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeTimeMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeGeneSamplerMaxCount;
	public final ModConfigSpec.IntValue apiaryPbUpgradeBlockMaxCount;

	ServerConfig(ModConfigSpec.Builder builder) {
		this.sections = new ConfigSectionRegistry();

		// 万象创世蜜蜂总开关
		myriadCreationsEnabled = builder
				.comment("启用万象创世蜜蜂", "false时禁用蜜蜂相关功能，仅保留通用机械资源蜜蜂机器")
				.define("myriadCreationsEnabled", true);

		builder.comment("万象创世蜜蜂过滤配置（存档级别）").push("myriad_creations_filter");

		myriadCreationsFilterMode = builder
				.comment("过滤模式", "DISABLED/BLOCKLIST/WHITELIST")
				.defineEnum("filterMode", ModConfig.FilterMode.DISABLED);

		myriadCreationsFilteredBeeTypes = builder
				.comment("过滤的蜜蜂类型列表", "格式: modID:beeType")
				.defineList("filteredBeeTypes", List.of(), () -> "productivebees:iron", ModConfig::validateResourceLocationElement);

		builder.pop();

		// 万象创世蜜蜂属性配置(抽取至 BeeAttributeConfigSection,Task 12 委托至 ConfigSectionRegistry)
		BeeAttributeConfigSection beeAttributes = this.sections.registerBeeAttributes(builder);
		// 向后兼容委托字段赋值(指向同一 ConfigValue 实例,零开销)
		this.primaryColor = beeAttributes.primaryColor;
		this.secondaryColor = beeAttributes.secondaryColor;
		this.particleColor = beeAttributes.particleColor;
		this.glowColor = beeAttributes.glowColor;
		this.flowerItem = beeAttributes.flowerItem;
		this.weatherTolerance = beeAttributes.weatherTolerance;
		this.temper = beeAttributes.temper;
		this.behavior = beeAttributes.behavior;
		this.endurance = beeAttributes.endurance;
		this.productivity = beeAttributes.productivity;
		this.createComb = beeAttributes.createComb;
		this.size = beeAttributes.size;
		this.speed = beeAttributes.speed;
		this.attack = beeAttributes.attack;
		this.breedingItem = beeAttributes.breedingItem;
		this.breedingItemCount = beeAttributes.breedingItemCount;
		this.selfbreed = beeAttributes.selfbreed;
		this.waterproof = beeAttributes.waterproof;
		this.fireproof = beeAttributes.fireproof;

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
		apiaryItemConversionEnabled = builder
				.comment("是否允许机械蜂箱（蜂箱工厂）通过饲养板进行物品转化（PB item_conversion 配方，如烈焰蜜蜂 + 黑曜石蜜蜂刷怪蛋 → 无限蜜蜂刷怪蛋）")
				.define("apiaryItemConversionEnabled", true);
		apiaryBlockConversionEnabled = builder
				.comment("是否允许机械蜂箱（蜂箱工厂）通过饲养板中的方块物品进行方块转化（PB block_conversion 配方）")
				.define("apiaryBlockConversionEnabled", true);
		builder.pop(); // bee_conversion

		builder.comment("蜜蜂产出配方配置（万象创世蜜脾产出参数）").push("bee_produce");
		produceEnabled = builder
				.comment("是否启用万象创世的蜜脾产出")
				.define("enabled", true);
		produceOutputItem = builder
				.comment("产出物品ID")
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
				.comment("每tick每只蜜蜂最大产物事件数", "0=无限制，高倍加速时降低CPU")
				.defineInRange("myriadProduceThrottlePerTick", 0, 0, 20);
		builder.pop(); // bee_produce

		builder.comment("高级蜂箱性能优化（缓解大量模拟蜂箱导致的CPU压力）").push("advanced_beehive");

		advancedBeehiveSimulateCooldown = builder
				.comment("模拟行为查询冷却(tick)", "0=原版，1-5降低高倍加速CPU开销")
				.defineInRange("simulateCooldown", 0, 0, 20);

		advancedBeehiveSaveInterval = builder
				.comment("NBT保存间隔(tick)", "默认20，值越大性能越好但宕机风险增加")
				.defineInRange("saveInterval", 20, 1, 200);

		maxBatchTicksPerTick = builder
				.comment("批量收获每真实tick虚拟tick预算", "上限值，实际预算按服务器TPS自适应降级（50ms健康=满额，100ms=10%）",
						"无批次限制加速器（如1024x时间杖）下防止MSPT尖峰，余量挂账后续tick消化")
				.defineInRange("maxBatchTicksPerTick", 256, 1, 1024);

		builder.pop(); // advanced_beehive

		// MEK离心机配置(抽取至 CentrifugeConfigSection,Task 12 委托至 ConfigSectionRegistry)
		CentrifugeConfigSection centrifuge = this.sections.registerCentrifuge(builder);
		// 向后兼容委托字段赋值(指向同一 ConfigValue 实例,零开销)
		this.mekCentrifugeEnergyPerTick = centrifuge.mekCentrifugeEnergyPerTick;
		this.mekCentrifugeEnergyStorage = centrifuge.mekCentrifugeEnergyStorage;
		this.mekCentrifugeProcessingTime = centrifuge.mekCentrifugeProcessingTime;
		this.mekCentrifugeEjectDelay = centrifuge.mekCentrifugeEjectDelay;
		this.mekCentrifugeEjectDelayActive = centrifuge.mekCentrifugeEjectDelayActive;
		this.mekCentrifugeFluidTankCapacity = centrifuge.mekCentrifugeFluidTankCapacity;
		this.mekCentrifugeMultiFluidTank = centrifuge.mekCentrifugeMultiFluidTank;
		// v2.0.9: 每种流体类型最大占用槽位数(配额机制)
		this.mekCentrifugeMaxTanksPerFluid = centrifuge.mekCentrifugeMaxTanksPerFluid;
		// Task 3: 移除 mekCentrifugeMaxFluidTanks 委托字段(maxTanks 直接使用 tier.processes)
		this.mekCentrifugeFluidEjectRate = centrifuge.mekCentrifugeFluidEjectRate;
		this.mekCentrifugeCombBlockMultiplier = centrifuge.mekCentrifugeCombBlockMultiplier;
		this.mekCentrifugeMaxExtractPerTick = centrifuge.mekCentrifugeMaxExtractPerTick;
		this.mekCentrifugeEjectBlockedThreshold = centrifuge.mekCentrifugeEjectBlockedThreshold;
		this.mekCentrifugeEjectBlockedCooldown = centrifuge.mekCentrifugeEjectBlockedCooldown;
		this.mekCentrifugeEjectSkipUnchanged = centrifuge.mekCentrifugeEjectSkipUnchanged;
		this.mekCentrifugeEjectSkipTicks = centrifuge.mekCentrifugeEjectSkipTicks;
		this.mekCentrifugeEjectMaxSpeedMode = centrifuge.mekCentrifugeEjectMaxSpeedMode;
		this.mekCentrifugeEjectMinInterval = centrifuge.mekCentrifugeEjectMinInterval;
		this.mekCentrifugeEjectBusyThreshold = centrifuge.mekCentrifugeEjectBusyThreshold;
		this.mekCentrifugeEjectBusyCooldown = centrifuge.mekCentrifugeEjectBusyCooldown;
		this.mekCentrifugeEjectMaxPerTick = centrifuge.mekCentrifugeEjectMaxPerTick;
		this.mekCentrifugeMaxOpsPerTick = centrifuge.mekCentrifugeMaxOpsPerTick;
		// 堆叠倍率/流体罐倍率已迁移至子段,外部访问通过 centrifuge().stackMultiplier.xxx / centrifuge().fluidTankMultiplier.xxx
		this.mekCentrifugeAeOutputEnabled = centrifuge.mekCentrifugeAeOutputEnabled;
		this.mekCentrifugeAeFluidOutputEnabled = centrifuge.mekCentrifugeAeFluidOutputEnabled;
		// v2.0.0: AE 网络能量输入集成 — 向后兼容委托字段赋值
		this.mekCentrifugeAeEnergyInputEnabled = centrifuge.mekCentrifugeAeEnergyInputEnabled;
		this.mekCentrifugePreferAppliedFluxOverAeEnergy = centrifuge.mekCentrifugePreferAppliedFluxOverAeEnergy;
		// AE2 输入拉取集成 — 向后兼容委托字段赋值(指向同一 ConfigValue 实例,零开销)
		this.mekCentrifugeAeInputEnabled = centrifuge.mekCentrifugeAeInputEnabled;
		this.mekCentrifugeAeInputRatePerTick = centrifuge.mekCentrifugeAeInputRatePerTick;
		this.mekCentrifugeAeInputIntervalTicks = centrifuge.mekCentrifugeAeInputIntervalTicks;
		this.mekCentrifugeAeInputMinPages = centrifuge.mekCentrifugeAeInputMinPages;
		// PB升级上限委托字段赋值(指向同一 ConfigValue 实例,零开销)
		this.mekCentrifugePbUpgradeProductivityMaxCount = centrifuge.mekCentrifugePbUpgradeProductivityMaxCount;
		this.mekCentrifugePbUpgradeTimeMaxCount = centrifuge.mekCentrifugePbUpgradeTimeMaxCount;
		this.mekCentrifugePbUpgradeStabilityMaxCount = centrifuge.mekCentrifugePbUpgradeStabilityMaxCount;
		// 通用机械:扩展 堆叠升级上限委托字段赋值(Task 13,指向同一 ConfigValue 实例,零开销)
		this.mekCentrifugeMaxStackUpgrades = centrifuge.mekCentrifugeMaxStackUpgrades;
		// 熔炉配方兼容总开关委托字段赋值（指向同一 ConfigValue 实例，零开销）
		this.mekCentrifugeSmeltingCompatEnabled = centrifuge.mekCentrifugeSmeltingCompatEnabled;

		// MEK通用机械蜂箱配置(抽取至 ApiaryConfigSection,Task 12 委托至 ConfigSectionRegistry)
		ApiaryConfigSection apiary = this.sections.registerApiary(builder);
		// 向后兼容委托字段赋值(指向同一 ConfigValue 实例,零开销)
		this.apiaryEnergyPerTick = apiary.apiaryEnergyPerTick;
		this.apiaryProcessingTime = apiary.apiaryProcessingTime;
		this.apiaryFluidTankCapacity = apiary.apiaryFluidTankCapacity;
		this.apiaryEjectDelay = apiary.apiaryEjectDelay;
		this.apiaryEjectDelayActive = apiary.apiaryEjectDelayActive;
		this.apiaryEjectMaxSpeedMode = apiary.apiaryEjectMaxSpeedMode;
		this.apiaryEjectMaxPerTick = apiary.apiaryEjectMaxPerTick;
		this.apiaryEjectBlockedThreshold = apiary.apiaryEjectBlockedThreshold;
		this.apiaryEjectBlockedCooldown = apiary.apiaryEjectBlockedCooldown;
		this.apiaryStackBasic = apiary.apiaryStackBasic;
		this.apiaryStackAdvanced = apiary.apiaryStackAdvanced;
		this.apiaryStackElite = apiary.apiaryStackElite;
		this.apiaryStackUltimate = apiary.apiaryStackUltimate;
		this.apiaryStackMeAbsolute = apiary.apiaryStackMeAbsolute;
		this.apiaryStackMeSupreme = apiary.apiaryStackMeSupreme;
		this.apiaryStackMeCosmic = apiary.apiaryStackMeCosmic;
		this.apiaryStackMeInfinite = apiary.apiaryStackMeInfinite;
		// EM 工厂蜂箱堆叠倍率委托字段赋值(指向同一 ConfigValue 实例,零开销;EM 未加载时为 null)
		this.apiaryStackEmOverclocked = apiary.apiaryStackEmOverclocked;
		this.apiaryStackEmQuantum = apiary.apiaryStackEmQuantum;
		this.apiaryStackEmDense = apiary.apiaryStackEmDense;
		this.apiaryStackEmMultiversal = apiary.apiaryStackEmMultiversal;
		this.apiaryStackEmCreative = apiary.apiaryStackEmCreative;
		this.apiaryStackEmeAbsoluteOverclocked = apiary.apiaryStackEmeAbsoluteOverclocked;
		this.apiaryStackEmeSupremeQuantum = apiary.apiaryStackEmeSupremeQuantum;
		this.apiaryStackEmeCosmicDense = apiary.apiaryStackEmeCosmicDense;
		this.apiaryStackEmeInfiniteMultiversal = apiary.apiaryStackEmeInfiniteMultiversal;
		this.apiaryAeOutputEnabled = apiary.apiaryAeOutputEnabled;
		this.apiaryAeFluidOutputEnabled = apiary.apiaryAeFluidOutputEnabled;
		this.apiaryAeEnergyInputEnabled = apiary.apiaryAeEnergyInputEnabled;
		this.apiaryPreferAppliedFluxOverAeEnergy = apiary.apiaryPreferAppliedFluxOverAeEnergy;
		this.apiaryPbUpgradeProductivityMaxCount = apiary.apiaryPbUpgradeProductivityMaxCount;
		this.apiaryPbUpgradeTimeMaxCount = apiary.apiaryPbUpgradeTimeMaxCount;
		this.apiaryPbUpgradeGeneSamplerMaxCount = apiary.apiaryPbUpgradeGeneSamplerMaxCount;
		this.apiaryPbUpgradeBlockMaxCount = apiary.apiaryPbUpgradeBlockMaxCount;
	}
}
