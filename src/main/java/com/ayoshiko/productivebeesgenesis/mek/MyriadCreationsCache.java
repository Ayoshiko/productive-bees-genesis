package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.world.level.Level;

/**
 * 万象创世缓存与过滤管理器
 * <br/>
 * 从 {@link MyriadCreationsHandler} 抽离，遵循 SRP：
 * 主类聚焦配方处理流程，本类聚焦缓存生命周期与输出空间判断。
 * <p>
 * 管理两类缓存：
 * <ul>
 *   <li>ticksForBase 时间窗口缓存（1 秒失效，使用墙钟时间适配 JDTE 加速）</li>
 *   <li>maxOpsPerTick 配置缓存（100 tick 刷新，对齐 PbRecipeProcessor）</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：缓存值字段为 volatile（单字段读写原子，保证可见性）；
 * check-then-update 临界区使用 {@code synchronized(this)} 守卫，避免并发线程
 * 同时通过守卫导致重复计算/配置读取，或与 clear 交错导致脏值。
 *
 * @since 1.0.0
 */
final class MyriadCreationsCache {

	/** getTicksForBase 缓存失效间隔（毫秒） — 使用 wall clock 避免 JDTE 加速下 getGameTime 不变导致缓存失效 */
	static final long TICKS_CACHE_INTERVAL_MS = 1000L;

	/** maxOpsPerTick 配置缓存刷新间隔（tick） */
	static final int MAX_OPS_REFRESH_INTERVAL = 100;

	/** 缓存的 getTicksForBase(baseTicksRequired) 结果 — 升级变更后最多 1 秒内自动反映新值 */
	private volatile int cachedTicksForBase = -1;

	/** 上次计算 cachedTicksForBase 时的墙钟时间戳（ms，-1 表示未计算） */
	private volatile long cachedTicksForBaseAtMs = -1L;

	/** maxOpsPerTick 配置缓存值（每 100 tick 刷新） */
	private volatile int maxOpsConfigCached = 0;

	/** 上次刷新 maxOpsPerTick 配置缓存的游戏刻 */
	private volatile long maxOpsConfigLastTick = -MAX_OPS_REFRESH_INTERVAL;

	/**
	 * 获取缓存的 getTicksForBase 结果（时间窗口缓存）
	 * <br/>
	 * 升级变更后最多 1 秒内自动反映新值。
	 * <p>
	 * <b>JDTE 适配</b>：使用 {@link System#currentTimeMillis()} 作为时间源，
	 * 避免 JDTE 加速下 {@code level.getGameTime()} 在同一 gameTick 内不变导致缓存窗口判断失效。
	 * <p>
	 * <b>线程安全</b>：{@code synchronized(this)} 守卫 check-then-update 临界区，
	 * 与 {@link #clearCachedTicksForBase()} 共用锁，避免并发刷新或与清除交错导致脏值。
	 *
	 * @param context PB 配方处理上下文
	 * @return 受速度升级影响的 baseTicksRequired 处理时间
	 */
	synchronized int getCachedTicksForBase(PbRecipeContext context) {
		long currentTimeMs = System.currentTimeMillis();
		if (cachedTicksForBase < 0 || (currentTimeMs - cachedTicksForBaseAtMs) >= TICKS_CACHE_INTERVAL_MS) {
			cachedTicksForBase = context.getTicksForBase(context.baseTicksRequired());
			cachedTicksForBaseAtMs = currentTimeMs;
		}
		return cachedTicksForBase;
	}

	/**
	 * 配方重载时失效 cachedTicksForBase（由 PbRecipeProcessor.checkRecipeVersion 经主类委托调用）
	 * <p>
	 * <b>线程安全</b>：与 {@link #getCachedTicksForBase} 共用 {@code synchronized(this)} 锁。
	 */
	synchronized void clearCachedTicksForBase() {
		cachedTicksForBase = -1;
	}

	/**
	 * 刷新并获取 maxOpsPerTick 配置缓存（每 100 tick 刷新）
	 * <br/>
	 * SubTask 5.1: maxOpsPerTick 配置 100-tick CAS 缓存，对齐 PbRecipeProcessor:261-264。
	 * 审查问题修复：ModConfig.SERVER 在 reload 期间可能为 null，守卫后保留上次缓存值。
	 * <p>
	 * <b>线程安全</b>：{@code synchronized(this)} 守卫 check-then-update 临界区，
	 * 避免并发线程同时通过守卫导致重复读取配置。
	 *
	 * @param level 世界实例（用于获取游戏刻）
	 * @return 当前 maxOpsPerTick 配置值
	 */
	synchronized int refreshAndGetMaxOps(Level level) {
		long currentGameTime = level != null ? level.getGameTime() : 0L;
		if (currentGameTime - maxOpsConfigLastTick >= MAX_OPS_REFRESH_INTERVAL
				&& ModConfig.SERVER != null) {
			maxOpsConfigCached = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
			maxOpsConfigLastTick = currentGameTime;
		}
		return maxOpsConfigCached;
	}

	/**
	 * 检查指定进程的所有物品输出槽是否已满
	 * <br/>
	 * 满时暂停处理，避免物品丢失（与 MEK 原版 NOT_ENOUGH_OUTPUT_SPACE 行为一致）。
	 *
	 * @param context PB 配方处理上下文
	 * @param process 进程索引
	 * @return true 如果该进程的所有物品输出槽均无剩余空间
	 */
	static boolean areOutputSlotsFull(PbRecipeContext context, int process) {
		return context.productivebeesgenesis$outputSlotsFull(process);
	}
}
