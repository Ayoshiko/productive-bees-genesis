package com.ayoshiko.productivebeesgenesis.apiary;

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

	/** 计算单只蜜蜂在当前真实游戏刻内（含加速批量）应扣除的能量。 */
	static long calculateAcceleratedEnergyCost(long energyPerTick, int tickMultiplier) {
		return saturatingMultiply(calculateBeeEnergyCost(energyPerTick), tickMultiplier);
	}

	/** 计算一个真实游戏刻内所有 active 蜜蜂槽位的总能耗。 */
	static long calculateBatchEnergyCost(long energyPerTick, int activeSlots, int tickMultiplier) {
		if (activeSlots <= 0) return 0L;
		return saturatingMultiply(calculateAcceleratedEnergyCost(energyPerTick, tickMultiplier), activeSlots);
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
