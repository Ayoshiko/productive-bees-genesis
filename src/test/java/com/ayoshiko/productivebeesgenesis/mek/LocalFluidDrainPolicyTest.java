package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocalFluidDrainPolicyTest {

	@Test
	void onlyDrainsWhenTheNextCandidateBatchNoLongerFits() {
		assertFalse(LocalFluidDrainPolicy.shouldDrainAfterCommit(250L, 250L));
		assertTrue(LocalFluidDrainPolicy.shouldDrainAfterCommit(249L, 250L));
		assertTrue(LocalFluidDrainPolicy.shouldDrainAfterCommit(0L, 250L));
		assertTrue(LocalFluidDrainPolicy.shouldDrainAfterCommit(1_000L, 4_000L));
	}
}
