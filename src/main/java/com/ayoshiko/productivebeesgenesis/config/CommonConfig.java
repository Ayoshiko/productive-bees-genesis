package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置 — 跨端同步且在世界加载前就需要读取的字段
 * <p>
 * 从 {@link ModConfig} 抽取的独立配置类（Task 21），遵循单一职责原则（SRP）。
 * 当前所有服务端游戏逻辑字段均已迁移至 {@link ServerConfig}。
 * 此处保留空的 common 分类占位，确保配置界面"通用配置"按钮不失效。
 * 实例由 {@link ModConfig#COMMON} 聚合持有，外部访问路径 {@code ModConfig.COMMON.xxx} 保持不变。
 */
public final class CommonConfig {

	CommonConfig(ModConfigSpec.Builder builder) {
		// 保留空的 common 分类占位，避免配置界面"通用配置"按钮失效
		builder.comment("通用配置 — 跨端同步且无需按存档区分的参数").push("common");
		// 此分类当前无配置项，保留占位结构供未来扩展
		builder.pop(); // common
	}
}
