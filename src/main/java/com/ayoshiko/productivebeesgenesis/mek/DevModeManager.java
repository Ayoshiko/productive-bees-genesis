package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发者模式服务端内存状态管理
 * <p>
 * 主开关 + 子功能开关。状态仅存在于内存，服务器重启后重置为关闭。
 * 通过 /productivebeesgenesis dev 命令控制。
 * <p>
 * 职责分离（日志 vs 非日志）：
 * <ul>
 *   <li>{@link #isEnabled()} / {@link #isEnabled(String)}：非日志场景（如创造标签页开发物品可见性），
 *       feature 默认关闭，需显式开启</li>
 *   <li>{@link #isLoggingEnabled()} / {@link #isLoggingEnabled(String)}：日志场景，
 *       feature 必须显式注册为 true 才输出日志，默认关闭以减少生产环境 I/O 开销</li>
 * </ul>
 * <p>
 * 粒度化 feature 设计：
 * 未来可通过 {@code /productivebeesgenesis dev <feature> on} 单独开启某个 feature 的日志
 * （例如开启 "recipe_reload" 但保留其他默认关闭），而不影响该 feature 的非日志行为。
 */
public final class DevModeManager {

	/** 主开关（默认关闭） */
	private static volatile boolean masterEnabled = false;

	/** 子功能开关（key=featureName, value=enabled） */
	private static final ConcurrentHashMap<String, Boolean> featureFlags = new ConcurrentHashMap<>();

	private DevModeManager() {
		// 工具类禁止实例化
	}

	/** 返回主开关状态 */
	public static boolean isEnabled() {
		return masterEnabled;
	}

	/**
	 * 返回子功能状态。主开关关闭时始终返回 false。
	 * <p>
	 * 用于非日志场景（如创造标签页），feature 默认关闭。
	 */
	public static boolean isEnabled(String feature) {
		if (!masterEnabled) return false;
		return featureFlags.getOrDefault(feature, false);
	}

	/**
	 * 日志主开关状态。主开关关闭时返回 false，开启时返回 true。
	 * <p>
	 * 与 {@link #isEnabled()} 职责分离：本方法仅用于 {@link com.ayoshiko.productivebeesgenesis.util.DevLog}
	 * 等日志门面判断，不影响创造标签页等非日志行为。
	 */
	public static boolean isLoggingEnabled() {
		return masterEnabled;
	}

	/**
	 * 返回指定 feature 的日志开关。主开关关闭时始终返回 false；主开关开启时
	 * 查询 {@code featureFlags}，默认返回 false（feature 必须显式注册为 true 才输出日志）。
	 * <p>
	 * 默认关闭以减少生产环境 I/O 开销：避免主开关开启后所有 feature 日志一并输出。
	 * 与 {@link #isEnabled(String)} 区别：本方法用于日志场景，后者用于非日志功能行为。
	 * 可单独开启某 feature 日志而不影响其功能行为。
	 *
	 * @param feature 功能标识（如 "recipe_reload"、"config_apply"、"filter_list"）
	 */
	public static boolean isLoggingEnabled(String feature) {
		if (!masterEnabled) return false;
		return featureFlags.getOrDefault(feature, false);
	}

	/** 设置主开关 */
	public static void setEnabled(boolean enabled) {
		masterEnabled = enabled;
	}

	/** 设置子功能开关 */
	public static void setEnabled(String feature, boolean enabled) {
		featureFlags.put(feature, enabled);
	}

	/**
	 * 返回所有子功能状态快照（用于 status 命令和 packet 同步）。
	 */
	public static Map<String, Boolean> getFeatureStates() {
		return new ConcurrentHashMap<>(featureFlags);
	}
}
