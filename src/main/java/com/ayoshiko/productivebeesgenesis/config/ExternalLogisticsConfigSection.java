package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 外部物流互操作配置段（{@code external_logistics.*}）。
 * <p>
 * 本模组的物品/流体弹出已由自研高性能通道接管（见 {@code logistics/} 包）：全量一次性弹出、
 * 按刻轮转的多流体槽视图、输出边沿唤醒、阻塞自动退避，全部<b>硬编码为最大速度</b>，
 * 不再提供 Mekanism 弹出器时代的一堆节流参数（弹出延迟、跳过刻数、单刻次数上限、长冷却…）。
 * <p>
 * 这里只保留一个会<b>改变产物落点</b>、因此需要玩家自主决定的开关：
 * {@link #externalDirectContainerOutput}。
 *
 * @since 2.1.0
 */
public final class ExternalLogisticsConfigSection {

	/** 产物直通相邻容器：配方完成时直接写入目标容器，跳过输出槽缓存 */
	public final ModConfigSpec.BooleanValue externalDirectContainerOutput;

	private ExternalLogisticsConfigSection(ModConfigSpec.Builder builder) {
		builder.comment("外部物流互操作（离心机与机械蜂箱通用）").push("external_logistics");
		externalDirectContainerOutput = builder
				.comment("产物直通：配方完成时先模拟再直接写入已配置输出面的相邻容器，跳过输出槽中转",
						"减少一次「写入输出槽 → 再被抽走」的往返，产物在输出槽里停留的时间大幅缩短",
						"关闭后回到「先进输出槽，再由弹出器/物流模组取走」的传统流程",
						"目标塞不下的部分始终回落输出槽，不会丢失；机器的自动弹出关闭时本功能同样不生效",
						"本项是总开关：开启后仍可在每台机器的侧面配置界面用「O」按钮单独关闭该机器的直通")
				.translation("productivebeesgenesis.configuration.external_logistics.directContainerOutput")
				.define("directContainerOutput", true);
		builder.pop(); // external_logistics
	}

	/**
	 * 工厂方法：注册全部外部物流配置项并返回实例。
	 *
	 * @param builder 机器参数配置构建器
	 * @return 已注册配置项的实例
	 */
	public static ExternalLogisticsConfigSection create(ModConfigSpec.Builder builder) {
		return new ExternalLogisticsConfigSection(builder);
	}
}
