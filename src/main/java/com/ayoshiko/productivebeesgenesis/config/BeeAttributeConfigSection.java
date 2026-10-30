package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	public final ModConfigSpec.ConfigValue<String> renderer;
	public final ModConfigSpec.ConfigValue<String> model;
	public final ModConfigSpec.ConfigValue<String> animation;
	public final ModConfigSpec.ConfigValue<String> beeTexture;
	public final ModConfigSpec.ConfigValue<String> particleType;
	public final ModConfigSpec.ConfigValue<String> nestingPreference;
	public final ModConfigSpec.ConfigValue<String> postPollination;
	public final ModConfigSpec.ConfigValue<String> tertiaryColor;
	public final ModConfigSpec.DoubleValue pollinatedSize;
	public final ModConfigSpec.BooleanValue useGlowLayer;
	public final ModConfigSpec.ConfigValue<String> attackResponse;
	public final ModConfigSpec.ConfigValue<List<? extends String>> invulnerability;
	public final ModConfigSpec.ConfigValue<String> renderTransform;
	public final ModConfigSpec.ConfigValue<String> flowerBlock;
	public final ModConfigSpec.ConfigValue<String> flowerTag;
	public final ModConfigSpec.ConfigValue<String> flowerFluid;
	public final ModConfigSpec.ConfigValue<String> flowerType;
	public final Map<String, ModConfigSpec.BooleanValue> traits;

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
				.comment("是否能产出蜜脾", "默认关闭，使用 PB configurable_honeycomb")
				.define("createComb", false);

		size = builder
				.comment("蜜蜂大小")
				.defineInRange("size", 1.2D, 0.1D, 10.0D);

		speed = builder
				.comment("飞行速度")
				.defineInRange("speed", 0.6D, 0.01D, 10.0D);

		attack = builder
				.comment("攻击伤害声明值（0～2147483647）；实际伤害仍受 Minecraft 攻击属性上限及其它模组影响")
				.defineInRange("attack", 2.147483647E9D, 0.0D, (double) Integer.MAX_VALUE);

		breedingItem = builder
				.comment("繁殖物品 ID、#物品标签；空值使用 PB 默认")
				.define("breedingItem", "productivebees:honey_treat", value -> value instanceof String text && text.length() <= 256
						&& (text.isEmpty() || ModConfig.validateResourceLocation(text.startsWith("#") ? text.substring(1) : text)));

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

		renderer = builder.comment("PB 渲染外观；模型不会改变蜜蜂 AI")
				.translation("productivebeesgenesis.configuration.bee_attributes.renderer")
				.define("renderer", "default", value -> value instanceof String text && Set.of("default", "thicc", "default_crystal",
						"default_shell", "default_foliage", "elvis", "tiny", "translucent_with_center", "small", "slim").contains(text));
		model = builder.comment("PB 或已安装资源包的模型资源 ID；空值使用默认模型")
				.translation("productivebeesgenesis.configuration.bee_attributes.model")
				.define("model", "", BeeAttributeConfigSection::optionalResource);
		animation = builder.comment("模型动画资源 ID；空值使用默认动画")
				.translation("productivebeesgenesis.configuration.bee_attributes.animation")
				.define("animation", "", BeeAttributeConfigSection::optionalResource);
		beeTexture = builder.comment("蜜蜂贴图资源 ID，不包含 .png；空值使用默认贴图")
				.translation("productivebeesgenesis.configuration.bee_attributes.beeTexture")
				.define("beeTexture", "", BeeAttributeConfigSection::optionalResource);
		particleType = builder.comment("PB 粒子类型：drip / pop / lava / portal / rising；只影响外观")
				.translation("productivebeesgenesis.configuration.bee_attributes.particleType")
				.define("particleType", "drip", value -> value instanceof String text && Set.of("drip", "pop", "lava", "portal", "rising").contains(text));
		nestingPreference = builder.comment("筑巢材料标签；空值沿用 PB 默认偏好，不赋予独居蜂 AI")
				.translation("productivebeesgenesis.configuration.bee_attributes.nestingPreference")
				.define("nestingPreference", "", BeeAttributeConfigSection::optionalResource);
		postPollination = builder.comment("PB 采蜜后行为：none / amber_encase / bonemeal / force_grow_crop / sus；目标仍由花源决定")
				.translation("productivebeesgenesis.configuration.bee_attributes.postPollination")
				.define("postPollination", "none", value -> value instanceof String text
						&& Set.of("none", "amber_encase", "bonemeal", "force_grow_crop", "sus").contains(text));
		tertiaryColor = builder.comment("第三颜色；空值跟随主颜色")
				.translation("productivebeesgenesis.configuration.bee_attributes.tertiaryColor")
				.define("tertiaryColor", "", value -> value instanceof String text && (text.isEmpty() || ModConfig.validateColor(text)));
		pollinatedSize = builder.comment("采蜜后体型；0 表示跟随基础体型")
				.translation("productivebeesgenesis.configuration.bee_attributes.pollinatedSize")
				.defineInRange("pollinatedSize", 0.0D, 0.0D, 10.0D);
		useGlowLayer = builder.comment("PB 蜜蜂发光图层")
				.translation("productivebeesgenesis.configuration.bee_attributes.useGlowLayer")
				.define("useGlowLayer", false);
		attackResponse = builder.comment("攻击附加效果：none / fire / lava")
				.translation("productivebeesgenesis.configuration.bee_attributes.attackResponse")
				.define("attackResponse", "none", value -> value instanceof String text && Set.of("none", "fire", "lava").contains(text));
		invulnerability = builder.comment("免疫的伤害类型 msgId；空列表不增加免疫")
				.translation("productivebeesgenesis.configuration.bee_attributes.invulnerability")
				.defineListAllowEmpty("invulnerability", List.of(), () -> "inFire", value -> value instanceof String text
						&& !text.isBlank() && text.length() <= 128 && text.chars().noneMatch(Character::isISOControl));
		renderTransform = builder.comment("PB 渲染变换名称；none 表示默认")
				.translation("productivebeesgenesis.configuration.bee_attributes.renderTransform")
				.define("renderTransform", "none", value -> value instanceof String text && text.length() <= 128);
		flowerBlock = builder.comment("方块花源 ID；空值不覆盖其它花源")
				.translation("productivebeesgenesis.configuration.bee_attributes.flowerBlock")
				.define("flowerBlock", "", BeeAttributeConfigSection::optionalResource);
		flowerTag = builder.comment("花源标签；可用 ! 前缀反向匹配方块标签")
				.translation("productivebeesgenesis.configuration.bee_attributes.flowerTag")
				.define("flowerTag", "", value -> value instanceof String text && text.length() <= 256
						&& (text.isEmpty() || ModConfig.validateResourceLocation(text.startsWith("!") ? text.substring(1) : text)));
		flowerFluid = builder.comment("流体花源 ID；由支持该花源的蜂箱执行")
				.translation("productivebeesgenesis.configuration.bee_attributes.flowerFluid")
				.define("flowerFluid", "", BeeAttributeConfigSection::optionalResource);
		flowerType = builder.comment("花源标签匹配：blocks / entity_types")
				.translation("productivebeesgenesis.configuration.bee_attributes.flowerType")
				.define("flowerType", "blocks", value -> value instanceof String text && Set.of("blocks", "entity_types").contains(text));
		Map<String, ModConfigSpec.BooleanValue> configurableTraits = new LinkedHashMap<>();
		for (String trait : new String[]{"coldResistant", "selfheal", "irradiated", "withered", "blinding", "draconic",
				"slimy", "teleporting", "munchies", "redstoned", "stringy", "stingless", "translucent", "colorCycle"}) {
			configurableTraits.put(trait, builder.translation("productivebeesgenesis.configuration.bee_attributes." + trait)
					.define(trait, false));
		}
		traits = java.util.Collections.unmodifiableMap(configurableTraits);
		builder.pop(); // bee_attributes
	}

	private static boolean optionalResource(Object value) {
		return value instanceof String text && text.length() <= 256
				&& (text.isEmpty() || ModConfig.validateResourceLocation(text));
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
