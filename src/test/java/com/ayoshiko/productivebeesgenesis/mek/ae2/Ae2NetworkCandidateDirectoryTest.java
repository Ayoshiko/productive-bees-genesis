package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2NetworkCandidateDirectoryTest {

	@AfterEach
	void resetDirectory() {
		Ae2NetworkCandidateDirectory.resetForTest();
	}

	@Test
	void refreshStateSharesOneGenerationWithinTheWindow() {
		Object inventory = new Object();
		Ae2NetworkCandidateDirectory.RefreshState state =
				new Ae2NetworkCandidateDirectory.RefreshState();

		assertTrue(state.refresh(inventory, 100L));
		assertEquals(1L, state.generation());
		assertFalse(state.refresh(inventory, 100L));
		assertFalse(state.refresh(inventory, 109L));
		assertEquals(1L, state.generation());
		assertTrue(state.refresh(inventory, 110L));
		assertEquals(2L, state.generation());
	}

	@Test
	void refreshStateRebuildsForNewSnapshotAndClockRollback() {
		Ae2NetworkCandidateDirectory.RefreshState state =
				new Ae2NetworkCandidateDirectory.RefreshState();
		Object first = new Object();
		Object second = new Object();

		assertTrue(state.refresh(first, 40L));
		assertTrue(state.refresh(second, 41L));
		assertTrue(state.refresh(second, 10L));
		assertEquals(3L, state.generation());
	}

}
