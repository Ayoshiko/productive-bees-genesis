package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 万象创世蜜蜂属性配置段 — 从 {@link ServerConfig} 抽取的独立配置段（Task 19）。
 * <p>
 * 遵循单一职责原则（SRP）：将万象创世蜜蜂的属性覆盖配置（颜色、PB 独有属性、
 * 体型/速度/攻击、繁殖/防火/防水等）集中管理。
 * <p>
 * 通过 {@link #create(ModConfigSpec.Builder)} 工厂方法注册所有配置项并返回实例，
 * 由 {@link ServerConfig} 聚合持有。外部访问路径 {@code ModConfig.SERVER.primaryColor}
 * 等（向后兼容委托字段）保持不变。
 * <p>
 * 配置键名与层级（bee_attributes.* 含 colors / pb_attributes 子段）与抽取前完全一致，
 * 纯重构无行为变更。校验逻辑复用 {@link ModConfig} 中的 package-private validator
 * 与常量集合，保证配置文件 validator 与网络包服务端校验单一来源（SRP）。
 */
public final class BeeAttributeConfigSection {

	// ========== 颜色配置（写入蜜蜂数据并在客户端渲染）==========
	public final ModConfigSpec.ConfigValue<String> primaryColor;
	public final ModConfigSpec.ConfigValue<String> secondaryColor;
	public final ModConfigSpec.ConfigValue<String> particleColor;
	public final ModConfigSpec.ConfigValue<String> glowColor;

	// ========== 授粉物品 ==========
	public final ModConfigSpec.ConfigValue<String> flowerItem;

	// ========== Productive Bees 独有属性 ==========
	public final ModConfigSpec.ConfigValue<String> weatherTolerance;
	public final ModConfigSpec.ConfigValue<String> temper;
	public final ModConfigSpec.ConfigValue<String> behavior;
	public final ModConfigSpec.ConfigValue<String> endurance;
	public final ModConfigSpec.ConfigValue<String> productivity;

	// ========== 通用属性 ==========
	public final ModConfigSpec.BooleanValue createComb;
	public final ModConfigSpec.DoubleValue size;
	public final ModConfigSpec.DoubleValue speed;
	public final ModConfigSpec.DoubleValue attack;
	public final ModConfigSpec.ConfigValue<String> breedingItem;
	public final ModConfigSpec.IntValue breedingItemCount;
	public final ModConfigSpec.BooleanValue selfbreed;
	public final ModConfigSpec.BooleanValue waterproof;
	public final ModConfigSpec.BooleanValue fireproof;

	private BeeAttributeConfigSection(ModConfigSpec.Builder builder) {
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
						o -> o instanceof String s && ModConfig.WEATHER_TOLERANCE_VALUES.contains(s));
		temper = builder
				.comment("性格", "可选值: temper.passive / temper.normal / temper.hostile / temper.aggressive")
				.define("temper", "temper.passive",
						o -> o instanceof String s && ModConfig.TEMPER_VALUES.contains(s));
		behavior = builder
				.comment("行为", "可选值: behavior.diurnal (昼行) / behavior.nocturnal (夜行) / behavior.metaturnal (昼夜皆可)")
				.define("behavior", "behavior.metaturnal",
						o -> o instanceof String s && ModConfig.BEHAVIOR_VALUES.contains(s));
		endurance = builder
				.comment("耐力", "可选值: endurance.weak / endurance.normal / endurance.medium / endurance.strong")
				.define("endurance", "endurance.strong",
						o -> o instanceof String s && ModConfig.ENDURANCE_VALUES.contains(s));
		productivity = builder
				.comment("产量", "可选值: productivity.normal / productivity.medium / productivity.high / productivity.very_high")
				.define("productivity", "productivity.very_high",
						o -> o instanceof String s && ModConfig.PRODUCTIVITY_VALUES.contains(s));
		builder.pop();

		createComb = builder
				.comment("是否能产出蜜脾", "默认关闭：万象创世使用 PB 的 configurable_honeycomb + bee_type 数据组件（不自动生成 configurable_honeycomb，createComb: false）")
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
	}

	/**
	 * 工厂方法：注册全部万象创世蜜蜂属性配置项并返回实例。
	 * <p>
	 * 调用此方法会执行 {@code builder.push("bee_attributes")} ... {@code builder.pop()}，
	 * 调用方需保证在合适的层级顺序中调用，以维持配置文件中节的顺序与抽取前一致。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册全部蜜蜂属性配置项的实例
	 */
	public static BeeAttributeConfigSection create(ModConfigSpec.Builder builder) {
		return new BeeAttributeConfigSection(builder);
	}
}