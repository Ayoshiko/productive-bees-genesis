package com.ayoshiko.productivebeesgenesis.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProgressDisplaySmootherTest {

	@Test
	void completionDrainsSmoothlyInsteadOfSnappingBack() {
		// 0.25s drain at a 20fps frame => 0.2 per frame, so a full bar does not
		// teleport back to the reset point in one frame.
		assertEquals(0.75D, ProgressDisplaySmoother.nextValue(0.95D, 0.05D, 0.05D));
		assertEquals(0.55D, ProgressDisplaySmoother.nextValue(0.75D, 0.0D, 0.05D));
		assertEquals(0.0D, ProgressDisplaySmoother.nextValue(0.2D, 0.0D, 0.05D));
	}

	@Test
	void drainNeverGoesBelowTheNewCycleTarget() {
		assertEquals(0.05D, ProgressDisplaySmoother.nextValue(0.1D, 0.05D, 0.05D));
		assertEquals(0.2D, ProgressDisplaySmoother.nextValue(0.4D, 0.2D, 0.05D));
	}

	@Test
	void activeCycleStillFillsAtTheConfiguredRate() {
		assertEquals(0.2D, ProgressDisplaySmoother.nextValue(0.1D, 0.2D, 0.05D));
		assertEquals(0.115D, ProgressDisplaySmoother.nextValue(0.1D, 0.8D, 0.001D), 0.000_001D);
	}
}
