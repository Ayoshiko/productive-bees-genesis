package com.ayoshiko.productivebeesgenesis.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * 服务端配置中间页
 * <br/>
 * 与 NeoForge {@link ConfigurationScreen.ConfigurationSectionScreen}
 * 保持一致的 {@link OptionsSubScreen} 风格：左侧标签、右侧操作按钮、底部“完成”按钮。
 * <p>
 * 该页面同时提供两个入口：
 * <ul>
 *   <li>“万象创世过滤” — 打开自定义 {@link FilterListScreen}，支持搜索、多选、滚动、全选</li>
 *   <li>“其他服务端配置” — 打开 NeoForge 原生服务端分组列表（蜜蜂属性、获得方式、转化产出等）</li>
 * </ul>
 */
public final class ServerConfigScreen extends OptionsSubScreen {

    private final ModConfig modConfig;
    private final ModConfig.Type configType;

    public ServerConfigScreen(Screen parent, ModConfig modConfig) {
        super(parent, Minecraft.getInstance().options, Component.translatable("productivebeesgenesis.configuration.section.productivebeesgenesis.server.toml.title"));
        this.modConfig = modConfig;
        this.configType = ModConfig.Type.SERVER;
    }

    // NeoForge 原生配置节屏幕的按钮/标签后缀（如“彩虹特效...”）
    private static final String SECTION_SUFFIX_KEY = "neoforge.configuration.uitext.section";

    @Override
    protected void addOptions() {
        // 1. 万象创世过滤 — 自定义编辑器
        Component filterLabel = Component.translatable(SECTION_SUFFIX_KEY,
                Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter"));
        StringWidget filterLabelWidget = new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, filterLabel, font).alignLeft();
        filterLabelWidget.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter.tooltip")));

        Component filterButtonText = Component.translatable(SECTION_SUFFIX_KEY,
                Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter.button"));
        Button filterButton = Button.builder(filterButtonText, button -> minecraft.setScreen(new FilterListScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter.tooltip")))
                .width(Button.DEFAULT_WIDTH)
                .build();

        list.addSmall(filterLabelWidget, filterButton);

        // 2. 其他服务端配置 — 原生 NeoForge 配置界面
        Component otherLabel = Component.translatable(SECTION_SUFFIX_KEY,
                Component.translatable("productivebeesgenesis.configuration.server.other"));
        StringWidget otherLabelWidget = new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, otherLabel, font).alignLeft();
        otherLabelWidget.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.configuration.server.other.tooltip")));

        Component otherButtonText = Component.translatable(SECTION_SUFFIX_KEY,
                Component.translatable("productivebeesgenesis.configuration.server.other.button"));
        Button otherButton = Button.builder(otherButtonText, button -> minecraft.setScreen(
                        new ConfigurationScreen.ConfigurationSectionScreen(
                                this, configType, modConfig,
                                Component.translatable("productivebeesgenesis.configuration.section.productivebeesgenesis.server.toml.title"))))
                .tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.configuration.server.other.tooltip")))
                .width(Button.DEFAULT_WIDTH)
                .build();

        list.addSmall(otherLabelWidget, otherButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
