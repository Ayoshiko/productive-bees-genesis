package com.ayoshiko.productivebeesgenesis.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FactoryExternalInsertPolicyTest {

	@Test
	void workingSetKeepsFourTicksOfMeasuredDemand() {
		assertEquals(64, FactoryExternalInsertPolicy.recommendedWorkingSet(1, 1, 1));
		assertEquals(4_096, FactoryExternalInsertPolicy.recommendedWorkingSet(4, 256, 1));
		assertEquals(1_048_576, FactoryExternalInsertPolicy.recommendedWorkingSet(256, 256, 4));
	}

	@Test
	void workingSetSaturatesInsteadOfOverflowing() {
		assertEquals(Integer.MAX_VALUE,
				FactoryExternalInsertPolicy.recommendedWorkingSet(Integer.MAX_VALUE, Integer.MAX_VALUE,
						Integer.MAX_VALUE));
	}

	@Test
	void externalInsertionSeesWorkingSetInsteadOfFullSlotCapacity() {
		// 槽位真实上限 100 万，工作集 4096：外部只能填到 4096，机器内部写入不受影响
		assertEquals(4_096L, FactoryExternalInsertPolicy.effectiveSlotLimit(1_000_000, 0, 4_096));
		assertEquals(4_096L, FactoryExternalInsertPolicy.effectiveSlotLimit(1_000_000, 512, 4_096));
	}

	@Test
	void slotCapacitySmallerThanWorkingSetStillWins() {
		assertEquals(64L, FactoryExternalInsertPolicy.effectiveSlotLimit(64, 0, 4_096));
	}

	@Test
	void workingSetNeverDropsBelowOneVanillaStack() {
		// 物流模组按 min(getSlotLimit, maxStackSize)=64 规划，低于 64 会造成"已提交 < 已规划"
		assertEquals(64L, FactoryExternalInsertPolicy.effectiveSlotLimit(1_000_000, 0, 1));
	}

	@Test
	void overfilledSlotReportsItsOwnCountSoExternalSpaceIsZero() {
		// 机器内部（INTERNAL）可以写满到真实上限；此时外部看到的上限等于现有数量 → 空间 0
		assertEquals(9_000L, FactoryExternalInsertPolicy.effectiveSlotLimit(1_000_000, 9_000, 4_096));
	}
}
