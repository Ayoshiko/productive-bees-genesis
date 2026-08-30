package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.security.IActionSource;
import appeng.me.helpers.BaseActionSource;

/**
 * AE2 输出推送路径的共享常量与操作源（从 {@code Ae2OutputPusher} 拆出）
 * <p>
 * 拆分原因：{@code Ae2OutputPusher} 原为 1102 行，超出项目 500 行阈值。
 * 拆分后 {@link Ae2OutputPusher}（编排）、{@link Ae2OutputSlotPass}（逐槽路径）、
 * {@link Ae2OutputMergedPass}（合并路径）、{@link Ae2DirectItemPushSession}（直推会话）
 * 都需要这同一组阈值与 {@link IActionSource}，集中放在这里避免多份定义漂移。
 */
final class Ae2PushLimits {

	/** 每台机器每游戏刻最多提交的不同物品键数，限制大型两页库存的 AE 网络尖峰。 */
	static final int MAX_ITEM_KEYS_PER_TICK = 32;

	/**
	 * 单次推送剩余 key 的时间预算（纳秒）— 时间维度保护，与 key 数量限制互补。
	 * <p>
	 * Spark 依据：两份报告均显示 insert 触发的网络遍历是唯一热点，数量限制（32 key）
	 * 无法感知单次 insert 成本差异 — 病态网络 32 key × 10ms = 320ms/tick。
	 * 预算耗尽后剩余 key 顺延（复用 firstDeferredKey 轮转机制），物品留原槽无损。
	 * <p>
	 * <b>只统计慢 insert 的超出耗时</b>：与 {@link Ae2GlobalInsertBudget} 同一策略，
	 * 健康网络（单次 &lt; {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS}）累计恒 0，
	 * 32 key 满速推送不受预算限制；病态网络立即钳制。
	 */
	static final long INSERT_TIME_BUDGET_NANOS = 1_000_000L;

	/**
	 * 连续零接收中止阈值 — 满存储专项：insert 返回 0 说明目标网络拒绝该 key，
	 * 网络状态在同 tick 内不会变化，后续 key 几乎必然同样被拒（分区存储除外，故取 3 次保守值）。
	 * 连续达到此值后 {@link Ae2DirectItemPushSession} 短路剩余推送，避免满存储下
	 * 每 tick 最多 32 次完整网络遍历（病态网络单次 5-10ms → 单机 160-320ms/tick）。
	 */
	static final int CONSECUTIVE_ZERO_ACCEPT_LIMIT = 3;

	private Ae2PushLimits() {
	}

	/**
	 * 懒加载 Holder — AE2 未安装时本类初始化不触发 {@link BaseActionSource} 类解析（Issue #8）
	 * <br/>
	 * 若写成静态字段则在 &lt;clinit&gt; 执行，先于任何方法体守卫，AE2 未安装时首次调用即
	 * NoClassDefFoundError。Holder 类仅在首次访问 INSTANCE 时初始化（JVM 类加载机制保证线程安全），
	 * 而所有访问点均位于 isOutputPushEnabled 守卫之后的 AE2 路径。
	 */
	static final class ActionSourceHolder {
		/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
		static final IActionSource INSTANCE = new BaseActionSource() {};

		private ActionSourceHolder() {
		}
	}
}
