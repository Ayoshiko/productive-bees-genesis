package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件 — 万象创世蜜蜂属性覆盖
 * <p>
 * 允许整合包作者通过配置文件修改蜜蜂属性，无需编辑数据包JSON。
 * 默认值与数据包JSON一致，修改后需重启游戏或执行 /reload 生效。
 */
public final class ModConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ClientConfig CLIENT;

    static {
        var pair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT = pair.getKey();
        CLIENT_SPEC = pair.getValue();
    }

    public static class ClientConfig {

        // ========== 外观 ==========
        public final ModConfigSpec.ConfigValue<String> primaryColor;
        public final ModConfigSpec.ConfigValue<String> secondaryColor;
        public final ModConfigSpec.ConfigValue<String> particleColor;

        // ========== 授粉 ==========
        public final ModConfigSpec.ConfigValue<String> flowerItem;

        // ========== PB 属性 ==========
        public final ModConfigSpec.ConfigValue<String> weatherTolerance;
        public final ModConfigSpec.ConfigValue<String> temper;
        public final ModConfigSpec.ConfigValue<String> behavior;
        public final ModConfigSpec.ConfigValue<String> endurance;
        public final ModConfigSpec.ConfigValue<String> productivity;

        // ========== 基础属性 ==========
        public final ModConfigSpec.BooleanValue createComb;
        public final ModConfigSpec.DoubleValue size;
        public final ModConfigSpec.DoubleValue speed;
        public final ModConfigSpec.DoubleValue attack;

        // ========== 繁殖 ==========
        public final ModConfigSpec.ConfigValue<String> breedingItem;
        public final ModConfigSpec.IntValue breedingItemCount;
        public final ModConfigSpec.BooleanValue selfbreed;

        // ========== 环境耐受 ==========
        public final ModConfigSpec.BooleanValue waterproof;
        public final ModConfigSpec.BooleanValue fireproof;

        ClientConfig(ModConfigSpec.Builder builder) {
            builder.push("bee_attributes").comment("万象创世蜜蜂属性覆盖配置");

            primaryColor = builder
                    .comment("主颜色（十六进制，如 #FFD700）")
                    .define("primaryColor", "#FFD700");

            secondaryColor = builder
                    .comment("次要颜色")
                    .define("secondaryColor", "#FF69B4");

            particleColor = builder
                    .comment("粒子颜色")
                    .define("particleColor", "#00FFFF");

            flowerItem = builder
                    .comment("授粉物品ID")
                    .define("flowerItem", "productivebees:honey_treat");

            builder.push("pb_attributes").comment("Productive Bees 独有属性");
            weatherTolerance = builder
                    .comment("天气耐受性", "可选值: weather_tolerance.none / weather_tolerance.rain / weather_tolerance.any")
                    .define("weatherTolerance", "weather_tolerance.any");
            temper = builder
                    .comment("性格", "可选值: temper.passive / temper.normal / temper.hostile / temper.aggressive")
                    .define("temper", "temper.passive");
            behavior = builder
                    .comment("行为", "可选值: behavior.diurnal (昼行) / behavior.nocturnal (夜行) / behavior.metaturnal (昼夜皆可)")
                    .define("behavior", "behavior.metaturnal");
            endurance = builder
                    .comment("耐力", "可选值: endurance.weak / endurance.normal / endurance.medium / endurance.strong")
                    .define("endurance", "endurance.strong");
            productivity = builder
                    .comment("产量", "可选值: productivity.normal / productivity.medium / productivity.high / productivity.very_high")
                    .define("productivity", "productivity.very_high");
            builder.pop();

            createComb = builder
                    .comment("是否能产出蜜脾")
                    .define("createComb", true);

            size = builder
                    .comment("蜜蜂大小")
                    .defineInRange("size", 1.0D, 0.1D, 10.0D);

            speed = builder
                    .comment("飞行速度")
                    .defineInRange("speed", 0.5D, 0.01D, 10.0D);

            attack = builder
                    .comment("攻击力")
                    .defineInRange("attack", 20.0D, 0.0D, 100.0D);

            breedingItem = builder
                    .comment("繁殖物品ID")
                    .define("breedingItem", "productivebees:honey_treat");

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

            builder.pop();
        }
    }
}