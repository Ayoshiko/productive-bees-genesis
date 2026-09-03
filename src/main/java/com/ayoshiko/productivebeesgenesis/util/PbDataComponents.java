package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;

/**
 * Productive Bees 数据组件类型的一次性解析缓存
 * <br/>
 * <b>动机</b>：{@code ModDataComponents.BEE_TYPE} 是 NeoForge 的 {@code DeferredHolder}，
 * 每次 {@code get()} 都要做一次注册表查找 + 绑定状态校验。它出现在多条每 tick 热路径上，
 * 且常在同一方法内被连续调用多次（如
 * {@code Ae2InputPuller$PullEntry.matchesComponents} 一次调用 4 次）。
 * <p>
 * spark 采样 AHlDkwd9n9（60s / NeoForge 21.1.214 / 45 mods）中
 * {@code DeferredHolder.get} 自耗 884ms、占服务端线程 1.47%，为全服第 2 热方法；
 * NKn1ZLQN2W 中同为 468ms / 0.78%。这些调用返回的都是同一个不变实例。
 * <p>
 * <b>为什么缓存是安全的</b>：{@code DataComponentType} 属于静态注册表
 * （{@code BuiltInRegistries.DATA_COMPONENT_TYPE}），在模组构造期注册一次，
 * 不参与数据包/世界重载，整个 JVM 生命周期内实例恒定。因此无需失效机制。
 * <p>
 * <b>线程安全</b>：字段声明为 {@code volatile}，服务端 tick 线程与客户端渲染线程可并发读；
 * 竞态下最坏是重复解析一次同一实例，不会发布未完成构造的对象
 * （该实例由 NeoForge 在注册阶段构造完毕并早已发布）。
 * <p>
 * <b>惰性解析</b>：不在类初始化时解析，避免在注册完成前触发 {@code DeferredHolder} 抛异常，
 * 行为与原先的直接调用完全一致。
 */
public final class PbDataComponents {

	/** 已解析的 bee_type 组件类型；null 表示尚未解析。 */
	private static volatile DataComponentType<ResourceLocation> beeType;

	private PbDataComponents() {
		// 工具类禁止实例化
	}

	/**
	 * 返回 PB 的 {@code bee_type} 数据组件类型，首次调用后不再走注册表查找。
	 *
	 * @return bee_type 组件类型（与 {@code ModDataComponents.BEE_TYPE.get()} 同一实例）
	 */
	public static DataComponentType<ResourceLocation> beeType() {
		DataComponentType<ResourceLocation> cached = beeType;
		if (cached == null) {
			cached = ModDataComponents.BEE_TYPE.get();
			beeType = cached;
		}
		return cached;
	}
}
