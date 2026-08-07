package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2InputPullerTest {

	@Test
	void accelerationShortensConfiguredIntervalWithoutDroppingBelowOneGameTick() {
		assertEquals(10, Ae2PullFairnessPolicy.effectiveInterval(10, 1));
		assertEquals(5, Ae2PullFairnessPolicy.effectiveInterval(10, 2));
		assertEquals(1, Ae2PullFairnessPolicy.effectiveInterval(10, 256));
	}

	@Test
	void highAccelerationUsesBoundedPerSlotQuota() {
		assertEquals(8_192L, Ae2PullFairnessPolicy.perSlotQuota(1_024L, 256, 32));
		assertEquals(131_072L, Ae2PullFairnessPolicy.perSlotQuota(16_384L, 256, 32));
		assertEquals(64L, Ae2PullFairnessPolicy.perSlotQuota(1_024L, 1, 32));
	}

	@Test
	void pushStateRotatesSlotsAndDeduplicatesAcceleratedSubTicks() {
		Ae2PushStateHolder state = new Ae2PushStateHolder();
		assertEquals(0, state.getAndAdvanceInputSlotRotation(3));
		assertEquals(1, state.getAndAdvanceInputSlotRotation(3));
		assertEquals(2, state.getAndAdvanceInputSlotRotation(3));
		assertEquals(0, state.getAndAdvanceInputSlotRotation(3));

		assertTrue(state.tryStartItemPush(100));
		assertFalse(state.tryStartItemPush(100));
		assertTrue(state.tryStartItemPush(101));
		assertTrue(state.tryStartFluidPush(100));
		assertFalse(state.tryStartFluidPush(100));
	}

}
