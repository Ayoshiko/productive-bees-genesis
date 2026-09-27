package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/**
 * 每台机器的提取成本预算，仅服务端 tick 线程访问。
 * 按实测成本减少昂贵网络的调用次数，不改变单次请求量；未提取物品仍留在 ME 中。
 * 网络间不共享静态额度，网络内的病态操作继续由 Ae2NetworkWorkCoordinator 协调。
 * 预算只能停止后续调用，不能中断已经开始的外部存储操作。
 */
final class Ae2ExtractBudget {

	private static final long HEALTHY_EXTRACT_NANOS = 300_000L;
	private static final long QUOTA_BUDGET_NANOS = 2_000_000L;
	private static final long TILE_TICK_BUDGET_NANOS = 8_000_000L;
	private static final long COMBINED_TILE_TICK_BUDGET_NANOS = 10_000_000L;
	private long averageNanos;
	private long tileTick = Long.MIN_VALUE;
	private long tileSpentNanos;
	private int tileExtractsThisTick;

	/** 所有 MODULATE 尝试都计费，包括零返回和异常，不推断后端是否实际落盘。 */
	void record(long gameTick, long costNanos) {
		if (costNanos < 0L) return;
		averageNanos = averageNanos <= 0L
				? costNanos : averageNanos + ((costNanos - averageNanos) >> 3);
		refreshTile(gameTick);
		tileSpentNanos = SaturatingMath.saturatingAdd(tileSpentNanos, costNanos);
		if (tileExtractsThisTick < Integer.MAX_VALUE) tileExtractsThisTick++;
	}

	/** 保留线探针也消耗主线程时间，但不混入 MODULATE 单次成本均值。 */
	void recordProbe(long gameTick, long costNanos) {
		if (costNanos < 0L) return;
		refreshTile(gameTick);
		tileSpentNanos = SaturatingMath.saturatingAdd(tileSpentNanos, costNanos);
	}

	boolean canExtractNow(long gameTick, int maxExtracts) {
		return canExtractNow(gameTick, maxExtracts, 0L);
	}

	boolean canExtractNow(long gameTick, int maxExtracts, long insertSpentThisTickNanos) {
		refreshTile(gameTick);
		if (maxExtracts <= 0 || tileSpentNanos >= TILE_TICK_BUDGET_NANOS) return false;
		// 输出先执行，仍给输入一次机会，避免持续慢输出把输入永久饿死。
		if (tileExtractsThisTick > 0 && SaturatingMath.saturatingAdd(
				Math.max(0L, insertSpentThisTickNanos), tileSpentNanos)
				>= COMBINED_TILE_TICK_BUDGET_NANOS) return false;
		if (!isExpensiveNetwork()) return true;
		return tileExtractsThisTick < keyQuota(maxExtracts);
	}

	int keyQuota(int maxKeys) {
		if (maxKeys <= 0) return 0;
		if (!isExpensiveNetwork()) return maxKeys;
		return (int) Math.max(1L, Math.min(maxKeys, QUOTA_BUDGET_NANOS / averageNanos));
	}

	boolean isExpensiveNetwork() {
		return averageNanos > HEALTHY_EXTRACT_NANOS;
	}

	long averageCostNanos() {
		return averageNanos;
	}

	void reset() {
		averageNanos = 0L;
		tileTick = Long.MIN_VALUE;
		tileSpentNanos = 0L;
		tileExtractsThisTick = 0;
	}

	private void refreshTile(long gameTick) {
		if (gameTick != tileTick) {
			tileTick = gameTick;
			tileSpentNanos = 0L;
			tileExtractsThisTick = 0;
		}
	}
}
