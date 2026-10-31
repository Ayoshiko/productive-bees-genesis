package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 输出推送的退避记账与诊断日志（从 {@code Ae2OutputPusher} 拆出）
 * <p>
 * 拆分理由：逐槽路径与合并路径都要用同一组「慢 insert 退避 / 完全失败 / 配额收缩」语义，
 * 集中在此可保证两条路径的退避行为不漂移。
 */
final class Ae2OutputBackoffLog {

	/** 物品推送失败计数器 — 用于日志显示近 5 分钟累计触发次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong itemPushFailureCount = new AtomicLong(0);

	private Ae2OutputBackoffLog() {
	}

	/**
	 * 完全失败处理：仅记录短指数退避和聚合日志，不搬运输出槽，也不暂停输入。
	 */
	static void handleCompleteFailure(Ae2PushBackoff itemBackoff, AEItemKey itemKey, long requestedAmount) {
		long failureCount = itemPushFailureCount.incrementAndGet();
		itemBackoff.recordFailure(System.nanoTime());
		LogThrottle.warnWithCooldown("ae2_output_backoff", 300_000L,
				"AE2 物品输出推送完全失败，进入短退避 item={}, count={}, 近5分钟累计 {} 次",
				itemKey, requestedAmount, failureCount);
	}

	/**
	 * 慢 insert 退避日志（节流 5 分钟）— 病态网络诊断入口。
	 * <p>
	 * insert 成功但耗时超 {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS} 说明网络含
	 * 昂贵外部存储（如 ProjectExpansion 转换接口触发 ProjectE EMC 全量查询），
	 * 推送仍会完成但频率被指数退避限制。玩家可据此定位 ME 网络侧的性能问题。
	 * <p>
	 * 保留 {@code LogThrottle}（而非开发者模式门控）：单次 &gt;0.5ms 的 insert 属于确实需要
	 * 玩家知晓的异常，且触发前提是已进入退避（推送变慢），不属于常态自适应。
	 */
	static void logSlowInsertBackoff(Ae2PushBackoff itemBackoff) {
		LogThrottle.warnWithCooldown("ae2_slow_insert_backoff", 300_000L,
				"AE2 网络 insert 耗时异常（>{}ms），推送频率已指数退避（当前指数 {}）— "
						+ "请检查 ME 网络中的昂贵外部存储（转换接口/EnderDrives 等）",
				Ae2GlobalInsertBudget.SLOW_INSERT_NANOS / 1_000_000,
				itemBackoff.getBackoffExponent());
	}

	/**
	 * 自适应键配额收缩日志 — 中等昂贵高频网络的诊断入口。
	 * <p>
	 * 与 {@link #logSlowInsertBackoff} 互补：后者针对单次 &gt;0.5ms 的极慢 insert，
	 * 本日志针对单次 0.15-0.5ms 但高频（ae2lt Matrix Port 样板解码、大量转换接口等）
	 * 的场景 —— 这类 insert 不触发慢阈值，只能靠均值判定并收缩每 tick 键配额。
	 * <p>
	 * 走 {@link Ae2ExpensiveNetworkLog}（开发者模式门控 + 60 秒冷却）而非 {@code LogThrottle}：
	 * 配额收缩是自适应行为而非故障，且判定位于每 tick 热路径，默认不应写入玩家日志。
	 */
	static void logAdaptiveKeyQuota(Ae2InsertCostTracker tracker, int keyQuota) {
		Ae2ExpensiveNetworkLog.insertQuotaShrunk(
				tracker.averageCostNanos(), keyQuota, Ae2PushLimits.MAX_ITEM_KEYS_PER_TICK);
	}
}
