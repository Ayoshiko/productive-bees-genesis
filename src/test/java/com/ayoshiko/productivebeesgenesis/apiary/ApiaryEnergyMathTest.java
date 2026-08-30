package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApiaryEnergyMathTest {

	@Test
	void accelerationChargesEveryActiveBeeSlot() {
		assertEquals(50L * 256L * 64L,
				ApiaryEnergyMath.calculateBatchEnergyCost(50L, 64, 256));
	}

	@Test
	void extremeConfiguredEnergySaturates() {
		assertEquals(Long.MAX_VALUE,
				ApiaryEnergyMath.calculateBatchEnergyCost(Long.MAX_VALUE, Integer.MAX_VALUE, 256));
		assertEquals(0L, ApiaryEnergyMath.calculateBatchEnergyCost(-1L, 64, 256));
	}

	@Test
	void creativeUpgradeExplicitlyDisablesBeeEnergy() {
		assertEquals(0L, ApiaryEnergyMath.calculateBeeEnergyCost(50L, true));
		assertEquals(50L, ApiaryEnergyMath.calculateBeeEnergyCost(50L, false));
	}

	@Test
	void partialBatchEnergyIsSharedAcrossEveryRunnableBee() {
		ApiaryEnergyMath.BeeTickAllocation allocation = ApiaryEnergyMath.allocateBeeTicks(
				50L * 64L * 5L, 50L, 64, 256);

		assertEquals(5, allocation.ticksPerBee());
		assertEquals(0, allocation.beesWithExtraTick());
		assertEquals(50L * 64L * 5L, allocation.energyUsed());
	}

	@Test
	void remainderTicksRotateWithoutExceedingTheEnergyBudget() {
		ApiaryEnergyMath.BeeTickAllocation allocation = ApiaryEnergyMath.allocateBeeTicks(
				50L * (64L * 5L + 17L), 50L, 64, 256);

		assertEquals(5, allocation.ticksPerBee());
		assertEquals(17, allocation.beesWithExtraTick());
		assertEquals(50L * (64L * 5L + 17L), allocation.energyUsed());
	}

	@Test
	void freeEnergyAndFullBudgetPreserveTheRequestedAcceleration() {
		ApiaryEnergyMath.BeeTickAllocation free = ApiaryEnergyMath.allocateBeeTicks(0L, 0L, 64, 256);
		ApiaryEnergyMath.BeeTickAllocation full = ApiaryEnergyMath.allocateBeeTicks(
				50L * 64L * 256L, 50L, 64, 256);

		assertEquals(256, free.ticksPerBee());
		assertEquals(0L, free.energyUsed());
		assertEquals(256, full.ticksPerBee());
		assertEquals(0, full.beesWithExtraTick());
	}
}
