package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AE2 全局配置缓存（纯状态，单实例）
 * <br/>
 * 从 {@link Ae2OutputStateHolder} 拆分而来，职责（SRP）：缓存与 AE2 输出/输入/
 * 能量注入相关的全局配置项，避免高频读取 ModConfig.SERVER（墙钟 5 秒刷新一次）。
 * <p>
 * 线程安全：AtomicLong + CAS 保证「检查时间戳 + 写入新值 + 加载配置」原子性；
 * 其余字段为 volatile。
 */
final class Ae2ConfigCache {

	/** 配置缓存刷新间隔（毫秒） — wall clock 避免 JDTE 加速下 getGameTime 不变导致缓存永不过期 */
	private static final long CONFIG_REFRESH_INTERVAL_MS = 5000L;

	/** 上次刷新配置的墙钟时间戳（ms） — AtomicLong + CAS 防止多线程重复刷新 */
	private final AtomicLong lastConfigRefreshMs = new AtomicLong(-CONFIG_REFRESH_INTERVAL_MS);

	/** 缓存的 AE2 输出推送开关（默认 false） */
	private volatile boolean cachedOutputPushEnabled = false;

	/** 缓存的 AE2 流体推送开关（默认 true，与配置未加载时回退一致） */
	private volatile boolean cachedFluidPushEnabled = true;

	/** 缓存的 AppliedFlux 优先开关（默认 false） */
	private volatile boolean cachedPreferAppliedFluxOverAeEnergy = false;

	/** 缓存的 AE2 原生能量提取开关（默认 true；AppliedFlux 未加载时配置为 null 回退 true，此时原生是唯一能量源） */
	private volatile boolean cachedNativeEnergyInputEnabled = true;

	/** 缓存的 AE2 输入拉取开关（默认 false） */
	private volatile boolean cachedInputPullEnabled = false;

	/** 缓存的 AE2 能量输入开关（默认 false） */
	private volatile boolean cachedEnergyInputEnabled = false;

	private volatile int cachedInputRatePerTick = 64;
	private volatile int cachedInputIntervalTicks = 20;

	/** 配置缓存刷新异常日志冷却器（ms 模式，避免高频刷新下刷屏） */
	private final LogThrottle configReadThrottle = new LogThrottle();

	/** 判断配置缓存是否过期（当前时间戳与上次刷新差 >= 刷新间隔） */
	boolean isStale() {
		return System.currentTimeMillis() - lastConfigRefreshMs.get() >= CONFIG_REFRESH_INTERVAL_MS;
	}

	/**
	 * 刷新 AE2 配置缓存 — 每 CONFIG_REFRESH_INTERVAL_MS ms 刷新一次。
	 * <br/>
	 * AtomicLong + CAS 保证「检查时间戳 + 写入新值 + 加载配置」原子性；
	 * 配置字段 null 时按原方法默认值回退。wall clock 避免 JDTE 加速下
	 * getGameTime 在同一 gameTick 内不变导致缓存永不过期。
	 */
	void refresh() {
		long currentTimeMs = System.currentTimeMillis();
		long lastRefresh = lastConfigRefreshMs.get();
		if (currentTimeMs - lastRefresh < CONFIG_REFRESH_INTERVAL_MS) {
			return;
		}
		// ModConfig.SERVER 为 null 时（reload 期间）不推进时间戳，
		// 允许下次调用立即重试，避免最长 5 秒的配置不可见窗口
		if (ModConfig.SERVER == null) return;
		// CAS 推进时间戳：失败说明其他线程已先一步完成刷新，本线程无需重复加载
		if (!lastConfigRefreshMs.compareAndSet(lastRefresh, currentTimeMs)) {
			return;
		}
		try {
			// mekCentrifugeAeOutputEnabled 为 null（AE2 未加载）时回退 false
			cachedOutputPushEnabled = ModConfig.SERVER.mekCentrifugeAeOutputEnabled != null
					&& ModConfig.SERVER.mekCentrifugeAeOutputEnabled.get();
			// mekCentrifugeAeFluidOutputEnabled 为 null（AE2 未加载）时回退 true
			cachedFluidPushEnabled = ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled == null
					|| ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.get();
			// mekCentrifugePreferAppliedFluxOverAeEnergy 为 null（AppliedFlux 未加载）时回退 false
			cachedPreferAppliedFluxOverAeEnergy = ModConfig.SERVER.mekCentrifugePreferAppliedFluxOverAeEnergy != null
					&& ModConfig.SERVER.mekCentrifugePreferAppliedFluxOverAeEnergy.get();
			// mekCentrifugeAeNativeEnergyInputEnabled 为 null（AppliedFlux 未加载）时回退 true（原生是唯一能量源）
			cachedNativeEnergyInputEnabled = ModConfig.SERVER.mekCentrifugeAeNativeEnergyInputEnabled == null
					|| ModConfig.SERVER.mekCentrifugeAeNativeEnergyInputEnabled.get();
			// mekCentrifugeAeInputEnabled 为 null（AE2 未加载）时回退 false
			cachedInputPullEnabled = ModConfig.SERVER.mekCentrifugeAeInputEnabled != null
					&& ModConfig.SERVER.mekCentrifugeAeInputEnabled.get();
			cachedEnergyInputEnabled = ModConfig.SERVER.mekCentrifugeAeEnergyInputEnabled != null
					&& ModConfig.SERVER.mekCentrifugeAeEnergyInputEnabled.get();
			cachedInputRatePerTick = ModConfig.SERVER.mekCentrifugeAeInputRatePerTick == null
					? 64 : Math.max(1, ModConfig.SERVER.mekCentrifugeAeInputRatePerTick.get());
			cachedInputIntervalTicks = ModConfig.SERVER.mekCentrifugeAeInputIntervalTicks == null
					? 20 : Math.max(1, ModConfig.SERVER.mekCentrifugeAeInputIntervalTicks.get());
		} catch (LinkageError | RuntimeException t) {
			// LinkageError 覆盖配置版本不兼容；RuntimeException 覆盖配置读取异常。
			// 不捕获 Throwable 以避免吞没 OOM 等严重错误。
			// 配置读取异常，保持当前缓存值，节流记录 WARN 便于调试
			final Throwable cause = t;
			configReadThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
				ProductiveBeesGenesis.LOGGER.warn("AE2 配置缓存刷新异常，保持当前缓存值"
						+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), cause);
			});
		}
	}

	boolean isOutputPushEnabled() { return cachedOutputPushEnabled; }
	boolean isFluidPushEnabled() { return cachedFluidPushEnabled; }
	boolean isPreferAppliedFluxOverAeEnergy() { return cachedPreferAppliedFluxOverAeEnergy; }
	boolean isNativeEnergyInputEnabled() { return cachedNativeEnergyInputEnabled; }
	boolean isInputPullEnabled() { return cachedInputPullEnabled; }
	boolean isEnergyInputEnabled() { return cachedEnergyInputEnabled; }
	int getInputRatePerTick() { return cachedInputRatePerTick; }
	int getInputIntervalTicks() { return cachedInputIntervalTicks; }

	/** 重置为初始默认值（方块重建时调用，确保不残留旧配置） */
	void reset() {
		// P0-4 修复：cachedPreferAppliedFluxOverAeEnergy 必须与字段声明默认值 (false) 一致，
		// 原代码重置为 true 会导致方块重建后首次配置读取前误判 AppliedFlux 优先
		cachedOutputPushEnabled = false;
		cachedFluidPushEnabled = true;
		cachedPreferAppliedFluxOverAeEnergy = false;
		cachedNativeEnergyInputEnabled = true;
		cachedInputPullEnabled = false;
		cachedEnergyInputEnabled = false;
		cachedInputRatePerTick = 64;
		cachedInputIntervalTicks = 20;
		lastConfigRefreshMs.set(-CONFIG_REFRESH_INTERVAL_MS);
	}
}
