package com.ayoshiko.productivebeesgenesis.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 自定义配置屏幕工厂
 * <br/>
 * 以 NeoForge {@link ConfigurationScreen} 为基础，保留其通用/客户端/服务端分类入口。
 * 对服务端配置中的“万象创世过滤”分组使用自定义 {@link FilterListScreen}，
 * 解决默认列表编辑器不支持空列表添加、搜索选择和排序的问题。
 * <p>
 * 其他所有服务端分组（蜜蜂属性、获得方式、转化产出、高级蜂箱、MEK离心机等）
 * 仍使用 NeoForge 原生配置界面。
 */
public final class CustomConfigScreenFactory implements IConfigScreenFactory {

    @Override
    public Screen createScreen(ModContainer container, Screen modListScreen) {
        return new ConfigurationScreen(container, modListScreen, (screen, type, modConfig, title) -> {
            // 仅对服务端配置中的万象创世过滤分组使用自定义编辑器
            if (type == ModConfig.Type.SERVER && isMyriadCreationsFilterTitle(title)) {
                return new FilterListScreen(screen);
            }
            return new ConfigurationScreen.ConfigurationSectionScreen(screen, type, modConfig, title);
        });
    }

    /**
     * 判断当前 section 标题是否为“万象创世过滤”
     * <br/>
     * 通过翻译后的可见文本匹配，兼容中文与英文客户端。
     */
    private static boolean isMyriadCreationsFilterTitle(Component title) {
        String text = title.getString();
        return "万象创世过滤".equals(text) || "Myriad Creations Filter".equals(text);
    }
}
