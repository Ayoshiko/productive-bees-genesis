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
 * 以 NeoForge {@link ConfigurationScreen} 为基础，保留其通用/客户端分类入口。
 * 对<b>服务端配置</b>使用自定义 {@link ServerConfigScreen} 作为中间页：
 * <ul>
 *   <li>“万象创世过滤” — 打开 {@link FilterListScreen}，支持搜索、多选、滚动、全选</li>
 *   <li>“其他服务端配置” — 打开 NeoForge 原生服务端分组列表</li>
 * </ul>
 */
public final class CustomConfigScreenFactory implements IConfigScreenFactory {

	@Override
	public Screen createScreen(ModContainer container, Screen modListScreen) {
		return new ConfigurationScreen(container, modListScreen, (screen, type, modConfig, title) -> {
			if (type == ModConfig.Type.SERVER) {
				return new ServerConfigScreen(screen, modConfig);
			}
			return new ConfigurationScreen.ConfigurationSectionScreen(screen, type, modConfig, title);
		});
	}
}
