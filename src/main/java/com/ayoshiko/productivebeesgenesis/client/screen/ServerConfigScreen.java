package com.ayoshiko.productivebeesgenesis.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * 服务端配置中间页
 * <br/>
 * 与 NeoForge {@link net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen}
 * 保持一致的 {@link OptionsSubScreen} 风格：左侧标签、右侧操作按钮、底部“完成”按钮。
 * 当前仅包含万象创世过滤入口，后续可在此扩展更多服务端配置项。
 */
public final class ServerConfigScreen extends OptionsSubScreen {

    public ServerConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("productivebeesgenesis.config.server.title"));
    }

    // NeoForge 原生配置节屏幕的按钮/标签后缀（如“彩虹特效...”）
    private static final String SECTION_SUFFIX_KEY = "neoforge.configuration.uitext.section";

    @Override
    protected void addOptions() {
        // 左侧标签：与原生 ConfigurationSectionScreen 一致，使用“万象创世过滤...”形式
        Component label = Component.translatable(SECTION_SUFFIX_KEY,
                Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter"));
        StringWidget labelWidget = new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, label, font).alignLeft();
        labelWidget.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter.tooltip")));

        // 右侧按钮：进入自定义过滤编辑器，同样使用“...”后缀保持风格一致
        Component buttonText = Component.translatable(SECTION_SUFFIX_KEY,
                Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter.button"));
        Button filterButton = Button.builder(buttonText, button -> minecraft.setScreen(new FilterListScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter.tooltip")))
                .width(Button.DEFAULT_WIDTH)
                .build();

        list.addSmall(labelWidget, filterButton);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		// 在底部绘制重载提示，提醒玩家保存后执行 /reload
		Component hint = Component.translatable("productivebeesgenesis.config.server.reload_hint");
		graphics.drawCenteredString(font, hint, width / 2, height - 50, 0xFFFFA0A0);
	}
}
