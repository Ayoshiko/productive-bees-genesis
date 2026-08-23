package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.concurrent.ThreadLocalRandom;

/**
	 * Per-tile 指数退避状态管理器（AE2 推送器），基于 {@link System#nanoTime()} 单调时钟。
	 * <br/>
	 * 每个 tile 独立持有 fluidBackoff 和 itemBackoff 两个实例。
	 * 失败时进入短指数退避窗口（50ms→100ms→200ms→400ms→800ms→1s），
	 * 窗口内跳过 AE2 存储操作。
	 * 成功时重置退避，立即恢复推送。
	 * <p>
	 * <b>JDTE 兼容</b>：退避基于 {@link System#nanoTime()} 墙钟单调时钟，
	 * 而非 level.getGameTime() 或内部 counter（JDTE 256x 加速下 MAX_BACKOFF_TICKS(200) < M(256)，
	 * counter 差值退避完全失效）。nanoTime 不受游戏刻加速影响。
	 * <p>
	 * <b>线程模型</b>：仅限服务端 tick 线程访问，跨线程使用未定义。
	 * 假设服务端 tick 线程独占调用（与现有源码注释一致，Ae2FluidPusher.java 第 56 行）。
	 * 在此假设下 nanoTime 在同一线程内单调递增（HotSpot 实现：
	 * Windows QueryPerformanceCounter / Linux CLOCK_MONOTONIC）。
	 * 若未来支持异步推送，需用 AtomicReference&lt;BackoffState&gt; 重构。
	 *
	 * @since 1.0.0
	 */
public final class Ae2PushBackoff {

	/** 初始退避纳秒（默认 50 毫秒） */
	private static final long DEFAULT_INITIAL_BACKOFF_NS = 50_000_000L;

	/** 最大退避纳秒（默认 1 秒） */
	private static final long DEFAULT_MAX_BACKOFF_NS = 1_000_000_000L;

	/**
	 * 退避窗口相位抖动系数 — Spark 实证：多台机器在同一病态网络（EnderDrives fsync）上
	 * 同 tick 失败后，无抖动的退避到期时刻完全对齐，重试时同 tick 串行发起 N 次慢 insert
	 * （对应报告 MSPT median 42ms / max 260ms 的尖峰形态）。±25% 抖动打散重试相位。
	 */
	private static final double JITTER_FACTOR = 0.25;

	/** 初始退避窗口（实例可配置，流体推送用更短窗口避免 256× 加速下长时间停机） */
	private final long initialBackoffNs;

	/** 最大退避窗口（实例可配置） */
	private final long maxBackoffNs;

	/** 退避结束时间戳（System.nanoTime()，0 表示无退避） */
	private long backoffEndNanos;

	/** 退避指数（初始 0，首次失败后变 1） */
	private int backoffExponent;

	/** 默认构造：50ms→100ms→200ms→400ms→800ms→1s */
	public Ae2PushBackoff() {
		this(DEFAULT_INITIAL_BACKOFF_NS, DEFAULT_MAX_BACKOFF_NS);
	}

	/**
	 * 自定义退避窗口构造。
	 *
	 * @param initialBackoffNs    初始退避纳秒
	 * @param maxBackoffNs        最大退避纳秒
	 */
	public Ae2PushBackoff(long initialBackoffNs, long maxBackoffNs) {
		this.initialBackoffNs = initialBackoffNs;
		this.maxBackoffNs = maxBackoffNs;
	}

	/**
	 * 判断当前是否在退避窗口内。
	 * <br/>
	 * 基于 nanoTime 绝对时间判断，窗口过期后返回 false。墙钟天然去重：
	 * 同一 gameTick 内第 1 次调用执行完整推送路径（耗时 0.1-10ms），
	 * 第 2-256 次调用通过本方法短路亚微秒级返回。
	 *
	 * @param nanos 当前 System.nanoTime() 值
	 * @return true 表示仍在退避窗口内应跳过；false 表示可以尝试推送
	 */
	public boolean shouldSkip(long nanos) {
		return backoffEndNanos > 0 && nanos < backoffEndNanos;
	}

	/**
	 * 记录推送失败，退避窗口指数增长并钳制到实例最大值。
	 * <br/>
	 * 默认退避序列：50ms → 100ms → 200ms → 400ms → 800ms → 1s。
	 *
	 * @param nanos 当前 System.nanoTime() 值
	 */
	public void recordFailure(long nanos) {
		backoffExponent++;
		// 钳制移位避免指数过大后溢出；最终窗口再由实例最大值限制。
		int shift = Math.min(backoffExponent - 1, 5);
		long backoffNanos = Math.min(maxBackoffNs, initialBackoffNs << shift);
		// 相位抖动 ±JITTER_FACTOR：窗口恒为正（0.75×~1.25×），仅打散多机重试时刻
		long jitter = (long) (backoffNanos * JITTER_FACTOR
				* ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
		backoffEndNanos = nanos + backoffNanos + jitter;
	}

	/**
	 * Records a slow storage operation with a cost-sensitive window.
	 * <p>
	 * A fixed first window is too aggressive for a 0.6-1ms external inventory call,
	 * while treating a 100ms serialization stall as an ordinary failure causes the
	 * next retry to arrive too soon. The progressive sequence is still retained for
	 * repeated slow calls, but the measured operation cost can raise the current
	 * window immediately. A later healthy operation calls {@link #recordSuccess()} and
	 * restores the full-rate path without waiting for the old window to expire.
	 *
	 * @param nanos current monotonic timestamp
	 * @param costNanos measured storage-operation duration
	 */
	public void recordSlowOperation(long nanos, long costNanos) {
		if (costNanos <= 500_000L) {
			recordSuccess();
			return;
		}
		backoffExponent++;
		int shift = Math.min(backoffExponent - 1, 5);
		long progressive = Math.min(maxBackoffNs, initialBackoffNs << shift);
		long measured = costNanos >= maxBackoffNs / 2
				? maxBackoffNs
				: Math.min(maxBackoffNs, costNanos * 2L);
		long backoffNanos = Math.max(progressive, measured);
		long jitter = (long) (backoffNanos * JITTER_FACTOR
				* ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
		backoffEndNanos = nanos + backoffNanos + jitter;
	}

	/**
	 * Compatibility entry point for callers that previously requested aggressive backoff.
	 * A transient first rejection now uses the same short progressive window as any other failure.
	 *
	 * @param nanos 当前 System.nanoTime() 值
	 */
	public void recordFailureAggressive(long nanos) {
		// One transient rejection must not park a whole parallel factory.
		recordFailure(nanos);
	}

	/**
	 * 记录推送成功，重置退避。
	 */
	public void recordSuccess() {
		backoffExponent = 0;
		backoffEndNanos = 0;
	}

	/** 完全重置状态（clear 时调用） */
	public void reset() {
		backoffExponent = 0;
		backoffEndNanos = 0;
	}

	/** 获取退避指数（诊断用） */
	public int getBackoffExponent() {
		return backoffExponent;
	}

	/** 获取退避结束时间戳（诊断用） */
	public long getBackoffEndNanos() {
		return backoffEndNanos;
	}
}
