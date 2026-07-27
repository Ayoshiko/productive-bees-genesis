package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.ConcurrentHashMap;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;

/**
 * PB 配方能量缓存与 ticks 计算器
 * <br/>
 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责
 * {@code getTicksForBase(baseTime)} 结果的时间窗口缓存和 PB 配方处理时间计算，
 * 不涉及配方查找、输出聚合或能量扣除等逻辑。
 * <p>
 * <b>缓存策略</b>：使用 20 tick（1 秒）时间窗口缓存，替代旧版"每 tick clear+重建"模式，
 * 避免 MU 256× 加速下每 tick 重新分配 HashMap 桶数组。升级变更后最多 20 tick 内自动反映新值
 * （与 {@link MyriadCreationsHandler#TICKS_CACHE_INTERVAL} 一致）。
 * <p>
 * <b>线程安全</b>：缓存底层使用 {@link ConcurrentHashMap}，过期判断与失效时间戳使用
 * {@code volatile} 字段保证跨线程可见性。"get-or-compute" 通过
 * {@link ConcurrentHashMap#computeIfAbsent} 原子完成，避免两个线程同时 miss 同一 key
 * 导致的重复计算。方块实体在服务端单线程执行，tags reload 或 JEI 异步查询等并发场景下
 * 最坏情况仅为重复计算一次（无更新丢失风险）。
 * 取消 synchronized 关键字以消除 monitorenter/exit 在高频调用路径上的开销。
 *
 * @since 1.7.0
 * @see PbRecipeProcessor
 */
public class PbRecipeEnergyCache {

	/** ticksForBaseCache 失效时间窗口（毫秒） — 使用 wall clock 避免 JDTE 加速下 getGameTime 不变导致缓存失效 */
	private static final long CACHE_WINDOW_MS = 1000L;

	/**
	 * getTicksForBase(baseTime) 的结果缓存 — 用于 PB 配方处理时间计算
	 * <br/>
	 * 不同配方的 baseTime 不同，但同一时间窗口内升级组件不变，计算结果只与 baseTime 有关。
	 * 使用 ConcurrentHashMap 保证并发安全（防御性设计，方块实体实际单线程执行）。
	 */
	private final ConcurrentHashMap<Integer, Integer> ticksForBaseCache = new ConcurrentHashMap<>(8);

	/**
	 * 当前 ticksForBaseCache 对应的失效墙钟时间戳（ms），过期时清空缓存
	 * <br/>
	 * volatile 保证可见性：配方重载清空缓存后，下次读取能立即观察到过期状态。
	 * 修复：原实现基于 getGameTime() 缓存，JDTE 加速下同一 gameTick 内 getGameTime 不变
	 * 导致缓存窗口判断失效。改用 System.currentTimeMillis() 保证 JDTE 下正常过期。
	 */
	private volatile long cacheExpireAtMs = -1L;

	/** PB 配方处理上下文 — 提供 getTicksForBase 和 baseTicksRequired */
	private final PbRecipeContext context;

	/**
	 * @param context PB 配方处理上下文（由 Factory TileEntity 提供）
	 */
	public PbRecipeEnergyCache(PbRecipeContext context) {
		this.context = context;
	}

	/**
	 * 获取 PB 配方处理时间（考虑速度升级）
	 * <br/>
	 * 优先使用配方定义的 processingTime，为 0 时回退到上下文的 baseTicksRequired，
	 * 然后通过 {@link #getCachedTicksForBase} 应用速度升级并缓存结果。
	 *
	 * @param recipe PB 离心配方
	 * @return 受速度升级影响的实际处理时间（tick）
	 */
	public int getPbProcessingTime(CentrifugeRecipe recipe) {
		int baseTime = recipe.getProcessingTime();
		if (baseTime <= 0) baseTime = context.baseTicksRequired();
		return getCachedTicksForBase(baseTime);
	}

	/**
	 * 时间窗口内 getTicksForBase(baseTime) 结果缓存
	 * <br/>
	 * 同一 1 秒时间窗口内升级组件不变，计算结果只与 baseTime 有关。
	 * 替代旧版每 tick clear+重建模式，避免 256× 加速下 HashMap 桶数组的频繁分配。
	 * 时间窗口过期时清空并重新填充，升级变更后最多 1 秒内反映新值。
	 * <p>
	 * <b>JDTE 适配</b>：使用 {@link System#currentTimeMillis()} 作为时间源，
	 * 避免 JDTE 加速下 {@code level.getGameTime()} 在同一 gameTick 内不变导致缓存窗口判断失效。
	 * <p>
	 * <b>线程安全</b>：使用 {@link ConcurrentHashMap#computeIfAbsent} 原子完成
	 * "get-or-compute" 序列，避免并发场景下两个线程同时 miss 同一 key 导致的重复计算
	 * （虽然方块实体实际单线程执行，但 tags reload、JEI 异步查询等场景可能并发访问）。
	 * 过期判断与失效时间戳使用 volatile 字段保证跨线程可见性，取消 synchronized
	 * 以消除高频调用路径上 monitorenter/exit 的开销。
	 *
	 * @param baseTime 基础处理时间
	 * @return 受速度升级影响的实际处理时间
	 */
	public int getCachedTicksForBase(int baseTime) {
		long currentTimeMs = System.currentTimeMillis();
		if (currentTimeMs >= cacheExpireAtMs) {
			ticksForBaseCache.clear();
			cacheExpireAtMs = currentTimeMs + CACHE_WINDOW_MS;
		}
		return ticksForBaseCache.computeIfAbsent(baseTime, k -> context.getTicksForBase(k));
	}

	/**
	 * 失效 ticksForBaseCache（配方重载或外部调用时强制下次重新计算）
	 * <br/>
	 * 清空缓存并重置过期时间，确保下次 {@link #getCachedTicksForBase} 调用时重新计算。
	 * <p>
	 * <b>线程安全</b>：{@link ConcurrentHashMap#clear} 与 volatile 字段写入本身具有
	 * 线程安全保证；并发场景下最坏情况为 clear 与 put 交错导致的一次重复计算，
	 * 不会出现更新丢失。
	 */
	public void clear() {
		ticksForBaseCache.clear();
		cacheExpireAtMs = -1L;
	}
}
