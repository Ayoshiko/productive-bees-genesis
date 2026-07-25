package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * Per-tile 指数退避状态管理器（AE2 推送器），基于 {@link System#nanoTime()} 单调时钟。
 * <br/>
 * 每个 tile 独立持有 fluidBackoff 和 itemBackoff 两个实例。
 * 失败时进入退避窗口（1s→2s→4s→8s→16s→30s），窗口内跳过所有 AE2 存储操作。
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
 * <p>
 * <b>MAX_BACKOFF_NS=30s 依据</b>：
 * (1) AE2 网络 storage cell 冷却/重连典型耗时 5-30s；
 * (2) 30s 内由 MEK Ejector 兜底输出；
 * (3) 与 Ae2InputPuller 的 mekCentrifugeAeInputIntervalTicks（默认 20t=1s）拉开 30 倍差距。
 *
 * @since 1.0.0
 */
public final class Ae2PushBackoff {

	/** 初始退避纳秒（1 秒） */
	private static final long INITIAL_BACKOFF_NS = 1_000_000_000L;

	/** 最大退避纳秒（30 秒） */
	private static final long MAX_BACKOFF_NS = 30_000_000_000L;

	/** 退避结束时间戳（System.nanoTime()，0 表示无退避） */
	private long backoffEndNanos;

	/** 退避指数（初始 0，首次失败后变 1） */
	private int backoffExponent;

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
	 * 记录推送失败，退避窗口指数增长（1s→2s→4s→8s→16s→30s）。
	 * <br/>
	 * 退避序列：1s(1&lt;&lt;0) → 2s(1&lt;&lt;1) → 4s(1&lt;&lt;2) → 8s(1&lt;&lt;3) → 16s(1&lt;&lt;4) → 30s(min(30s, 1&lt;&lt;5=32s)) → 30s → ...
	 *
	 * @param nanos 当前 System.nanoTime() 值
	 */
	public void recordFailure(long nanos) {
		backoffExponent++;
		// 移位钳制：1<<5=32s 已超过 MAX_BACKOFF_NS(30s)，更大的 shift 必然被 Math.min 钳制；
		// 同时防止 backoffExponent 过大时移位溢出为负值绕过 Math.min
		int shift = Math.min(backoffExponent - 1, 5);
		long backoffNanos = Math.min(MAX_BACKOFF_NS, INITIAL_BACKOFF_NS << shift);
		backoffEndNanos = nanos + backoffNanos;
	}

	/**
	 * 激进退避：直接跳到最大退避窗口（30s），跳过 1s→2s→4s→8s→16s 渐进过程。
	 * <br/>
	 * 用于 256× 加速下 Grid 不稳定时的首次失败或空存储检测，避免高频无效重试加剧 TPS 负载。
	 * 调用后 backoffExponent 设为 5（与正常 recordFailure 6+ 次失败的级别一致），
	 * 后续若再次失败，recordFailure 会 ++ 到 6，shift 仍为 5，保持 30s。
	 *
	 * @param nanos 当前 System.nanoTime() 值
	 */
	public void recordFailureAggressive(long nanos) {
		backoffExponent = 5;
		backoffEndNanos = nanos + MAX_BACKOFF_NS;
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
