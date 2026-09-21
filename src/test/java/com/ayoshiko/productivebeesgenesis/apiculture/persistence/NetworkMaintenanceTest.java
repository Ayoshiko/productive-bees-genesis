package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkMaintenanceTest {
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static NetworkSavedData account(long amount) {
		return NetworkSavedData.create(NetworkCheckpoint.empty(CheckpointTestData.identity()).configureEnergy(Long.MAX_VALUE).receiveEnergy(amount),
				Runnable::run, (path, payload) -> { });
	}
	private static void work(NetworkSavedData data, long tick, long cost, long fee) {
		var before = data.checkpoint(); data.publishMaintainedWork(before, before.spendMaintenance(cost), tick, fee);
	}
	@Test void severalMembersShareOneFeeAndSkippedTicksNeverAccumulateDebt() {
		var data = account(100); var ledger = data.checkpoint().ledger();
		work(data, 10, 20, 7); work(data, 10, 10, 7);
		assertEquals(63, data.checkpoint().energy().stored());
		work(data, 10000, 10, 7); assertEquals(46, data.checkpoint().energy().stored());
		assertSame(ledger, data.checkpoint().ledger());
	}
	@Test void simulationAndUnfundedOrStaleCandidatesCannotConsumeFeeOrReceipt() {
		var data = account(10); var before = data.checkpoint();
		assertEquals(7, data.maintenanceDue(1, 7)); assertEquals(7, data.maintenanceDue(1, 7));
		assertThrows(IllegalArgumentException.class, () -> data.publishMaintainedWork(before, before.spendMaintenance(5), 1, 7));
		assertSame(before, data.checkpoint()); assertEquals(7, data.maintenanceDue(1, 7));
		work(data, 1, 3, 7); assertEquals(0, data.checkpoint().energy().stored());
		assertThrows(IllegalArgumentException.class, () -> data.publishMaintainedWork(before, before.spendMaintenance(1), 1, 7));
		assertEquals(0, data.checkpoint().energy().stored());
	}
	@Test void priceIsFrozenAfterPaymentAndOtherNetworksStillPay() {
		var first = account(100); var second = account(100);
		work(first, 1, 10, 7); assertEquals(0, first.maintenanceDue(1, 99));
		work(second, 1, 10, 7); assertEquals(83, second.checkpoint().energy().stored());
		assertEquals(99, first.maintenanceDue(2, 99));
	}
	@Test void longBoundaryDoesNotOverflowAndClosedAuthoritiesRejectWork() {
		var data = account(Long.MAX_VALUE); work(data, 1, 1, Long.MAX_VALUE - 1);
		assertEquals(0, data.checkpoint().energy().stored());
		assertThrows(IllegalArgumentException.class, () -> data.maintenanceDue(2, -1));
		data.closeAuthority(); assertThrows(IllegalStateException.class, () -> data.maintenanceDue(1, 1));
	}
	@Test void checkpointRetainsDebitButNewServerSessionHasNoOldTickCredit() {
		var data = account(100); work(data, 5, 10, 7);
		var codec = new NetworkCheckpointCodec(key -> { });
		var restored = codec.decode(NetworkCheckpointCodec.encode(data.checkpoint()));
		assertEquals(83, restored.energy().stored());
		var loaded = NetworkSavedData.loaded(restored, new CheckpointSaveQueue(Runnable::run, (path, payload) -> { }));
		assertEquals(7, loaded.maintenanceDue(5, 7)); work(loaded, 5, 10, 7);
		assertEquals(66, loaded.checkpoint().energy().stored());
	}
	@Test void zeroFeePreservesWorkPriceAndCrossThreadAccessIsRejected() throws Exception {
		var data = account(100); work(data, 1, 10, 0); assertEquals(90, data.checkpoint().energy().stored());
		var task = new java.util.concurrent.FutureTask<>(() -> assertThrows(IllegalStateException.class, () -> data.maintenanceDue(1, 7)));
		var thread = new Thread(task); thread.start(); task.get(); thread.join();
	}
}
