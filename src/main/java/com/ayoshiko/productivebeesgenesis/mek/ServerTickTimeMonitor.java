package com.ayoshiko.productivebeesgenesis.mek;

/**
	 * 服务端 tick 时间监测器 — 通过 ServerTickEvent.Pre/Post 监听器记录每 tick 实际耗时（MSPT），
	 * 维护最近 100 tick 滚动平均，暴露 getTpsFactor() 供所有节流逻辑使用。
	 * 单例模式（服务端单线程无需并发安全）。
	 * TPS 自适应：健康服务器 avgMspt ≤ 50ms → factor = 1.0；
	 * 轻微卡顿 50-100ms → 线性降级到 0.1；
	 * 严重卡顿 > 100ms → factor = 0.1（最低保护，避免完全停止）。
	 * 滞回机制：上升沿 60ms 触发降级，下降沿 45ms 触发升级，避免在阈值附近震荡。
	 */
public class ServerTickTimeMonitor {

	/** 滚动数组大小 — 最近 100 tick 的耗时样本 */
	private static final int SAMPLE_SIZE = 100;

	/** 同一游戏刻内共享计算结果；滚动平均已改为 O(1)，无需跨刻保留陈旧预算。 */
	private static final int TPS_FACTOR_CACHE_INTERVAL = 1;

	/** 短期 MSPT 指数平均权重；用于比 100-tick 长期平均更快感知突发负载。 */
	private static final double RESPONSIVE_ALPHA = 0.2;

	/** 健康 TPS 阈值（ms）— 50ms 对应 20 TPS */
	private static final double HEALTHY_MSPT = 50.0;

	/** 严重卡顿阈值（ms）— 100ms 对应 10 TPS */
	private static final double SEVERE_MSPT = 100.0;

	/** 最低 tpsFactor — 不为 0 避免完全停止拉取导致输入断供 */
	private static final double MIN_FACTOR = 0.1;

	/** 上升沿触发阈值（ms）— 开始降级 */
	private static final double DOWNGRADE_THRESHOLD = 60.0;

	/** 下降沿恢复阈值（ms）— 恢复满倍率 */
	private static final double UPGRADE_THRESHOLD = 45.0;

	/** 单例实例 */
	private static final ServerTickTimeMonitor INSTANCE = new ServerTickTimeMonitor();

	/** tick 耗时滚动数组（ms） */
	private final double[] tickDurations = new double[SAMPLE_SIZE];

	/** 滚动数组的写入位置（取模循环） */
	private int sampleIndex = 0;

	/** 已记录的样本数（启动初期不足 SAMPLE_SIZE 时使用） */
	private int sampleCount = 0;

	/** 最近 SAMPLE_SIZE 个样本之和，使平均值读取保持 O(1)。 */
	private double rollingDurationTotal = 0.0;

	/** 短期 MSPT 指数平均；预算取长期平均与该值的较大者。 */
	private double responsiveMspt = 0.0;

	/** ServerTickEvent.Pre 时记录的起始时间（纳秒） */
	private long tickStartNanos = 0L;

	/** 缓存的有效 MSPT（长期滚动平均与短期指数平均的较大者）。 */
	private double cachedAvgMspt = 0.0;

	/** 缓存的 tpsFactor（每 TPS_FACTOR_CACHE_INTERVAL tick 重算一次） */
	private double cachedTpsFactor = 1.0;

	/** 缓存失效时的游戏刻；Long.MIN_VALUE 同时覆盖首次读取与游戏时间回拨。 */
	private long lastFactorCacheTick = Long.MIN_VALUE;

	/** 当前 factor 状态：true=满倍率(1.0)，false=已降级（滞回状态机） */
	private boolean atFullFactor = true;

	/**
	 * 获取单例实例
	 * <br/>
	 * 服务端单线程执行，无需并发安全保护。
	 *
	 * @return 单例实例
	 */
	public static ServerTickTimeMonitor getInstance() {
		return INSTANCE;
	}

	/**
	 * ServerTickEvent.Pre 监听器 — 记录 tick 起始时间
	 * <br/>
	 * 性能约束：仅 System.nanoTime() 调用，禁止其他操作。
	 *
	 * @param event 服务端 tick Pre 事件
	 */
	public void onTickPre(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
		tickStartNanos = System.nanoTime();
	}

	/**
	 * ServerTickEvent.Post 监听器 — 计算并记录本 tick 耗时到滚动数组
	 * <br/>
	 * 性能约束：仅算术运算 + 数组写入，禁止 I/O 或字符串操作。
	 *
	 * @param event 服务端 tick Post 事件
	 */
	public void onTickPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
		long durationNanos = System.nanoTime() - tickStartNanos;
		double durationMs = Math.max(0.0, durationNanos / 1_000_000.0);
		if (sampleCount == SAMPLE_SIZE) {
			rollingDurationTotal -= tickDurations[sampleIndex];
		} else {
			sampleCount++;
		}
		tickDurations[sampleIndex] = durationMs;
		rollingDurationTotal += durationMs;
		responsiveMspt = nextResponsiveMspt(responsiveMspt, durationMs);
		sampleIndex = (sampleIndex + 1) % SAMPLE_SIZE;
	}

	/**
	 * 计算最近 100 tick 的平均耗时（MSPT）
	 * <br/>
	 * 使用写入时维护的滚动总和，启动初期按实际样本数计算。
	 *
	 * @return 平均 MSPT（ms），无样本时返回 0.0
	 */
	public double getAvgMspt() {
		if (sampleCount == 0) {
			return 0.0;
		}
		return rollingDurationTotal / sampleCount;
	}

	/**
	 * 获取当前 TPS 自适应因子（同一游戏刻共享缓存）
	 * <br/>
	 * 长期滚动平均和短期指数平均都在样本写入时 O(1) 更新；这里每个游戏刻只由
	 * 第一台加速机器重算一次，后续机器复用结果。
	 *
	 * @param currentTick 当前游戏刻（由外部传入，避免内部调用 level.getGameTime()）
	 * @return TPS 因子，范围 [MIN_FACTOR, 1.0]
	 */
	public double getTpsFactor(long currentTick) {
		if (lastFactorCacheTick != Long.MIN_VALUE
				&& currentTick >= lastFactorCacheTick
				&& currentTick - lastFactorCacheTick < TPS_FACTOR_CACHE_INTERVAL) {
			return cachedTpsFactor;
		}
		lastFactorCacheTick = currentTick;
		cachedAvgMspt = Math.max(getAvgMspt(), responsiveMspt);
		cachedTpsFactor = computeTpsFactorWithHysteresis(cachedAvgMspt);
		return cachedTpsFactor;
	}

	static double nextResponsiveMspt(double previous, double sample) {
		if (previous <= 0.0) {
			return Math.max(0.0, sample);
		}
		return RESPONSIVE_ALPHA * Math.max(0.0, sample) + (1.0 - RESPONSIVE_ALPHA) * previous;
	}

	/**
	 * 获取当前估算 TPS（带 10-tick 缓存，与 {@link #getTpsFactor} 共享缓存）。
	 * <br/>
	 * TPS = 1000 / avgMspt。健康服务器 avgMspt=50ms → TPS=20；
	 * 严重卡顿 avgMspt=100ms → TPS=10；极端卡顿 avgMspt=3490ms → TPS≈0.3。
	 * 无样本时返回 20.0（健康默认值，避免启动初期误跳过）。
	 * <p>
	 * <b>用途</b>：供 AE2 推送/拉取的 "TPS &lt; N 时跳过" 判断使用，
	 * 语义比 {@link #getTpsFactor}（0.1-1.0 范围的降级因子）更直观。
	 *
	 * @param currentTick 当前游戏刻
	 * @return 估算 TPS 值，范围 [0, 20]
	 */
	public double getTps(long currentTick) {
		// 触发缓存更新（与 getTpsFactor 共享同一缓存周期）
		getTpsFactor(currentTick);
		if (cachedAvgMspt <= 0.0) return 20.0;
		return Math.max(0.0, 1000.0 / cachedAvgMspt);
	}

	/**
	 * 滞回状态机计算 TPS 因子
	 * <br/>
	 * 状态机转换：
	 * <ul>
	 *   <li>满倍率状态：avgMspt > DOWNGRADE_THRESHOLD(60ms) 触发降级（上升沿）</li>
	 *   <li>已降级状态：avgMspt &lt; UPGRADE_THRESHOLD(45ms) 恢复满倍率（下降沿）</li>
	 * </ul>
	 * 因子计算规则：
	 * <ul>
	 *   <li>满倍率状态且 avgMspt ≤ HEALTHY_MSPT(50ms)：返回 1.0</li>
	 *   <li>满倍率状态且 HEALTHY_MSPT &lt; avgMspt ≤ DOWNGRADE_THRESHOLD：过渡区轻微降级（1.0→0.8）</li>
	 *   <li>已降级状态且 avgMspt &lt; SEVERE_MSPT(100ms)：线性降级（50ms→1.0, 100ms→0.1）</li>
	 *   <li>已降级状态且 avgMspt ≥ SEVERE_MSPT：返回 MIN_FACTOR(0.1)</li>
	 * </ul>
	 *
	 * @param avgMspt 平均 MSPT（ms）
	 * @return TPS 因子，范围 [MIN_FACTOR, 1.0]
	 */
	private double computeTpsFactorWithHysteresis(double avgMspt) {
		atFullFactor = nextFullFactorState(atFullFactor, avgMspt);
		return factorForMspt(avgMspt, atFullFactor);
	}

	static boolean nextFullFactorState(boolean currentlyFull, double avgMspt) {
		if (currentlyFull) {
			return avgMspt <= DOWNGRADE_THRESHOLD;
		}
		return avgMspt < UPGRADE_THRESHOLD;
	}

	static double factorForMspt(double avgMspt, boolean fullFactorState) {
		if (fullFactorState) {
			// 满倍率或轻微超线但未触发降级：返回 1.0
			// 但若 avgMspt 已超过 HEALTHY_MSPT (50)，需要轻微线性降级（仅用于过渡区）
			if (avgMspt <= HEALTHY_MSPT) {
				return 1.0;
			}
			// 过渡区：50-60ms 之间轻微降级，避免突变
			return 1.0 - (avgMspt - HEALTHY_MSPT) / (DOWNGRADE_THRESHOLD - HEALTHY_MSPT) * 0.2;
		}

		// 已降级状态：50-100ms 线性 1.0→0.1，>100ms 0.1
		if (avgMspt >= SEVERE_MSPT) {
			return MIN_FACTOR;
		}
		// 线性降级：50ms→1.0, 100ms→0.1
		// 注意：avgMspt < HEALTHY_MSPT(50) 时（仍处于已降级状态未恢复），线性公式会返回 > 1.0
		// 通过 Math.min(1.0, ...) 钳制上限，避免有效速率超过原版
		double factor = 1.0 - (avgMspt - HEALTHY_MSPT) / (SEVERE_MSPT - HEALTHY_MSPT) * (1.0 - MIN_FACTOR);
		return Math.min(1.0, Math.max(MIN_FACTOR, factor));
	}

	/**
	 * 重置所有状态用于服务器重启
	 * <br/>
	 * 清空滚动样本数组、缓存与滞回状态机，使监测器回到初始状态。
	 */
	public void invalidate() {
		sampleIndex = 0;
		sampleCount = 0;
		rollingDurationTotal = 0.0;
		responsiveMspt = 0.0;
		tickStartNanos = 0L;
		cachedAvgMspt = 0.0;
		cachedTpsFactor = 1.0;
		lastFactorCacheTick = Long.MIN_VALUE;
		atFullFactor = true;
	}
}
