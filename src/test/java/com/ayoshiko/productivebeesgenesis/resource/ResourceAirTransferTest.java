package com.ayoshiko.productivebeesgenesis.resource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResourceAirTransferTest {
	@Test void leavesRoomBelowBothPressureLimits() {
		assertEquals(4999, ResourceAirTransfer.room(0, 1000, 5, 7));
		assertEquals(4999, ResourceAirTransfer.room(0, 1000, 7, 5));
		assertEquals(73, ResourceAirTransfer.room(4926, 1000, 5, 7));
		assertEquals(0, ResourceAirTransfer.room(4999, 1000, 5, 7));
		assertEquals(0, ResourceAirTransfer.room(8000, 1000, 5, 7));
	}

	@Test void vacuumIsFilledWithoutTreatingPressureAsStoredQuantity() {
		assertEquals(5999, ResourceAirTransfer.room(-1000, 1000, 5, 7));
		assertEquals(0, ResourceAirTransfer.room(-1001, 1000, 5, 7));
	}

	@Test void invalidReceiverMetadataCannotAuthorizeDelivery() {
		for (float pressure : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
			assertEquals(0, ResourceAirTransfer.room(0, 1000, pressure, 7));
			assertEquals(0, ResourceAirTransfer.room(0, 1000, 5, pressure));
		}
		assertEquals(0, ResourceAirTransfer.room(0, 0, 5, 7));
		assertEquals(0, ResourceAirTransfer.room(0, -1, 5, 7));
	}

	@Test void largeVolumesCannotOverflowTheNativeIntegerBalance() {
		assertEquals(1, ResourceAirTransfer.room(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 20, 30));
		assertEquals(Integer.MAX_VALUE, ResourceAirTransfer.room(-Integer.MAX_VALUE, Integer.MAX_VALUE, 20, 30));
		assertEquals(0, ResourceAirTransfer.room(Integer.MAX_VALUE, Integer.MAX_VALUE, 20, 30));
	}

	@Test void computedOffersRemainStrictlyBelowNativeFloatPressureThreshold() {
		for (int volume : new int[]{1, 7, 1000, 16000, 16777217, Integer.MAX_VALUE}) {
			for (float pressure : new float[]{0.1f, 1, 5, 7, 20, 30}) {
				int amount = ResourceAirTransfer.room(0, volume, pressure, pressure);
				assertTrue(amount / (float) volume < pressure, () -> "Unsafe pressure for volume " + volume);
			}
		}
	}

	@Test void exactSynchronousAcceptanceCommitsOneOffer() {
		var stored = new AtomicInteger(100);
		var calls = new AtomicInteger();
		assertEquals(73, ResourceAirTransfer.deliver(100, 73, stored::get, amount -> {
			calls.incrementAndGet(); stored.addAndGet(amount);
		}));
		assertEquals(173, stored.get());
		assertEquals(1, calls.get());
	}

	@Test void partialZeroAndDelayedAcceptanceAreUncertainRatherThanRetryableRefusals() {
		for (int accepted : new int[]{0, 13, 101}) {
			var stored = new AtomicInteger();
			var calls = new AtomicInteger();
			assertThrows(IllegalStateException.class, () -> ResourceAirTransfer.deliver(0, 100, stored::get, amount -> {
				calls.incrementAndGet(); stored.addAndGet(accepted);
			}));
			assertEquals(1, calls.get());
			assertEquals(accepted, stored.get());
		}
	}

	@Test void exceptionAfterMutationDoesNotRetryOrUndoTheReceiver() {
		var stored = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> ResourceAirTransfer.deliver(0, 100, stored::get, amount -> {
			stored.addAndGet(13); throw new IllegalStateException("after acceptance");
		}));
		assertEquals(13, stored.get());
	}

	@Test void changedReceiverAndInvalidOffersNeverInvokeTheMutation() {
		var calls = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> ResourceAirTransfer.deliver(10, 100, () -> 11, calls::addAndGet));
		for (long invalid : new long[]{0, -1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> ResourceAirTransfer.deliver(0, invalid, () -> 0, calls::addAndGet));
		}
		assertThrows(IllegalArgumentException.class, () -> ResourceAirTransfer.deliver(Integer.MAX_VALUE, 1, () -> Integer.MAX_VALUE, calls::addAndGet));
		assertEquals(0, calls.get());
	}
}
