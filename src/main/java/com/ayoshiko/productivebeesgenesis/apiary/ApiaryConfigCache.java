package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
	 * basic 配置缓存 — 从 {@link BeeSlotTickProcessor} 拆分，将 {@link ApiaryConfigSection}
	 * 的 basic 配置值缓存到 volatile 字段，避免每 tick 高频读取 NeoForge 配置。
	 */
final class ApiaryConfigCache {

	/** 配置缓存刷新间隔（tick） */
	private static final int CONFIG_REFRESH_INTERVAL = 100;

	// ----- basic 配置缓存 -----
	/** 缓存的基础处理时间（tick） */
	private volatile int cachedProcessingTime = 200;

	/** 上次刷新配置的游戏刻 — AtomicLong + CAS 防止多线程重复刷新 */
	private final AtomicLong lastConfigRefreshTick = new AtomicLong(-CONFIG_REFRESH_INTERVAL);

	private ApiaryConfigCache() {
	}

	static ApiaryConfigCache create() {
		return new ApiaryConfigCache();
	}

	int getProcessingTime() {
		return cachedProcessingTime;
	}

	/**
	 * 刷新配置缓存 — 每 {@link #CONFIG_REFRESH_INTERVAL} tick 刷新一次
	 * <br/>
	 * 参考离心机 {@link com.ayoshiko.productivebeesgenesis.mixin.mek.TileComponentEjectorCooldownMixin}
	 * 的配置缓存模式，将 {@link ApiaryConfigSection} 的 basic 和 ejection 配置值缓存到 volatile 字段，
	 * 避免 256× 加速场景下每 tick 高频读取 NeoForge 配置。
	 * <p>
	 * 线程安全：使用 AtomicLong + CAS（compareAndSet）保证「检查时间戳 + 写入新值 + 加载配置」的原子性。
	 * 即使异步线程与主线程同时调用，CAS 也只有一个线程能成功推进时间戳，另一个线程短路返回。
	 *
	 * @param currentTick 当前游戏刻
	 */
	void refresh(long currentTick) {
		long lastRefresh = lastConfigRefreshTick.get();
		if (currentTick - lastRefresh < CONFIG_REFRESH_INTERVAL) {
			return;
		}
		// CAS 推进时间戳：失败说明其他线程已先一步完成刷新，本线程无需重复加载
		if (!lastConfigRefreshTick.compareAndSet(lastRefresh, currentTick)) {
			return;
		}
		// basic 配置（能耗由 MachineEnergyContainer 按 BlockType 的 usage 提供）
		cachedProcessingTime = ModConfig.SERVER.apiaryProcessingTime.get();
	}
}
