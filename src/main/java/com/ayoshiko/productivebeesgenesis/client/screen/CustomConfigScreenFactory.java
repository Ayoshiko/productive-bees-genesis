package com.ayoshiko.productivebeesgenesis.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 自定义配置屏幕工厂
 * <br/>
 * 以 NeoForge {@link ConfigurationScreen} 为基础，保留其通用/客户端/服务端分类入口（图3），
 * 但将服务端配置（万象创世过滤）重定向到自定义 {@link FilterListScreen}，
 * 解决默认列表编辑器不支持空列表添加、搜索选择和排序的问题。
 * <p>
 * 其他配置（common.toml 的蜜蜂属性/MEK离心机、client.toml 的视觉特效）
 * 仍使用 NeoForge 原生配置界面。
 */
public final class CustomConfigScreenFactory implements IConfigScreenFactory {

	@Override
	public Screen createScreen(ModContainer container, Screen modListScreen) {
		return new ConfigurationScreen(container, modListScreen, (screen, type, modConfig, title) -> {
			// 服务端选项先进入中间页，汇总所有服务端级配置入口，避免直接跳转到过滤编辑器
			if (type == ModConfig.Type.SERVER) {
				return new ServerConfigScreen(screen);
			}
			return new ConfigurationScreen.ConfigurationSectionScreen(screen, type, modConfig, title);
		});
	}
}
