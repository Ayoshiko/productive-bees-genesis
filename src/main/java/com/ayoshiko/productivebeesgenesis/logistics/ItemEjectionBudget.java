package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/** 相邻目标的实际调用成本；直出与槽位弹出共享额度，各保留一次推进机会。 */
final class ItemEjectionBudget {

	private static final long HEALTHY_NANOS = 150_000L;
	private static final long SOFT_BUDGET_NANOS = 2_000_000L;
	private static final long BURST_BUDGET_NANOS = 8_000_000L;
	private long tick = Long.MIN_VALUE;
	private long spentNanos;
	private long averageNanos;
	private boolean directAttempted;
	private boolean slotAttempted;

	boolean canAttempt(long gameTime, boolean direct) {
		refresh(gameTime);
		// 两条路径不能因执行顺序固定而相互饿死。
		if (direct ? !directAttempted : !slotAttempted) return true;
		return spentNanos < BURST_BUDGET_NANOS
				&& (averageNanos <= HEALTHY_NANOS || spentNanos < SOFT_BUDGET_NANOS);
	}

	void record(long gameTime, boolean direct, long costNanos) {
		refresh(gameTime);
		if (direct) directAttempted = true;
		else slotAttempted = true;
		long cost = Math.max(0L, costNanos);
		spentNanos = SaturatingMath.saturatingAdd(spentNanos, cost);
		averageNanos = averageNanos == 0L ? cost : averageNanos + ((cost - averageNanos) >> 3);
	}

	private void refresh(long gameTime) {
		if (tick == gameTime) return;
		tick = gameTime;
		spentNanos = 0L;
		directAttempted = false;
		slotAttempted = false;
	}
}
