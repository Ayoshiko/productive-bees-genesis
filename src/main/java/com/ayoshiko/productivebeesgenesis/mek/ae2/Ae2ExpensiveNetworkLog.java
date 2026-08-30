package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 「昂贵 ME 网络已自适应降级」诊断日志（单一职责：只负责这一类提示的门控与冷却）
 * <p>
 * <b>为什么需要</b>：{@link Ae2StockProbePolicy} 与 {@link Ae2InsertCostTracker} 的降级是静默的 ——
 * 玩家只会看到「机器变慢了但服务器不卡了」，无法判断是哪块存储元件造成的。
 * 输出实测平均成本可以把排查方向直接指向 ME 网络里的昂贵存储
 * （megacells 大宗盘的压缩链线性扫描、ae2lt 样板端口的每次解码、EnderDrives 的 WAL fsync 等）。
 * <p>
 * <b>为什么不用 {@code LogThrottle}</b>：这属于诊断信息而非故障，默认不应出现在玩家日志里。
 * 本类走 {@link DevLog}，因此受 {@code /productivebeesgenesis dev ae2_expensive_network on}
 * 双重门控（主开关 + feature 开关），并在 DevLog 自带 1 秒节流之上再叠加
 * {@link #COOLDOWN_NANOS} 长冷却 —— 降级判定发生在每 tick 每候选键的热路径上，
 * 单靠 1 秒节流仍会在长时间运行中堆积大量重复行。
 * <p>
 * <b>零开销保证</b>：所有入口首行都是 {@link DevModeManager#isLoggingEnabled(String)} 判定，
 * 关闭时（生产环境默认）不做时间读取、不做字符串拼接、不分配 varargs 数组。
 * <p>
 * <b>线程安全</b>：冷却时间戳用 {@link AtomicLong} 的 CAS 更新，即使未来被非 tick 线程调用
 * 也不会出现两条日志同时通过冷却窗口。
 */
final class Ae2ExpensiveNetworkLog {

	/** DevLog feature 名 — 必须与 {@code DevModeCommand.KNOWN_FEATURES} 中的条目一致 */
	static final String FEATURE = "ae2_expensive_network";

	/** 同类提示的最短间隔：60 秒。降级本身每 tick 都在发生，只需周期性提醒一次 */
	private static final long COOLDOWN_NANOS = 60_000_000_000L;

	/** 纳秒 → 微秒换算，日志用 µs 更易读 */
	private static final long NANOS_PER_MICRO = 1_000L;

	private static final AtomicLong lastProbeLogNanos = new AtomicLong(Long.MIN_VALUE);
	private static final AtomicLong lastQuotaLogNanos = new AtomicLong(Long.MIN_VALUE);

	private Ae2ExpensiveNetworkLog() {
	}

	/**
	 * 库存探针被降级（跳过 SIMULATE extract，改用 KeyCounter 计数）时的提示。
	 *
	 * @param averageProbeNanos 探针实测平均耗时（纳秒）
	 */
	static void probeDowngraded(long averageProbeNanos) {
		if (!DevModeManager.isLoggingEnabled(FEATURE)) return;
		if (!tryPass(lastProbeLogNanos)) return;
		DevLog.warn(FEATURE,
				"ME 网络库存探针平均耗时 {}µs 偏高，已跳过非占位库存键的模拟抽取（拉取量改用网络计数）"
						+ " — 请检查网络中是否接入了昂贵存储元件（大宗盘/压缩元件/无限元件/转换接口）",
				averageProbeNanos / NANOS_PER_MICRO);
	}

	/**
	 * insert 键配额被自适应收缩时的提示。
	 *
	 * @param averageInsertNanos insert 实测平均耗时（纳秒）
	 * @param quota              收缩后的本 tick 键配额
	 * @param maxKeys            健康网络下的名义配额
	 */
	static void insertQuotaShrunk(long averageInsertNanos, int quota, int maxKeys) {
		if (!DevModeManager.isLoggingEnabled(FEATURE)) return;
		if (!tryPass(lastQuotaLogNanos)) return;
		DevLog.warn(FEATURE,
				"ME 网络 insert 平均耗时 {}µs 偏高，本 tick 推送键数自适应收缩为 {}/{}"
						+ " — 请检查网络中是否接入了昂贵外部存储（样板存储端口/转换接口/EnderDrives 等）",
				averageInsertNanos / NANOS_PER_MICRO, quota, maxKeys);
	}

	/** CAS 抢占冷却窗口：成功者输出日志，其余直接返回 */
	private static boolean tryPass(AtomicLong slot) {
		long now = System.nanoTime();
		long last = slot.get();
		if (last != Long.MIN_VALUE && now - last < COOLDOWN_NANOS) return false;
		return slot.compareAndSet(last, now);
	}
}
