package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 零耗时配方（CREATIVE 升级）合并推进的数学与窗口生命周期回归。
 * <p>
 * 背景：CREATIVE 让 {@code getTicksRequired()} 返回 0，Mekanism 每个配方 tick 恰好
 * 完成一个完整周期，原批量快速路径必然第一次推进就退出 ——
 * 等价于每虚拟刻一次完整 calculateOperationsThisTick + 一次输出槽 insertItem
 * （spark XnLugba3Cw：1940ms + 1208ms）。
 */
class ZeroTickBatchMathTest {

	@Test
	void partialBatchAndLowEnergyMatchSequentialTicks() {
		assertEquals(18, ZeroTickBatchMath.coalescedEnergyCost(1, 33, 32));
		assertEquals(33, ZeroTickBatchMath.affordableCoalescedOperations(1, 32, 18, 2));
		assertEquals(1, ZeroTickBatchMath.affordableCoalescedOperations(10, 32, 10, 16));
		for (long energy = 0; energy <= 40; energy++) {
			long remaining = energy;
			int operations = 0;
			for (int i = 0; i < 3; i++) {
				int next = MekCentrifugeEnergyScaling.affordableOperations(1, 32, remaining);
				operations += next;
				remaining -= MekCentrifugeEnergyScaling.parallelEnergyCost(1, next);
			}
			assertEquals(operations, ZeroTickBatchMath.affordableCoalescedOperations(1, 32, energy, 3));
		}
	}

	/**
	 * 关键语义：边际计费曲线是次线性的，必须先算单刻可承担量再乘回倍数。
	 * 若直接把 ops×factor 交给曲线，1024 倍加速几乎不耗电。
	 */
	@Test
	void coalescedAffordabilityStaysPerTickPriced() {
		long energyPerOperation = 100L;
		int baseOps = 64;
		int factor = 16;
		long perTickCost = MekCentrifugeEnergyScaling.batchEnergyCost(energyPerOperation, baseOps, 1);
		long stored = perTickCost * factor;

		int coalesced = ZeroTickBatchMath.affordableCoalescedOperations(
				energyPerOperation, baseOps, stored, factor);
		assertEquals(baseOps * factor, coalesced,
				"恰好够 factor 个满刻时应放行 baseOps × factor 个操作");

		int naive = MekCentrifugeEnergyScaling.affordableOperations(
				energyPerOperation, baseOps * factor, stored);
		assertTrue(naive >= coalesced,
				"直接对总操作数套次线性曲线只会更宽松，这正是必须按刻定价的原因: naive="
						+ naive + " coalesced=" + coalesced);
	}

}
