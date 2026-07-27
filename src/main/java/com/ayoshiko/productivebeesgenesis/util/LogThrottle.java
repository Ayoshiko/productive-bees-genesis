package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 通用日志冷却器 — 限流高频日志，避免输出阻塞时刷屏拖慢 TPS
 * <br/>
 * 支持两种时间源：
 * <ul>
 *   <li><b>tick 模式</b>：基于 {@code Level.getGameTime()}，适用于方块实体 tick 逻辑</li>
 *   <li><b>ms 模式</b>：基于 {@code System.currentTimeMillis()}，适用于静态上下文无法获取 Level 的场景</li>
 * </ul>
 * 单个实例应只使用一种模式（tick 或 ms），共享的抑制计数仅在该模式下语义有效。
 * <p>
 * <b>线程安全</b>：使用 {@link AtomicLong} 记录上次日志时间与抑制计数，
 * 单字段读写原子。服务端单线程执行场景下 AtomicLong 作为可见性保证与安全冗余。
 * <p>
 * <b>静态便捷方法</b>：{@link #warn(String, String, Object...)} / {@link #error(String, String, Object...)}
 * 使用全局静态 {@link ConcurrentHashMap} 按 key 节流，实现多 tile 全局节流（非实例级）。
 * 使用 {@link System#nanoTime()} 单调时钟计时（与 Ae2PushBackoff 一致）。
 * <p>
 * <b>静态 API 线程安全</b>：节流判定使用 {@link ConcurrentHashMap#compute} 保证 check-and-update 原子性，
 * 避免并发线程同时通过守卫导致重复输出。日志 I/O 在 compute 之外执行，不持有 bin 锁。
 *
 * @since 1.0.0
 */
public class LogThrottle {

	// ===== 静态全局节流 API（多 tile 共享） =====

	/** 静态节流冷却间隔（毫秒） — 5000ms = 5 秒 */
	private static final long COOLDOWN_MS = 5000L;

	/** 静态节流冷却间隔（纳秒） — COOLDOWN_MS * 1_000_000 */
	private static final long COOLDOWN_NS = COOLDOWN_MS * 1_000_000L;

	/** 全局静态节流缓存 — key = 业务标识，value = 上次输出时间戳（nanoTime） */
	private static final ConcurrentHashMap<String, Long> lastLogTimeNanos = new ConcurrentHashMap<>();

	/** 全局调用计数器 — 用于触发惰性清理（每 64 次调用清理一次过期 key） */
	private static final AtomicLong callCounter = new AtomicLong(0);

	/** 惰性清理触发频率（每 N 次调用触发一次） */
	private static final long LAZY_CLEAN_INTERVAL = 64L;

	/** 过期阈值 — 超过此时长未使用的 key 将被惰性清理（5 分钟） */
	private static final long EXPIRY_NS = TimeUnit.MINUTES.toNanos(5);

	/**
	 * 惰性清理 — 每 {@value #LAZY_CLEAN_INTERVAL} 次调用触发一次，移除超过 5 分钟未使用的 key
	 * <br/>
	 * 防止极端场景下 lastLogTimeNanos 因动态 key 泄漏导致无限增长。
	 * ConcurrentHashMap.entrySet().removeIf 为线程安全操作。
	 */
	private static void lazyCleanup() {
		if (callCounter.incrementAndGet() % LAZY_CLEAN_INTERVAL == 0) {
			long cutoff = System.nanoTime() - EXPIRY_NS;
			lastLogTimeNanos.entrySet().removeIf(e -> e.getValue() < cutoff);
		}
	}

	/**
	 * 清空所有节流缓存 — 在服务器停止时调用，防止单机多次切换存档时的状态残留
	 */
	public static void clearAll() {
		lastLogTimeNanos.clear();
	}

	/**
	 * 静态 WARN 级别日志 — 5 秒内同 key 仅输出首次 WARN（多 tile 全局节流）
	 * <p>
	 * 用于多 tile 同类异常的统一节流，避免 256x 工厂场景下同一错误刷屏。
	 * key 应为常量字符串（如 "ae2_fluid_backoff"），message 应为常量模板字符串。
	 *
	 * @param key     节流键（必须为常量，否则 lastLogTimeNanos map 将无限增长）
	 * @param message 日志消息（支持 SLF4J {} 占位符）
	 * @param args    占位符参数
	 */
	public static void warn(String key, String message, Object... args) {
		lazyCleanup();
		warnWithCooldown(key, COOLDOWN_MS, message, args);
	}

	/**
	 * 静态 WARN 级别日志 — 自定义冷却时间内同 key 仅输出首次 WARN（多 tile 全局节流）
	 * <p>
	 * 用于需要更长节流周期的场景（如深度诊断在 256× 加速下需要 10 秒节流）。
	 * 与 {@link #warn(String, String, Object...)} 共享 {@link #lastLogTimeNanos} 缓存，
	 * 同一 key 的两种调用会互相影响 lastNanos（但实际使用中每个 key 只用一种冷却，安全）。
	 * <p>
	 * <b>线程安全</b>：使用 {@link ConcurrentHashMap#compute} 保证 check-and-update 原子性，
	 * 日志 I/O 在 compute 之外执行，不持有 bin 锁。
	 *
	 * @param key        节流键（必须为常量）
	 * @param cooldownMs 自定义冷却间隔（毫秒）
	 * @param message    日志消息（支持 SLF4J {} 占位符）
	 * @param args       占位符参数
	 */
	public static void warnWithCooldown(String key, long cooldownMs, String message, Object... args) {
		lazyCleanup();
		long now = System.nanoTime();
		long cooldownNs = cooldownMs * 1_000_000L;
		AtomicBoolean shouldOutput = new AtomicBoolean(false);
		lastLogTimeNanos.compute(key, (k, last) -> {
			if (last == null || (now - last) >= cooldownNs) {
				shouldOutput.set(true);
				return now;
			}
			return last; // 节流：保留旧时间戳
		});
		if (shouldOutput.get()) {
			Logger logger = ProductiveBeesGenesis.LOGGER;
			logger.warn(message, args);
		}
	}

	/**
	 * 静态 ERROR 级别日志 — 5 秒内同 key 仅输出首次 ERROR（多 tile 全局节流）
	 * <p>
	 * 用于多 tile 同类错误的统一节流，避免 256x 工厂场景下同一错误刷屏。
	 * key 应为常量字符串（如 "ae2_push_permanent_failure"），message 应为常量模板字符串。
	 * <p>
	 * <b>线程安全</b>：使用 {@link ConcurrentHashMap#compute} 保证 check-and-update 原子性，
	 * 日志 I/O 在 compute 之外执行，不持有 bin 锁。
	 *
	 * @param key     节流键（必须为常量，否则 lastLogTimeNanos map 将无限增长）
	 * @param message 日志消息（支持 SLF4J {} 占位符）
	 * @param args    占位符参数
	 */
	public static void error(String key, String message, Object... args) {
		lazyCleanup();
		long now = System.nanoTime();
		AtomicBoolean shouldOutput = new AtomicBoolean(false);
		lastLogTimeNanos.compute(key, (k, last) -> {
			if (last == null || (now - last) >= COOLDOWN_NS) {
				shouldOutput.set(true);
				return now;
			}
			return last; // 节流：保留旧时间戳
		});
		if (shouldOutput.get()) {
			Logger logger = ProductiveBeesGenesis.LOGGER;
			logger.error(message, args);
		}
	}

	/** 默认冷却间隔（tick） — 100 tick = 5 秒 */
	public static final long DEFAULT_COOLDOWN_TICKS = 100L;

	/** 默认冷却间隔（毫秒） — 5000ms = 5 秒 */
	public static final long DEFAULT_COOLDOWN_MS = 5000L;

	/** 冷却间隔（tick） */
	private final long cooldownTicks;

	/** 冷却间隔（毫秒） */
	private final long cooldownMs;

	/** 上次日志记录的游戏刻（-1 表示未记录） */
	private final AtomicLong lastLogTick = new AtomicLong(-1L);

	/** 上次日志记录的墙钟时间戳（-1 表示未记录） */
	private final AtomicLong lastLogMs = new AtomicLong(-1L);

	/** 冷却期内被抑制的日志计数（tick/ms 模式共享，单实例单模式使用） */
	private final AtomicLong suppressedCount = new AtomicLong(0L);

	/** 使用默认冷却间隔（tick 100 / ms 5000）构造冷却器 */
	public LogThrottle() {
		this(DEFAULT_COOLDOWN_TICKS);
	}

	/**
	 * 指定 tick 冷却间隔构造冷却器，ms 冷却间隔取默认值
	 *
	 * @param cooldownTicks 冷却间隔（tick）
	 */
	public LogThrottle(long cooldownTicks) {
		this.cooldownTicks = cooldownTicks;
		this.cooldownMs = DEFAULT_COOLDOWN_MS;
	}

	/**
	 * 同时指定 tick 与 ms 冷却间隔构造冷却器
	 *
	 * @param cooldownTicks 冷却间隔（tick）
	 * @param cooldownMs    冷却间隔（毫秒）
	 */
	public LogThrottle(long cooldownTicks, long cooldownMs) {
		this.cooldownTicks = cooldownTicks;
		this.cooldownMs = cooldownMs;
	}

	// ===== tick 模式 API =====

	/**
	 * 检查是否可以记录日志（冷却期已过） — tick 模式
	 *
	 * @param currentTick 当前游戏刻
	 * @return true 如果冷却期已过或首次记录
	 */
	public boolean canLog(long currentTick) {
		long last = lastLogTick.get();
		return last < 0 || (currentTick - last) >= cooldownTicks;
	}

	/**
	 * 记录已输出日志（更新上次日志时间，重置抑制计数） — tick 模式
	 *
	 * @param currentTick 当前游戏刻
	 * @return 重置前的抑制计数（0 表示无抑制）
	 */
	public long logged(long currentTick) {
		lastLogTick.set(currentTick);
		return suppressedCount.getAndSet(0L);
	}

	/**
	 * 尝试输出日志 — tick 模式
	 * <br/>
	 * 冷却期已过则执行 logger 并返回 true，否则增加抑制计数并返回 false。
	 *
	 * @param currentTick 当前游戏刻
	 * @param logger      日志消费者，参数为被抑制的次数
	 * @return 是否实际输出了日志
	 */
	public boolean tryLog(long currentTick, Consumer<Long> logger) {
		if (canLog(currentTick)) {
			long suppressed = logged(currentTick);
			logger.accept(suppressed);
			return true;
		} else {
			incrementSuppressed();
			return false;
		}
	}

	// ===== ms 模式 API（用于静态上下文） =====

	/**
	 * 检查是否可以记录日志（冷却期已过） — ms 模式
	 *
	 * @param currentTimeMs 当前墙钟时间戳（毫秒）
	 * @return true 如果冷却期已过或首次记录
	 */
	public boolean canLogMs(long currentTimeMs) {
		long last = lastLogMs.get();
		return last < 0 || (currentTimeMs - last) >= cooldownMs;
	}

	/**
	 * 记录已输出日志（更新上次日志时间，重置抑制计数） — ms 模式
	 *
	 * @param currentTimeMs 当前墙钟时间戳（毫秒）
	 * @return 重置前的抑制计数（0 表示无抑制）
	 */
	public long loggedMs(long currentTimeMs) {
		lastLogMs.set(currentTimeMs);
		return suppressedCount.getAndSet(0L);
	}

	/**
	 * 尝试输出日志 — ms 模式
	 * <br/>
	 * 冷却期已过则执行 logger 并返回 true，否则增加抑制计数并返回 false。
	 * 适用于无法获取 game tick 的静态上下文，传入 {@code System.currentTimeMillis()}。
	 *
	 * @param currentTimeMs 当前墙钟时间戳（毫秒）
	 * @param logger        日志消费者，参数为被抑制的次数
	 * @return 是否实际输出了日志
	 */
	public boolean tryLogMs(long currentTimeMs, Consumer<Long> logger) {
		if (canLogMs(currentTimeMs)) {
			long suppressed = loggedMs(currentTimeMs);
			logger.accept(suppressed);
			return true;
		} else {
			incrementSuppressed();
			return false;
		}
	}

	// ===== 共享 API =====

	/** 增加被抑制的日志计数（当 canLog 返回 false 时调用） */
	public void incrementSuppressed() {
		suppressedCount.incrementAndGet();
	}

	/** 获取当前被抑制的日志数量（不重置） */
	public long getSuppressedCount() {
		return suppressedCount.get();
	}
}
