package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

/**
 * 语言文件数据生成器
 * <br/>
 * MEK离心机重构后暂保留GUI/配置翻译键，方块翻译后续Phase添加
 */
public class ModLanguageProvider extends LanguageProvider {

    private final String locale;

    public ModLanguageProvider(PackOutput output, String locale) {
        super(output, ProductiveBeesGenesis.MOD_ID, locale);
        this.locale = locale;
    }

    @Override
    protected void addTranslations() {
        if ("en_us".equals(locale)) {
            addEnglish();
        } else if ("zh_cn".equals(locale)) {
            addChinese();
        }
    }

    private void addEnglish() {
        // 方块翻译后续Phase添加（依赖新注册系统）

        add("gui.productivebeesgenesis.mek_centrifuge_recipes", "MEK Centrifuge Recipes");
        add("gui.productivebeesgenesis.processing_time", "Time: %dt");
        add("gui.productivebeesgenesis.energy_per_tick", "Energy: %d FE/t");

        add("itemGroup.productivebeesgenesis", "MEK Centrifuge");
    }

    private void addChinese() {
        // 方块翻译后续Phase添加（依赖新注册系统）

        add("gui.productivebeesgenesis.mek_centrifuge_recipes", "MEK离心机配方");
        add("gui.productivebeesgenesis.processing_time", "时间: %dt");
        add("gui.productivebeesgenesis.energy_per_tick", "能量: %d FE/t");

        add("itemGroup.productivebeesgenesis", "MEK离心机");

        // common.toml 配置翻译键
        add("productivebeesgenesis.configuration.section.productivebeesgenesis.common.toml", "MEK离心机设置");
        add("productivebeesgenesis.configuration.section.productivebeesgenesis.common.toml.title", "MEK离心机配置");
        add("productivebeesgenesis.configuration.processingTime", "处理时间（tick）");
        add("productivebeesgenesis.configuration.processingTime.tooltip", "每次操作的基础处理时间（tick）");
        add("productivebeesgenesis.configuration.mek_centrifuge", "MEK离心机");
        add("productivebeesgenesis.configuration.mek_centrifuge.button", "MEK离心机");
        add("productivebeesgenesis.configuration.mek_centrifuge.tooltip", "MEK离心机设置");
        add("productivebeesgenesis.configuration.energyPerTick", "每tick能量消耗");
        add("productivebeesgenesis.configuration.energyPerTick.tooltip", "每个处理槽每tick的能量消耗（FE）");
    }
}
