package com.ayoshiko.productivebeesgenesis.mek;

import java.util.function.BiConsumer;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

/**
 * 万象创世日志管理器 — 封装带冷却和抑制计数的日志输出
 * <br/>
 * 从 {@link MyriadCreationsHandler} 抽离，遵循 SRP：
 * 主类聚焦配方处理流程，本类聚焦日志节流与洪水治理。
 * <p>
 * 两级节流：
 * <ul>
 *   <li>每进程节流（fullLogThrottles / emptyCacheLogThrottles）— 单进程日志冷却</li>
 *   <li>全局节流（{@link #globalFullLogThrottle}）— 所有进程共享，5 秒内仅输出首条，
 *       用于 EME 工厂多进程同时失败的日志洪水治理</li>
 * </ul>
 *
 * @since 1.0.0
 */
final class MyriadCreationsLogger {

	/** 日志前缀（区分原版/ME/EME 工厂） */
	private final String logPrefix;

	/** PB 配方处理上下文（用于获取 level 的 gameTime 作为全局节流时间源） */
	private final PbRecipeContext context;

	/** 每进程的"产物无法插入"日志冷却器 */
	private final LogThrottle[] fullLogThrottles;

	/** 全局"产物无法完全插入/流体槽已满"日志冷却器 — 所有进程共享，5 秒内仅输出首条 */
	/** Continuous output backpressure is expected; report it at most once per minute. */
	final LogThrottle globalFullLogThrottle = new LogThrottle(1200L);

	/** 每进程的"类型缓存为空"日志冷却器 */
	private final LogThrottle[] emptyCacheLogThrottles;

	/** 构造日志管理器 */
	MyriadCreationsLogger(String logPrefix, PbRecipeContext context, int processes) {
		this.logPrefix = logPrefix;
		this.context = context;
		this.fullLogThrottles = new LogThrottle[processes];
		this.emptyCacheLogThrottles = new LogThrottle[processes];
		for (int i = 0; i < processes; i++) {
			this.fullLogThrottles[i] = new LogThrottle();
			this.emptyCacheLogThrottles[i] = new LogThrottle();
		}
	}

	/** 缓存为空时记录区分性日志并保留进度：预热未完成 = info，已完成 = warn */
	void logEmptyCacheAndPreserve(int processIndex) {
		if (MyriadCreationsEventHandler.isBeeTypeCacheWarmupComplete()) {
			logThrottledWarn(emptyCacheLogThrottles, processIndex,
					"{}进程{}万象创世类型缓存永久为空（配置过滤过严），保留进度等待配置修正",
					logPrefix, processIndex);
		} else {
			logThrottledInfo(emptyCacheLogThrottles, processIndex,
					"{}进程{}万象创世类型缓存未就绪（预热中），保留进度等待缓存构建",
					logPrefix, processIndex);
		}
	}

	/**
	 * 全局节流 WARN 日志 — 所有进程共享同一 throttle，5 秒内仅输出首条
	 * <br/>
	 * 用于 EME 工厂多进程同时失败的日志洪水治理，首条日志包含 processIndex 和 batchSize 便于定位。
	 *
	 * @param throttle 全局 LogThrottle 实例（通常为 {@link #globalFullLogThrottle}）
	 * @param pattern  日志消息模板（支持 SLF4J {} 占位符）
	 * @param args     占位符参数
	 */
	void logThrottledWarnGlobal(LogThrottle throttle, String pattern, Object... args) {
		long currentTick = context.level() != null ? context.level().getGameTime() : 0L;
		throttle.tryLog(currentTick, suppressed -> {
			if (suppressed > 0) {
				ProductiveBeesGenesis.LOGGER.warn(pattern + " (抑制 {} 条同类日志)", appendArg(args, suppressed));
			} else {
				ProductiveBeesGenesis.LOGGER.warn(pattern, args);
			}
		});
	}

	/** 记录带冷却和抑制计数的日志（通过 logger 参数区分 WARN 与 INFO 级别）— 使用 ms 时间源避免 JDTE 加速下节流失效 */
	private void logThrottled(BiConsumer<String, Object[]> logger, LogThrottle[] throttles, int processIndex, String pattern, Object... args) {
		long currentTimeMs = System.currentTimeMillis();
		LogThrottle throttle = throttles[processIndex];
		if (!throttle.canLogMs(currentTimeMs)) {
			throttle.incrementSuppressed();
			return;
		}
		long suppressed = throttle.loggedMs(currentTimeMs);
		if (suppressed > 0) {
			String mergedPattern = pattern + " (过去冷却期内抑制了 {} 次类似日志)";
			Object[] mergedArgs = new Object[args.length + 1];
			System.arraycopy(args, 0, mergedArgs, 0, args.length);
			mergedArgs[args.length] = suppressed;
			logger.accept(mergedPattern, mergedArgs);
		} else {
			logger.accept(pattern, args);
		}
	}

	private void logThrottledWarn(LogThrottle[] throttles, int processIndex, String pattern, Object... args) {
		logThrottled(ProductiveBeesGenesis.LOGGER::warn, throttles, processIndex, pattern, args);
	}

	private void logThrottledInfo(LogThrottle[] throttles, int processIndex, String pattern, Object... args) {
		logThrottled(ProductiveBeesGenesis.LOGGER::info, throttles, processIndex, pattern, args);
	}

	/** 辅助方法：追加 suppressed 参数到 args 数组末尾 */
	private static Object[] appendArg(Object[] args, long extra) {
		Object[] result = new Object[args.length + 1];
		System.arraycopy(args, 0, result, 0, args.length);
		result[args.length] = extra;
		return result;
	}
}
