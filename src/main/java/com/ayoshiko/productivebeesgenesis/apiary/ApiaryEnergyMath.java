package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/**
	 * 蜜蜂能耗/产出计数数学工具 — 从 {@link BeeSlotTickProcessor} 拆分的静态辅助类。
	 * <br/>
	 * 同时供 {@link ApiaryProgressAdvancer} 与 {@link BeeSlotTickProcessor} 使用，防止极端加速倍率下能耗/产出计数溢出为负数。
	 */
final class ApiaryEnergyMath {

	private ApiaryEnergyMath() {
	}

	/**
	 * 计算单只蜜蜂每 tick 能耗
	 * <br/>
	 * = MachineEnergyContainer 当前每槽每 tick 能耗。
	 * Mekanism 已按 {@code ceil(base × multiplier)} 公式应用 SPEED/ENERGY 升级，
	 * 因此这里不能再次用浮点倍率计算，否则会丢失工厂等级并产生截断误差。
	 * <p>
	 *
	 * @param energyPerTick MachineEnergyContainer 当前每槽每 tick 能耗
	 * @return 单只蜜蜂每 tick 能耗（FE）
	 */
	static long calculateBeeEnergyCost(long energyPerTick) {
		return Math.max(0L, energyPerTick);
	}

	/**
	 * 计算单只蜜蜂每 tick 能耗，并在 CREATIVE 升级下显式免除能耗。
	 * <p>
	 * 不依赖 MEKExtras 对 {@code MachineEnergyContainer#getEnergyPerTick} 的 Mixin，
	 * 避免自定义容器或加载时序差异让创造升级意外扣能量。
	 */
	static long calculateBeeEnergyCost(long energyPerTick, boolean creativeInstalled) {
		return creativeInstalled ? 0L : calculateBeeEnergyCost(energyPerTick);
	}

	/** 计算单只蜜蜂在当前真实游戏刻内（含加速批量）应扣除的能量。 */
	static long calculateAcceleratedEnergyCost(long energyPerTick, int tickMultiplier) {
		return saturatingMultiply(calculateBeeEnergyCost(energyPerTick), tickMultiplier);
	}

	/** 计算一个真实游戏刻内所有 active 蜜蜂槽位的总能耗。 */
	static long calculateBatchEnergyCost(long energyPerTick, int activeSlots, int tickMultiplier) {
		if (activeSlots <= 0) return 0L;
		return saturatingMultiply(calculateAcceleratedEnergyCost(energyPerTick, tickMultiplier), activeSlots);
	}

	/**
	 * Shares the affordable virtual bee ticks evenly across all otherwise runnable bees.
	 * <p>
	 * Requiring one bee to afford the complete accelerator batch makes a 256x batch an
	 * all-or-nothing purchase. A power source that can sustain several normal bee ticks then
	 * advances no bee at all, or advances a rotating subset on alternating game ticks. Splitting
	 * the same FE budget into a base allocation and a one-tick remainder keeps every bee working
	 * whenever the available energy can sustain at least one tick per bee.
	 */
	static BeeTickAllocation allocateBeeTicks(long availableEnergy, long energyPerBeeTick,
			int runnableBees, int requestedTicksPerBee) {
		if (runnableBees <= 0 || requestedTicksPerBee <= 0) {
			return BeeTickAllocation.NONE;
		}

		long totalRequestedTicks = SaturatingMath.saturatingMultiply(runnableBees, requestedTicksPerBee);
		long perTick = Math.max(0L, energyPerBeeTick);
		long affordableTicks = perTick == 0L
				? totalRequestedTicks
				: Math.min(totalRequestedTicks, Math.max(0L, availableEnergy) / perTick);
		int ticksPerBee = (int) Math.min(requestedTicksPerBee, affordableTicks / runnableBees);
		int beesWithExtraTick = ticksPerBee >= requestedTicksPerBee
				? 0
				: (int) (affordableTicks % runnableBees);
		long energyUsed = SaturatingMath.saturatingMultiply(affordableTicks, perTick);
		return new BeeTickAllocation(ticksPerBee, beesWithExtraTick, energyUsed);
	}

	record BeeTickAllocation(int ticksPerBee, int beesWithExtraTick, long energyUsed) {
		private static final BeeTickAllocation NONE = new BeeTickAllocation(0, 0, 0L);
	}

	/** 防止极端加速倍率下能耗/产出计数溢出为负数。 */
	static long saturatingMultiply(long value, int multiplier) {
		if (value <= 0 || multiplier <= 0) return 0L;
		if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
		return value * multiplier;
	}

	/** 防止待产出计数溢出；溢出时保留可表示的最大值，避免负数导致永久跳过刷新。 */
	static int saturatingAdd(int value, int amount) {
		if (amount <= 0) return value;
		if (value > Integer.MAX_VALUE - amount) return Integer.MAX_VALUE;
		return value + amount;
	}
}
