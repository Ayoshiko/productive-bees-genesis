package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;

/**
 * 仅样板供应器向本模组离心机提交时使用的全服共享调用预算；只在服务器线程访问。
 * 模拟不计数，单次请求不裁剪数量，未接收的原料由供应器 sendList 持有并重试。
 * 大批次通常只需少数调用；逐份洪水则通过供应器 busy 状态让 CPU 停止继续派单。
 * 不缓存机器、网络或世界引用，停服由 AE2 集成清理入口复位。
 */
final class CentrifugeDispatchScope {

	static final int MIN_BUDGET = 256;
	static final int MAX_BUDGET = 1_048_576;
	private static final int STEP = 512;
	private static final int DECAY_PERCENT = 75;
	private static final double HEALTHY_FACTOR = 0.75;
	private static final double DECAY_FACTOR = 0.65;
	private static final long DECAY_COOLDOWN_TICKS = 4;

	private static long tick = ServerTickClock.UNSET;
	private static long lastDecayTick = ServerTickClock.UNSET;
	private static int count;
	private static int budget = MIN_BUDGET;
	private static int slowStartThreshold = MAX_BUDGET;

	private CentrifugeDispatchScope() {
	}

	static boolean externalPushOverBudget() {
		long currentTick = ServerTickClock.now();
		// 加载期/无服务器的注册表单测没有真实刻，不建立永久耗尽的预算。
		if (currentTick == ServerTickClock.UNSET) return false;
		double factor = currentTick == tick ? 1.0
				: ServerTickTimeMonitor.getInstance().getTpsFactor(currentTick);
		return externalPushOverBudget(currentTick, factor);
	}

	static boolean externalPushOverBudget(long currentTick, double factor) {
		if (currentTick == ServerTickClock.UNSET) return false;
		if (tick != ServerTickClock.UNSET && currentTick < tick) reset();
		if (currentTick != tick) {
			if (tick != ServerTickClock.UNSET) {
				// 空闲间隔不算饱和；不能因很久以前用满过预算而突然抬高冷启动成本。
				boolean saturated = currentTick - tick == 1 && count >= budget;
				adjustBudget(currentTick, saturated, factor);
			}
			tick = currentTick;
			count = 0;
		}
		if (count >= budget) return true;
		count++;
		return false;
	}

	private static void adjustBudget(long currentTick, boolean saturated, double factor) {
		if (!Double.isFinite(factor)) return;
		if (factor < DECAY_FACTOR) {
			// 首次退避特判哨兵，避免 long 相减溢出；滞回与冷却避免滞后 MSPT 连环退避。
			if (lastDecayTick == ServerTickClock.UNSET
					|| currentTick - lastDecayTick >= DECAY_COOLDOWN_TICKS) {
				budget = Math.max(MIN_BUDGET, budget * DECAY_PERCENT / 100);
				slowStartThreshold = budget;
				lastDecayTick = currentTick;
			}
		} else if (factor >= HEALTHY_FACTOR && saturated) {
			budget = budget < slowStartThreshold
					? Math.min(slowStartThreshold, Math.min(MAX_BUDGET, budget * 2))
					: Math.min(MAX_BUDGET, budget + STEP);
		}
	}

	static void reset() {
		tick = ServerTickClock.UNSET;
		lastDecayTick = ServerTickClock.UNSET;
		count = 0;
		budget = MIN_BUDGET;
		slowStartThreshold = MAX_BUDGET;
	}

	static int budgetForTest() {
		return budget;
	}
}
