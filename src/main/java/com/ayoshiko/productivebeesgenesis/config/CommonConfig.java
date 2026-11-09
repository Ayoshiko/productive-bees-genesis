package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
	 * 通用显示配置 — 客户端与服务端分别加载本地配置，不由 NeoForge 跨端同步
	 * <p>
	 * 客户端显示偏好由客户端本地配置决定，不随服务器配置改变。
	 * 当前所有服务端游戏逻辑字段均已迁移至 {@link ServerConfig}。
	 * 此处保留通用显示参数（如大数字格式化开关）。
	 * 实例由 {@link ModConfig#COMMON} 聚合持有，外部访问路径 {@code ModConfig.COMMON.xxx} 保持不变。
	 */
public final class CommonConfig {

	/** 是否启用 K/M/G/T 大数字缩写显示（false 时使用千分位分隔） */
	public final ModConfigSpec.BooleanValue enableLargeNumberAbbreviation;

	CommonConfig(ModConfigSpec.Builder builder) {
		builder.comment("通用显示配置 — 两端分别加载本地设置，不按存档区分").push("common");

		enableLargeNumberAbbreviation = builder
				.comment("使用 K/M/G/T 代替千分位显示")
				.translation("productivebeesgenesis.configuration.common.enableLargeNumberAbbreviation")
				.define("enableLargeNumberAbbreviation", true);

		builder.pop(); // common
	}
}
