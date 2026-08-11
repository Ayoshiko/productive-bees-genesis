package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
	 * 客户端配置 — 仅影响本地渲染/显示
	 * <p>
	 * 从 {@link ModConfig} 抽取的独立配置类（Task 21），遵循单一职责原则（SRP）。
	 * 实例由 {@link ModConfig#CLIENT} 聚合持有，外部访问路径 {@code ModConfig.CLIENT.xxx} 保持不变。
	 */
public final class ClientConfig {

	// ========== 彩虹特效（纯客户端渲染）==========
	public final ModConfigSpec.BooleanValue rainbowMode;
	public final ModConfigSpec.BooleanValue particleEffectEnabled;
	public final ModConfigSpec.BooleanValue glowEnabled;
	public final ModConfigSpec.IntValue particleCount;

	// ========== MEK离心机端口可视化（纯客户端渲染）==========
	public final ModConfigSpec.BooleanValue showPortColors;
	public final ModConfigSpec.IntValue portColorRenderRange;

	// ========== 窗口位置持久化（独立于 MEK 配置系统）==========
	public final WindowPositionConfigSection windowPositions = new WindowPositionConfigSection();

	ClientConfig(ModConfigSpec.Builder builder) {
		// 彩虹特效配置（仅客户端可见的粒子/光晕开关）
		builder.push("rainbow_effects").comment("万象创世蜜蜂彩虹特效配置（仅客户端生效）");

		rainbowMode = builder
				.comment("启用彩虹模式（颜色会动态变化）")
				.define("rainbowMode", true);

		particleEffectEnabled = builder
				.comment("启用彩虹粒子特效")
				.define("particleEffectEnabled", true);

		particleCount = builder
				.comment("每个tick生成的粒子数量")
				.defineInRange("particleCount", 1, 1, 20);

		glowEnabled = builder
				.comment("启用光晕效果")
				.define("glowEnabled", true);

		builder.pop(); // rainbow_effects

		// MEK离心机端口可视化配置
		builder.comment("通用机械离心机端口可视化设置").push("mek_port_visualization");

		showPortColors = builder
				.comment("手持配置器时显示端口颜色")
				.define("showPortColors", true);

		portColorRenderRange = builder
				.comment("端口颜色渲染范围（方块距离）")
				.defineInRange("portColorRenderRange", 16, 4, 32);

		builder.pop(); // mek_port_visualization

		// PB 自定义窗口位置持久化
		windowPositions.registerAll(builder);
	}
}
