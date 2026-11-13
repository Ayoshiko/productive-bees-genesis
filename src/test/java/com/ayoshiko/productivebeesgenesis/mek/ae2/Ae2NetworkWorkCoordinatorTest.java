package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Ae2NetworkWorkCoordinatorTest {

	@AfterEach
	void resetCoordinator() {
		Ae2NetworkWorkCoordinator.resetForTest();
	}

	@Test
	void staleWaitingReservationIsConsumedAndCannotFreezeRotation() {
		Object network = new Object();
		Object handle = Ae2NetworkWorkCoordinator.resolve(network);
		long active = Ae2NetworkWorkCoordinator.createWorkerId();
		long vanished = Ae2NetworkWorkCoordinator.createWorkerId();
		Ae2NetworkWorkCoordinator.recordResolvedCost(handle, 0, 1_000_000, 150_000);
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, active, 1));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, vanished, 1));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, active, 2));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, active, 3));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, active, 204));
	}

	@Test
	void slowOutputsCannotSpendInputBudgetOrOwnInputTurns() {
		Object handle = Ae2NetworkWorkCoordinator.resolve(new Object());
		long outputOnly = Ae2NetworkWorkCoordinator.createWorkerId();
		long first = Ae2NetworkWorkCoordinator.createWorkerId();
		long second = Ae2NetworkWorkCoordinator.createWorkerId();
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, outputOnly, 10));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 10));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, second, 10));
		Ae2NetworkWorkCoordinator.recordResolvedCost(handle, 10, 3_000_000, 150_000);
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, outputOnly, 10));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 10));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, second, 10));
		Ae2NetworkWorkCoordinator.recordResolvedExtractCost(handle, 10, 1_000_000, 300_000);
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 11));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, second, 11));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 12));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, second, 12));
	}

	@Test
	void releaseAlsoRemovesInputWorkerFromFutureTurns() {
		Object network = new Object();
		Object handle = Ae2NetworkWorkCoordinator.resolve(network);
		long first = Ae2NetworkWorkCoordinator.createWorkerId();
		long second = Ae2NetworkWorkCoordinator.createWorkerId();
		Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 30);
		Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, second, 30);
		Ae2NetworkWorkCoordinator.recordResolvedExtractCost(handle, 30, 1_000_000, 300_000);
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 31));
		Ae2NetworkWorkCoordinator.release(network, second);
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireExtractResolved(handle, first, 32));
	}

}
