package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import java.util.concurrent.atomic.AtomicLong;

/**
	 * 蜂箱升级倍率缓存
	 * <br/>
	 * 缓存 {@link ApiaryUpgradeHandler} 的 6 个高频查询结果（时间倍率、能耗倍率、生产力倍率、
	 * 蜜脾块升级标志、基因采样器升级标志、基因采样器数量），避免每 tick 重复调用
	 * {@code Math.pow} 与 EnumMap 查询。
	 * <p>
	 * 缓存刷新策略（参考 {@code PbRecipeProcessor} 的 100-tick 配置缓存模式）：
	 * <ul>
	 *   <li>每 {@value #REFRESH_INTERVAL} 次调用自动刷新一次，对齐
	 *       {@code BeeSlotTickProcessor.configCache} 周期</li>
	 *   <li>使用 volatile 字段 + simple if check 守卫，防止多线程重复刷新</li>
	 *   <li>升级组件变更时调用 {@link #invalidate()} 强制下次访问触发刷新</li>
	 * </ul>
	 * <p>
	 * <b>JDTE 适配</b>：刷新基于内部计数器 {@link #internalCounter} 而非 {@code level.getGameTime()}。
	 * JDTE（Just Do The Enhancement）等 tick 加速模组通过多次调用 {@code BlockEntityTicker.tick()}
	 * 实现加速，{@code getGameTime()} 在这些加速 tick 中保持不变，会导致 100-tick 缓存守卫失效。
	 * 改用每次 {@link #refreshIfNeeded()} 调用递增的内部计数器，正确反映实际 tick 调用次数。
	 * <p>
	 * <b>递归防护</b>：刷新逻辑调用 handler 的 package-private {@code compute*} 方法
	 * （非公共 getter），因为公共 getter 已委托到本缓存，调用公共 getter 会形成
	 * handler.getXxx → cache.getXxx → refreshIfNeeded → handler.getXxx 的无限递归。
	 * <p>
	 * 异常处理：刷新过程中若 handler compute* 方法抛出异常，标记 {@link #refreshFailed}，
	 * 后续 getter 退化为直接调用 handler compute* 方法（不缓存），并通过 {@link LogThrottle}
	 * 节流记录警告日志，避免高频异常刷屏。
	 * <p>
	 * <b>线程安全</b>：缓存值字段为 volatile（单字段读写原子，保证可见性）；
	 * 计数器与时间戳字段使用 {@link AtomicLong}，并通过 {@code synchronized(this)} 守卫
	 * check-refresh-update 临界区，避免 check-then-act 竞态导致重复刷新或刷新丢失。
	 * 服务端单线程 tick 场景下作为可见性保证与安全冗余。
	 *
	 * @since 1.0.0
	 */
public class ApiaryUpgradeCache {

	/** 缓存刷新间隔（调用次数） — 对齐 BeeSlotTickProcessor.configCache 周期 */
	public static final int REFRESH_INTERVAL = 100;

	/** 所属升级处理器引用 — 用于调用原方法计算倍率 */
	private final ApiaryUpgradeHandler handler;

	/** 刷新异常日志冷却器（ms 模式，避免高频异常刷屏） */
	private final LogThrottle errorThrottle = new LogThrottle();

	/** 缓存的时间倍率（默认 1.0 = 无加速） */
	private volatile float cachedTimeMultiplier = 1.0f;

	/** 缓存的能耗倍率（默认 1.0 = 无能耗增加） */
	private volatile float cachedEnergyMultiplier = 1.0f;

	/** 缓存的生产力倍率（默认 1.0 = 无产量加成） */
	private volatile float cachedProductivityMultiplier = 1.0f;

	/** 缓存的蜜脾块升级标志（默认 false = 未安装） */
	private volatile boolean cachedHasCombBlock = false;

	/** 缓存的基因采样器升级标志（默认 false = 未安装） */
	private volatile boolean cachedHasGeneSampler = false;

	/** 缓存的基因采样器安装数量（默认 0 = 未安装） */
	private volatile int cachedGeneSamplerCount = 0;

	/** 内部调用计数器 — JDTE 适配，不依赖 level.getGameTime()；AtomicLong 保证自增原子 */
	private final AtomicLong internalCounter = new AtomicLong(0L);

	/** 上次刷新时的内部计数器值 — 初始化为 -REFRESH_INTERVAL，首次访问触发立即刷新 */
	private final AtomicLong lastRefreshCounter = new AtomicLong(-REFRESH_INTERVAL);

	/** 最近一次刷新是否失败 — true 时 getter 退化为直接调用 handler 原方法（不缓存） */
	private volatile boolean refreshFailed = false;

	/**
	 * 构造升级倍率缓存
	 *
	 * @param handler 所属升级处理器，用于调用原方法计算倍率
	 */
	public ApiaryUpgradeCache(ApiaryUpgradeHandler handler) {
		this.handler = handler;
	}

	/**
	 * 按需刷新缓存 — 100 次调用间隔守卫
	 * <br/>
	 * 外部调用方（如 {@code BeeSlotTickProcessor.tick()}）应每 tick 调用此方法。
	 * getter 内部也会调用此方法，确保首次访问触发刷新。
	 * <p>
	 * 刷新逻辑：若 {@code internalCounter - lastRefreshCounter >= REFRESH_INTERVAL}，
	 * 调用 handler 原方法重新计算 6 个缓存字段；否则直接返回，复用缓存。
	 * <p>
	 * <b>JDTE 适配</b>：基于内部计数器而非 gameTime，避免 tick 加速模组
	 * （多次调用 tick 但 getGameTime 不变）导致缓存守卫失效。
	 * <p>
	 * <b>线程安全</b>：{@code internalCounter} 自增使用 {@link AtomicLong#incrementAndGet()}
	 * 保证原子；check-refresh-update 临界区使用 {@code synchronized(this)} 守卫，
	 * 防止并发线程同时通过守卫导致重复刷新，或刷新与 invalidate 交错导致刷新丢失。
	 */
	public void refreshIfNeeded() {
		long current = internalCounter.incrementAndGet();
		if (current - lastRefreshCounter.get() >= REFRESH_INTERVAL) {
			synchronized (this) {
				// 二次检查：进入临界区后再次校验，避免多线程同时通过外层守卫后重复刷新
				if (current - lastRefreshCounter.get() >= REFRESH_INTERVAL) {
					doRefresh();
					lastRefreshCounter.set(current);
				}
			}
		}
	}

	/**
	 * 内部刷新实现 — 调用 handler 的 package-private compute* 方法计算 6 个缓存字段
	 * <br/>
	 * 注意：必须调用 {@code compute*} 方法而非公共 getter，否则会形成
	 * handler.getXxx → cache.getXxx → refreshIfNeeded → handler.getXxx 的无限递归。
	 * 6 个值先全部计算成功后再提交到缓存字段，避免部分刷新导致缓存不一致。
	 * 任一异常标记 {@link #refreshFailed}，getter 将退化为直接调用 handler compute* 方法。
	 */
	private void doRefresh() {
		try {
			float timeMultiplier = handler.computeTimeMultiplier();
			float energyMultiplier = handler.computeEnergyMultiplier();
			float productivityMultiplier = handler.computeProductivityMultiplier();
			boolean hasCombBlock = handler.computeHasCombBlockUpgrade();
			boolean hasGeneSampler = handler.computeHasGeneSamplerUpgrade();
			int geneSamplerCount = handler.computeGeneSamplerCount();
			// 6 个值全部计算成功，提交到缓存
			cachedTimeMultiplier = timeMultiplier;
			cachedEnergyMultiplier = energyMultiplier;
			cachedProductivityMultiplier = productivityMultiplier;
			cachedHasCombBlock = hasCombBlock;
			cachedHasGeneSampler = hasGeneSampler;
			cachedGeneSamplerCount = geneSamplerCount;
			refreshFailed = false;
		} catch (Exception e) {
			refreshFailed = true;
			errorThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
				ProductiveBeesGenesis.LOGGER.warn(
						"ApiaryUpgradeCache 刷新异常，退化为直接调用 handler compute* 方法{}",
						suppressed > 0 ? " (抑制 " + suppressed + " 次)" : "",
						e);
			});
		}
	}

	/**
	 * 获取时间倍率
	 * <br/>
	 * 首次访问或距上次刷新 ≥ {@value #REFRESH_INTERVAL} 次调用时自动触发刷新。
	 * 刷新失败时退化为直接调用 {@link ApiaryUpgradeHandler#computeTimeMultiplier()}（不走缓存）。
	 *
	 * @return 时间倍率（>0，越小越快）
	 */
	public float getTimeMultiplier() {
		refreshIfNeeded();
		if (refreshFailed) {
			return handler.computeTimeMultiplier();
		}
		return cachedTimeMultiplier;
	}

	/**
	 * 获取能耗倍率
	 * <br/>
	 * 首次访问或距上次刷新 ≥ {@value #REFRESH_INTERVAL} 次调用时自动触发刷新。
	 * 刷新失败时退化为直接调用 {@link ApiaryUpgradeHandler#computeEnergyMultiplier()}（不走缓存）。
	 *
	 * @return 能耗倍率（≥1.0）
	 */
	public float getEnergyMultiplier() {
		refreshIfNeeded();
		if (refreshFailed) {
			return handler.computeEnergyMultiplier();
		}
		return cachedEnergyMultiplier;
	}

	/**
	 * 获取生产力倍率
	 * <br/>
	 * 首次访问或距上次刷新 ≥ {@value #REFRESH_INTERVAL} 次调用时自动触发刷新。
	 * 刷新失败时退化为直接调用 {@link ApiaryUpgradeHandler#computeProductivityMultiplier()}（不走缓存）。
	 *
	 * @return 生产力倍率（≥1.0）
	 */
	public float getProductivityMultiplier() {
		refreshIfNeeded();
		if (refreshFailed) {
			return handler.computeProductivityMultiplier();
		}
		return cachedProductivityMultiplier;
	}

	/**
	 * 是否安装了蜜脾块升级
	 * <br/>
	 * 首次访问或距上次刷新 ≥ {@value #REFRESH_INTERVAL} 次调用时自动触发刷新。
	 * 刷新失败时退化为直接调用 {@link ApiaryUpgradeHandler#computeHasCombBlockUpgrade()}（不走缓存）。
	 *
	 * @return true 如果已安装 BLOCK 升级或 Ω 产量升级
	 */
	public boolean hasCombBlockUpgrade() {
		refreshIfNeeded();
		if (refreshFailed) {
			return handler.computeHasCombBlockUpgrade();
		}
		return cachedHasCombBlock;
	}

	/**
	 * 是否安装了基因采样器升级
	 * <br/>
	 * 首次访问或距上次刷新 ≥ {@value #REFRESH_INTERVAL} 次调用时自动触发刷新。
	 * 刷新失败时退化为直接调用 {@link ApiaryUpgradeHandler#computeHasGeneSamplerUpgrade()}（不走缓存）。
	 *
	 * @return true 如果已安装至少一个基因采样器
	 */
	public boolean hasGeneSamplerUpgrade() {
		refreshIfNeeded();
		if (refreshFailed) {
			return handler.computeHasGeneSamplerUpgrade();
		}
		return cachedHasGeneSampler;
	}

	/**
	 * 获取基因采样器安装数量
	 * <br/>
	 * 首次访问或距上次刷新 ≥ {@value #REFRESH_INTERVAL} 次调用时自动触发刷新。
	 * 刷新失败时退化为直接调用 {@link ApiaryUpgradeHandler#computeGeneSamplerCount()}（不走缓存）。
	 *
	 * @return 基因采样器安装数量（0 表示未安装）
	 */
	public int getGeneSamplerCount() {
		refreshIfNeeded();
		if (refreshFailed) {
			return handler.computeGeneSamplerCount();
		}
		return cachedGeneSamplerCount;
	}

	/**
	 * 失效缓存 — 强制下次访问触发刷新
	 * <br/>
	 * 升级组件变更（玩家安装/移除升级）时调用，确保下次 getter 访问重新计算倍率。
	 * 仅重置 {@link #lastRefreshCounter}，不清理缓存字段（旧值在刷新成功前仍可读，避免空窗期）。
	 * <p>
	 * <b>线程安全</b>：与 {@link #refreshIfNeeded()} 共用 {@code synchronized(this)} 锁，
	 * 保证 invalidate 与 refresh 不会交错执行导致状态不一致。
	 */
	public void invalidate() {
		synchronized (this) {
			lastRefreshCounter.set(internalCounter.get() - REFRESH_INTERVAL);
		}
	}
}
