package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MekCentrifugeEnergyScalingTest {

	@Test
	void includesProcessesParallelismAndAcceleration() {
		assertEquals(50L * 65_536L * 4L * 19L * 256L,
				MekCentrifugeEnergyScaling.requiredEnergyPerTick(50L, 65_536, 4, 19, 256));
	}

	@Test
	void extremeConfiguredDemandSaturates() {
		assertEquals(Long.MAX_VALUE, MekCentrifugeEnergyScaling.requiredEnergyPerTick(
				Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 256));
		assertEquals(0L, MekCentrifugeEnergyScaling.requiredEnergyPerTick(0L, 1, 1, 1, 256));
	}
}
