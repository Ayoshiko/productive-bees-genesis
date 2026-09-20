package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/** 只规划已经分配的虚拟 tick；不修改蜂位、不扣能、不采样结果。 */
public record BeeProgressPlan(int cycleTicks, int remainingTicks, long completedCycles,
		long productionCycles, long energyCost) {

	public BeeProgressPlan {
		if (cycleTicks < 1 || remainingTicks < 0 || completedCycles < 0 || productionCycles < 0 || energyCost < 0) {
			throw new IllegalArgumentException("Invalid bee progress plan");
		}
	}

	public static int cycleTicks(int baseTicks, int fallbackTicks, float timeMultiplier, boolean creative) {
		int base = baseTicks > 0 ? baseTicks : fallbackTicks;
		float speed = SaturatingMath.positiveFiniteFloat(timeMultiplier, 1.0F);
		return creative ? 1 : Math.max(1, SaturatingMath.saturatingRoundToInt((double) base * speed));
	}

	/** 单位能耗由成员升级快照给出；调用方须先分配可支付 tick，溢出不能以少收费降级。 */
	public static BeeProgressPlan plan(int progressTicks, int allocatedTicks, int cycleTicks,
			long energyPerTick, int stackMultiplier) {
		if (progressTicks < 0 || allocatedTicks < 0 || cycleTicks < 1 || energyPerTick < 0 || stackMultiplier < 0) {
			throw new IllegalArgumentException("Invalid bee work context");
		}
		if (allocatedTicks == 0) return new BeeProgressPlan(cycleTicks, progressTicks, 0, 0, 0);
		long advanced = (long) progressTicks + allocatedTicks;
		long completed = advanced / cycleTicks;
		return new BeeProgressPlan(cycleTicks, (int) (advanced % cycleTicks), completed,
				Math.multiplyExact(completed, stackMultiplier), Math.multiplyExact(energyPerTick, allocatedTicks));
	}
}
