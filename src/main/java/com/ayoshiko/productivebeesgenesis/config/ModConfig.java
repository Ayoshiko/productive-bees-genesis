package com.ayoshiko.productivebeesgenesis.config;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件 — 万象创世蜜蜂属性覆盖
 * <p>
 * 允许整合包作者通过配置文件修改蜜蜂属性，无需编辑数据包JSON。
 * 默认值与数据包JSON一致，修改后需重启游戏或执行/reload生效。
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
     * 通用配置 — 服务端生效的游戏逻辑参数
     * <p>
     * 修改后需重启游戏或执行 /reload 生效。
     */
    public static class CommonConfig {

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

        // ========== MEK离心机配置 ==========
        public final ModConfigSpec.IntValue mekCentrifugeEnergyPerTick;
        public final ModConfigSpec.IntValue mekCentrifugeProcessingTime;
        public final ModConfigSpec.IntValue mekCentrifugeEjectDelay;
        public final ModConfigSpec.IntValue mekCentrifugeEjectDelayActive;
        public final ModConfigSpec.IntValue mekCentrifugeFluidTankCapacity;
        public final ModConfigSpec.IntValue mekCentrifugeCombBlockMultiplier;
        public final ModConfigSpec.BooleanValue enablePerformanceMonitor;
        // Task 13: AE2/管道拉取限流（防止 ME 接口过载拉取触发全量排序扫描）
        public final ModConfigSpec.IntValue mekCentrifugeMaxExtractPerTick;

        CommonConfig(ModConfigSpec.Builder builder) {
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
                    .comment("流体输出罐基础容量(mB)", "工厂版会按并行数倍增此值")
                    .defineInRange("fluidTankCapacity", 10000, 1000, 100000);

            mekCentrifugeCombBlockMultiplier = builder
                    .comment("蜜脾块产出倍率（蜜脾块 = N个蜜脾）", "影响离心蜜脾块时物品和流体产出的倍数")
                    .defineInRange("combBlockMultiplier", 4, 1, 16);

            enablePerformanceMonitor = builder
                    .comment("启用性能监控（兼容Spark profiler，通过JMX暴露数据）")
                    .define("enablePerformanceMonitor", false);

            // Task 13: AE2/管道拉取限流 — 默认0=无限制，不影响正常游戏
            mekCentrifugeMaxExtractPerTick = builder
                    .comment("每游戏刻外部通过管道/AE2从离心机输出槽拉取的最大物品总数（0=无限制）",
                            "防止ME接口过载拉取导致主线程卡顿")
                    .defineInRange("mekCentrifugeMaxExtractPerTick", 0, 0, 1024);

            builder.pop(); // mek_centrifuge
        }
    }

    /**
     * 服务端配置 — 存档级别配置
     * <p>
     * 随存档保存，不同存档可拥有不同配置。
     * 万象创世蜜蜂过滤配置迁移至此，支持每个存档独立的过滤规则。
     */
    public static class ServerConfig {

        // ========== 万象创世过滤配置（存档级别）==========
        // 使用枚举类型，ConfigurationScreen自动渲染循环切换按钮
        public final ModConfigSpec.EnumValue<FilterMode> myriadCreationsFilterMode;
        public final ModConfigSpec.ConfigValue<List<? extends String>> myriadCreationsFilteredBeeTypes;

        ServerConfig(ModConfigSpec.Builder builder) {
            builder.comment("万象创世蜜蜂过滤配置（存档级别）").push("myriad_creations_filter");

            myriadCreationsFilterMode = builder
                    .comment("过滤模式", "DISABLED - 不过滤，万象创世可转化为所有蜜蜂类型", "BLACKLIST - 黑名单，排除列表中的蜜蜂类型", "WHITELIST - 白名单，仅允许列表中的蜜蜂类型")
                    .defineEnum("filterMode", FilterMode.DISABLED);

            myriadCreationsFilteredBeeTypes = builder
                    .comment("过滤的蜜蜂类型列表", "格式: 模组ID:蜜蜂类型，如 productivebees:iron", "黑名单模式下排除这些类型，白名单模式下仅允许这些类型")
                    .defineList("filteredBeeTypes", List.of(), () -> "productivebees:iron", ModConfig::validateResourceLocationElement);

            builder.pop();
        }
    }
}