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
}
