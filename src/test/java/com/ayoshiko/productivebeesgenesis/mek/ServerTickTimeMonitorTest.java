package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerTickTimeMonitorTest {

	@Test
	void healthyServerKeepsFullBatchBudget() {
		assertEquals(1.0, ServerTickTimeMonitor.factorForMspt(50.0, true));
		assertEquals(0.9, ServerTickTimeMonitor.factorForMspt(55.0, true), 1.0e-9);
	}

	@Test
	void sustainedHighMsptDegradesTowardTenPercent() {
		assertEquals(0.55, ServerTickTimeMonitor.factorForMspt(75.0, false), 1.0e-9);
		assertEquals(0.1, ServerTickTimeMonitor.factorForMspt(100.0, false), 1.0e-9);
		assertEquals(0.1, ServerTickTimeMonitor.factorForMspt(250.0, false), 1.0e-9);
	}

	@Test
	void hysteresisPreventsThresholdOscillation() {
		assertTrue(ServerTickTimeMonitor.nextFullFactorState(true, 60.0));
		assertFalse(ServerTickTimeMonitor.nextFullFactorState(true, 60.1));
		assertFalse(ServerTickTimeMonitor.nextFullFactorState(false, 45.0));
		assertTrue(ServerTickTimeMonitor.nextFullFactorState(false, 44.9));
	}

	@Test
	void responsiveAverageReactsToSpikeAndThenRecovers() {
		double responsive = ServerTickTimeMonitor.nextResponsiveMspt(50.0, 200.0);
		assertEquals(80.0, responsive, 1.0e-9);
		assertEquals(74.0, ServerTickTimeMonitor.nextResponsiveMspt(responsive, 50.0), 1.0e-9);
	}
}
