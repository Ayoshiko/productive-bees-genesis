package com.ayoshiko.productivebeesgenesis.multiblock.production;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 首版单机容量；六种模板共用，不能按壳体体积倍乘。升级扩容另走有版本的资产迁移。 */
public final class CombinedMachineCapacity {
	public static final int VERSION = 1;
	public static final int BEE_SLOTS = 6, LANES = 3, ITEM_SLOTS = 27, FLUID_TANKS = 4, TANK_CAPACITY = 16_000;
	public static final long ENERGY_CAPACITY = 1_000_000;
	public static CombinedMachineWork empty(UUID machine, long generation) {
		return new CombinedMachineWork(machine, generation, 0, BEE_SLOTS, LANES, 0, ENERGY_CAPACITY,
				List.of(), Map.of(), FiniteProductBuffer.empty(ITEM_SLOTS, FLUID_TANKS, TANK_CAPACITY));
	}
	public static void validate(CombinedMachineWork work) {
		if (work.beeSlots() != BEE_SLOTS || work.lanes() != LANES || work.energyCapacity() != ENERGY_CAPACITY
				|| work.buffer().items().size() != ITEM_SLOTS || work.buffer().fluids().size() != FLUID_TANKS
				|| work.buffer().tankCapacity() != TANK_CAPACITY) throw new IllegalArgumentException("Unsupported combined machine capacity");
	}
	private CombinedMachineCapacity() { }
}
