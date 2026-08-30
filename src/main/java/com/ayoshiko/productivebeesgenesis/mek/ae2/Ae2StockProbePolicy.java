package com.ayoshiko.productivebeesgenesis.mek.ae2;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * 「模拟抽取探针」是否值得执行的自适应策略（单机实例，非静态）
 * <br/>
 * 背景：{@code Ae2NetworkInventoryView.visibleAmount} 对每个候选键执行一次
 * {@code network.extract(key, MAX, SIMULATE)}，用来兼容那些在 KeyCounter 里
 * 上报占位数量的特殊/无限存储（ae2lt、无限元件等）。在普通存储上这次探针得到的
 * 结果与缓存计数完全一致，属纯浪费；而某些第三方元件的 extract 极其昂贵 ——
 * spark 报告 vVh8WfPCN3 实测 megacells {@code BulkCellInventory.extract} →
 * {@code CompressionChain.containsVariant} 线性遍历压缩链，仅 SIMULATE 探针
 * 就占 1428ms（其中大宗盘 872ms）。
 * <p>
 * 策略：<b>健康网络零改动</b>（平均探针 ≤ {@link #HEALTHY_PROBE_NANOS} 时全部照常探测）；
 * 一旦实测平均成本偏高，只对「确实需要探针」的键（历史上 simulated &gt; reported，
 * 即真正的占位/无限存储）继续每 tick 探测，其余键直接用 KeyCounter 计数；
 * 未见过的新键必探一次以完成学习；每 {@link #RELEARN_INTERVAL_TICKS} tick 放开一次全量重探，
 * 让玩家换存储元件后能自动重新学习。另有每 tick 时间预算做灾难兜底。
 * <p>
 * 降级只影响「拉取候选可见量」的乐观估计：真正跨越保留线前的实时校验走
 * {@code Ae2NetworkInventoryView.liveExtractableAmount}，不受本策略影响，
 * 因此不会出现超抽或破坏保留量的行为。
 * <p>
 * <b>线程安全</b>：实例由所属机器的服务端 tick 线程独占访问（与 TickCache 同生命周期），
 * 不跨线程共享，故使用非并发容器。
 *
 * @param <K> 存储键类型（生产路径为 {@code AEItemKey}；泛型化便于纯 Java 单元测试）
 */
final class Ae2StockProbePolicy<K> {

	/** 健康探针上限：50µs。普通 AE2 存储的 SIMULATE extract 实测在此量级以下。 */
	private static final long HEALTHY_PROBE_NANOS = 50_000L;
	/** 昂贵网络下每台机器每 tick 的探针时间预算：1ms。 */
	private static final long PROBE_TICK_BUDGET_NANOS = 1_000_000L;
	/** 全量重探间隔（tick）：玩家更换存储元件后据此重新学习。 */
	private static final long RELEARN_INTERVAL_TICKS = 100L;
	/** 跟踪键上限，超出即整体重置，防止大型网络下无界增长。 */
	private static final int MAX_TRACKED_KEYS = 1024;
	/** EWMA 平滑位移（1/8 权重），与 Ae2InsertCostTracker 保持一致。 */
	private static final int EWMA_SHIFT = 3;

	/** 探针成本 EWMA，<=0 表示尚无样本。 */
	private long averageNanos;
	private long probeTick = Long.MIN_VALUE;
	private long spentNanos;
	private long nextRelearnTick = Long.MIN_VALUE;
	/** 本轮是否处于全量重探窗口。 */
	private boolean relearnWindow;

	/** 已探测过的键（用于识别新键，新键必探一次）。 */
	private final ObjectOpenHashSet<K> knownKeys = new ObjectOpenHashSet<>();
	/** 探针确有价值的键：模拟量高于 KeyCounter 上报量（占位/无限存储）。 */
	private final ObjectOpenHashSet<K> probeWorthyKeys = new ObjectOpenHashSet<>();

	/**
	 * 判断本次是否执行模拟抽取探针。
	 *
	 * @param gameTick 当前游戏刻
	 * @param key      候选物品键
	 * @return true 表示执行探针；false 表示只用 KeyCounter 缓存计数
	 */
	boolean shouldProbe(long gameTick, K key) {
		refreshTick(gameTick);
		// 健康网络：不做任何限制，行为与旧实现完全一致
		if (averageNanos <= HEALTHY_PROBE_NANOS) return true;
		if (spentNanos >= PROBE_TICK_BUDGET_NANOS) return false;
		if (relearnWindow) return true;
		if (key == null) return false;
		// 新键必探一次：否则永远学不到它是否需要探针
		return !knownKeys.contains(key) || probeWorthyKeys.contains(key);
	}

	/**
	 * 记录一次探针的实测成本与结论。
	 *
	 * @param costNanos 本次探针耗时
	 * @param reported  KeyCounter 上报量
	 * @param simulated 模拟抽取量
	 */
	void record(long gameTick, K key, long costNanos, long reported, long simulated) {
		refreshTick(gameTick);
		long safeCost = Math.max(0L, costNanos);
		spentNanos += safeCost;
		averageNanos = averageNanos <= 0L
				? safeCost
				: averageNanos + ((safeCost - averageNanos) >> EWMA_SHIFT);
		if (key == null) return;
		if (knownKeys.size() >= MAX_TRACKED_KEYS || probeWorthyKeys.size() >= MAX_TRACKED_KEYS) {
			// 超界即整体重置：宁可重新学习，也不持有无界键集合
			knownKeys.clear();
			probeWorthyKeys.clear();
		}
		knownKeys.add(key);
		if (simulated > reported) {
			probeWorthyKeys.add(key);
		} else {
			probeWorthyKeys.remove(key);
		}
	}

	/** 换网络或网格重建后清空学习结果。 */
	void reset() {
		averageNanos = 0L;
		spentNanos = 0L;
		probeTick = Long.MIN_VALUE;
		nextRelearnTick = Long.MIN_VALUE;
		relearnWindow = false;
		knownKeys.clear();
		probeWorthyKeys.clear();
	}

	/** 探针平均成本（纳秒），用于日志与测试。 */
	long averageCostNanos() {
		return Math.max(0L, averageNanos);
	}

	/** 当前是否判定为昂贵网络（已触发探针收缩）。 */
	boolean isExpensiveNetwork() {
		return averageNanos > HEALTHY_PROBE_NANOS;
	}

	private void refreshTick(long gameTick) {
		if (probeTick == gameTick) return;
		probeTick = gameTick;
		spentNanos = 0L;
		if (nextRelearnTick == Long.MIN_VALUE || gameTick >= nextRelearnTick) {
			relearnWindow = true;
			nextRelearnTick = gameTick + RELEARN_INTERVAL_TICKS;
		} else {
			relearnWindow = false;
		}
	}
}
