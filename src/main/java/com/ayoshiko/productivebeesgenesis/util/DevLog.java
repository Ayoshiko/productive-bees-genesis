package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
	 * 开发者模式日志门面
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP（单一职责）：仅负责日志输出门面，不持有任何业务状态</li>
	 *   <li>DIP（依赖倒置）：依赖 {@link DevModeManager} 抽象判断是否输出，
	 *       而非直接读取 volatile 字段，便于未来替换状态源</li>
	 * </ul>
	 * <p>
	 * 性能考量：
	 * <ul>
	 *   <li>开发者模式关闭时，{@code "[DEV][" + feature + "] "} 字符串拼接仍会发生（轻量），
	 *       但 SLF4J 参数化避免 message 内 args 的 toString 调用</li>
	 *   <li>错误日志（{@link #error(String, Object...)} / {@link #error(String, Throwable)}）
	 *       无条件输出，确保异常不被静默吞没；错误级别独立于 {@link LogThrottle}，不参与节流</li>
	 *   <li>节流机制：相同 feature+message 在 {@link #THROTTLE_MS} 内只输出首次，
	 *       避免高频 tick 日志（如 resolveFluidOutput）刷屏卡顿</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：节流判定使用 {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
	 * 保证 check-and-update 原子性，避免并发线程同时通过节流守卫导致重复输出。
	 * <p>
	 * <b>message 参数约束（重要）</b>：{@code message} 参数必须为常量模板字符串，
	 * 禁止包含动态拼接内容（如坐标、UUID、时间戳等），否则 {@link #lastLogTime} map
	 * 将因 key 无限增长而内存泄漏。动态内容请通过 SLF4J {@code {}} 占位符传入 args。
	 * <p>
	 * feature 参数示例："recipe_reload"、"config_apply"、"filter_list"、"bee_register" 等。
	 * 与 {@link DevModeManager#isLoggingEnabled(String)} 配合实现粒度化日志开关。
	 */
public final class DevLog {

	/** 节流时间窗口(ms) — 相同 feature+message 在此时间内只输出首次 */
	private static final int THROTTLE_MS = 1000;

	/** 节流缓存 — key = feature + ":" + messageHash, value = 上次输出时间戳 */
	private static final ConcurrentHashMap<String, Long> lastLogTime = new ConcurrentHashMap<>();

	/** 工具类禁止实例化 */
	private DevLog() {
	}

	/**
	 * 检查日志是否应被节流(相同 feature+message 在 THROTTLE_MS 内只输出首次)
	 * <p>
	 * <b>线程安全</b>：使用 {@link ConcurrentHashMap#compute} 保证 check-and-update 原子性，
	 * 避免并发线程同时通过 get 守卫后都 put 并返回 true 导致节流失效。
	 * 节流期内保留旧时间戳（不延长节流窗口），保持原语义。
	 *
	 * @param feature 功能标识
	 * @param message 日志消息
	 * @return true 如果应输出(未被节流);false 如果应抑制(1000ms 内已输出过相同日志)
	 */
	private static boolean shouldLog(String feature, String message) {
		// 使用 message.hashCode() 基于内容判定，相同内容字符串返回相同 hash
		String key = feature + ":" + message.hashCode();
		long now = System.currentTimeMillis();
		AtomicBoolean shouldOutput = new AtomicBoolean(false);
		lastLogTime.compute(key, (k, lastTime) -> {
			if (lastTime != null && (now - lastTime) < THROTTLE_MS) {
				return lastTime; // 节流：保留旧时间戳，不延长窗口
			}
			shouldOutput.set(true);
			return now; // 输出：更新时间戳
		});
		return shouldOutput.get();
	}

	/**
	 * 输出 INFO 级别开发日志（受 feature 开关控制）
	 *
	 * @param feature 功能标识（如 "recipe_reload"），由 {@link DevModeManager#isLoggingEnabled(String)} 判断
	 * @param message 日志消息（支持 SLF4J {} 占位符）
	 * @param args    占位符参数
	 */
	public static void info(String feature, String message, Object... args) {
		if (!DevModeManager.isLoggingEnabled(feature)) {
			return;
		}
		if (!shouldLog(feature, message)) {
			return;
		}
		Logger logger = ProductiveBeesGenesis.LOGGER;
		logger.info("[DEV][" + feature + "] " + message, args);
	}

	/**
	 * 输出 DEBUG 级别开发日志（受 feature 开关控制）
	 * <p>
	 * 用于诊断阶段的细粒度日志（如按钮点击坐标、DataSlot 索引、流体槽查找等）。
	 * 与 {@link #info(String, String, Object...)} 区别：DEBUG 级别在日志配置中默认可能不输出,
	 * 需启用 DEBUG 级别才能看到;但受 feature 开关控制,开启 DEV 模式后默认输出。
	 *
	 * @param feature 功能标识（如 "ae2_input"、"upgrade_slot"、"fluid_tank"），由 {@link DevModeManager#isLoggingEnabled(String)} 判断
	 * @param message 日志消息（支持 SLF4J {} 占位符）
	 * @param args    占位符参数
	 */
	public static void debug(String feature, String message, Object... args) {
		if (!DevModeManager.isLoggingEnabled(feature)) {
			return;
		}
		if (!shouldLog(feature, message)) {
			return;
		}
		Logger logger = ProductiveBeesGenesis.LOGGER;
		logger.debug("[DEV][" + feature + "] " + message, args);
	}

	/**
	 * 输出 WARN 级别开发日志（受 feature 开关控制）
	 *
	 * @param feature 功能标识（如 "config_apply"），由 {@link DevModeManager#isLoggingEnabled(String)} 判断
	 * @param message 日志消息（支持 SLF4J {} 占位符）
	 * @param args    占位符参数
	 */
	public static void warn(String feature, String message, Object... args) {
		if (!DevModeManager.isLoggingEnabled(feature)) {
			return;
		}
		if (!shouldLog(feature, message)) {
			return;
		}
		Logger logger = ProductiveBeesGenesis.LOGGER;
		logger.warn("[DEV][" + feature + "] " + message, args);
	}

	/**
	 * 输出 ERROR 级别日志（无条件输出，错误必须可见）
	 *
	 * @param message 日志消息（支持 SLF4J {} 占位符）
	 * @param args    占位符参数
	 */
	public static void error(String message, Object... args) {
		ProductiveBeesGenesis.LOGGER.error(message, args);
	}

	/**
	 * 输出 ERROR 级别日志并附带异常堆栈（无条件输出）
	 *
	 * @param message 日志消息
	 * @param t       异常对象
	 */
	public static void error(String message, Throwable t) {
		ProductiveBeesGenesis.LOGGER.error(message, t);
	}
}
