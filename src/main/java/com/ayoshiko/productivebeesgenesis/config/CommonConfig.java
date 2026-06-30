package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置 — 跨端同步且在世界加载前就需要读取的字段
 * <p>
 * 从 {@link ModConfig} 抽取的独立配置类（Task 21），遵循单一职责原则（SRP）。
 * 当前所有服务端游戏逻辑字段均已迁移至 {@link ServerConfig}，
 * 此处仅保留性能监控开关等需要在 common setup 阶段读取的字段。
 * 实例由 {@link ModConfig#COMMON} 聚合持有，外部访问路径 {@code ModConfig.COMMON.xxx} 保持不变。
 */
public final class CommonConfig {

	public final ModConfigSpec.BooleanValue enablePerformanceMonitor;

	CommonConfig(ModConfigSpec.Builder builder) {
		builder.comment("通用配置 — 跨端同步且无需按存档区分的参数").push("common");

		enablePerformanceMonitor = builder
				.comment("启用性能监控（兼容 Spark profiler，通过 JMX 暴露数据）")
				.define("enablePerformanceMonitor", false);

		builder.pop(); // common
	}
}
