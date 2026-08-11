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
	void insertionQuantumExposesOneTickOfDemandPerMachine() {
		assertEquals(16, FactoryExternalInsertPolicy.insertionQuantum(64));
		assertEquals(1_024, FactoryExternalInsertPolicy.insertionQuantum(4_096));
		assertEquals(262_144, FactoryExternalInsertPolicy.insertionQuantum(1_048_576));
	}

	@Test
	void externalSimulationUsesRealSlotCapacityInsteadOfSixteenItemQuantum() {
		assertEquals(4_096L, FactoryExternalInsertPolicy.effectiveSlotLimit(4_096, 0));
		assertEquals(4_096L, FactoryExternalInsertPolicy.effectiveSlotLimit(4_096, 512));
	}
}
