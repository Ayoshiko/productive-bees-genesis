package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置 — 跨端同步且在世界加载前就需要读取的字段
 * <p>
 * 从 {@link ModConfig} 抽取的独立配置类（Task 21），遵循单一职责原则（SRP）。
 * 当前所有服务端游戏逻辑字段均已迁移至 {@link ServerConfig}。
 * 此处保留通用显示参数（如大数字格式化开关）。
 * 实例由 {@link ModConfig#COMMON} 聚合持有，外部访问路径 {@code ModConfig.COMMON.xxx} 保持不变。
 */
public final class CommonConfig {

	/** 是否启用 K/M/G/T 大数字缩写显示（false 时使用千分位分隔） */
	public final ModConfigSpec.BooleanValue enableLargeNumberAbbreviation;

	CommonConfig(ModConfigSpec.Builder builder) {
		builder.comment("通用配置 — 跨端同步且无需按存档区分的参数").push("common");

		enableLargeNumberAbbreviation = builder
				.comment("启用大数字缩写显示", "true: 使用 K/M/G/T 格式（如 1.5K、2.5M）", "false: 使用千分位分隔（如 1,500、2,500,000）")
				.define("enableLargeNumberAbbreviation", true);

		builder.pop(); // common
	}
}
