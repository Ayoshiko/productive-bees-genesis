package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductWithdrawalCheckpointTest {
	private static final ProductKey KEY = key(0), OTHER = key(1);
	private static ProductKey key(int value) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:item_" + value), new CompoundTag()); }
	private static LedgerCheckpoint ledger(ProductAmount amount, long reserved) {
		return new LedgerCheckpoint(7, Map.of(KEY, amount, OTHER, ProductAmount.of(42)), reserved == 0 ? List.of() : List.of(
				new LedgerCheckpoint.Pending(UUID.randomUUID(), 0, LedgerTransaction.State.PAID, Map.of(KEY, ProductAmount.of(reserved)), Map.of(OTHER, ProductAmount.of(1)))));
	}
	@Test void exactWithdrawalPreservesReservationsAndEveryOtherBalance() {
		var before = ledger(ProductAmount.of(100), 90); var after = before.withdrawExact(KEY, ProductAmount.of(10));
		assertEquals(ProductAmount.ZERO, after.available(KEY)); assertEquals(ProductAmount.of(90), after.balances().get(KEY));
		assertSame(before.transactions(), after.transactions()); assertEquals(before.balances().get(OTHER), after.balances().get(OTHER));
		assertEquals(ProductAmount.of(100), before.balances().get(KEY)); assertEquals(Set.of(KEY), after.changesSince(before));
		assertThrows(IllegalArgumentException.class, () -> after.withdrawExact(KEY, ProductAmount.of(1)));
	}
	@Test void finiteDeliveryNeverSaturatesTheLargeOwnedAmount() {
		var amount = BigInteger.ONE.shiftLeft(160).add(BigInteger.valueOf(37)); var before = ledger(ProductAmount.of(amount), 3);
		var after = before.withdrawExact(KEY, ProductAmount.of(64));
		assertEquals(amount.subtract(BigInteger.valueOf(64)), after.balances().get(KEY).exact());
		assertEquals(amount.subtract(BigInteger.valueOf(67)), after.available(KEY).exact());
	}
	@Test void zeroMissingOverspendingAndRevisionOverflowDoNotChangeOldRoot() {
		var before = ledger(ProductAmount.of(5), 3);
		assertThrows(IllegalArgumentException.class, () -> before.withdrawExact(KEY, ProductAmount.ZERO));
		assertThrows(IllegalArgumentException.class, () -> before.withdrawExact(KEY, ProductAmount.of(3)));
		assertThrows(IllegalArgumentException.class, () -> before.withdrawExact(key(2), ProductAmount.of(1)));
		var last = new LedgerCheckpoint(Long.MAX_VALUE, before.balances(), before.transactions());
		assertThrows(ArithmeticException.class, () -> last.withdrawExact(KEY, ProductAmount.of(1)));
		assertEquals(before.balances(), last.balances());
	}
	@Test void removingFinalUnitKeepsEarlierSnapshotsAndConcurrentCandidatesIndependent() {
		var before = ledger(ProductAmount.of(1), 0); var a = before.withdrawExact(KEY, ProductAmount.of(1)); var b = before.withdrawExact(OTHER, ProductAmount.of(2));
		assertFalse(a.balances().containsKey(KEY)); assertEquals(ProductAmount.of(1), b.balances().get(KEY));
		assertEquals(ProductAmount.of(42), a.balances().get(OTHER)); assertEquals(ProductAmount.of(40), b.balances().get(OTHER));
		assertEquals(2, before.balances().size());
	}
	@Test void stockIndexUpdatesOnlyTheWithdrawnKeyAtScale() {
		Map<ProductKey, ProductAmount> balances = new ConcurrentHashMap<>();
		for (int i = 0; i < 10_000; i++) balances.put(key(i), ProductAmount.of(5));
		var before = new LedgerCheckpoint(0, balances, List.of()); var index = new ProcessingStockIndex(before);
		while (!index.ready()) index.step(); var view = index.view();
		var after = before.withdrawExact(KEY, ProductAmount.of(2)); index.update(after);
		assertTrue(index.step(), "A single withdrawal must not restart the 10,000-key scan");
		assertEquals(ProductAmount.of(3), index.view().amount(KEY)); assertEquals(ProductAmount.of(5), view.amount(KEY));
		assertEquals(10_000, after.balances().size());
	}
	@Test void checkpointRejectsStaleRevisionAndRoundTripsWithoutTouchingOtherAuthority() {
		var before = new NetworkCheckpoint(CheckpointTestData.identity(), 12, 0, ledger(ProductAmount.of(10), 3),
				List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY).configureEnergy(1000).receiveEnergy(123);
		assertSame(before, before.withdrawProduct(6, KEY, 1)); assertSame(before, before.withdrawProduct(7, KEY, 8));
		var after = before.withdrawProduct(7, KEY, 7);
		assertSame(after, after.withdrawProduct(7, KEY, 1)); assertSame(before.energy(), after.energy());
		assertSame(before.ownedMachines(), after.ownedMachines()); assertSame(before.scheduler(), after.scheduler());
		assertSame(before.transfers(), after.transfers()); assertSame(before.discoveries(), after.discoveries());
		var codec = new NetworkCheckpointCodec(key -> { }); assertEquals(after, codec.decode(NetworkCheckpointCodec.encode(after)));
	}
	@Test void repeatedFiniteDeliveriesMatchAnIndependentBigIntegerReference() {
		var value = BigInteger.ONE.shiftLeft(100); var current = ledger(ProductAmount.of(value), 1234); var random = new Random(20260922);
		for (int i = 0; i < 2000; i++) {
			int take = 1 + random.nextInt(1000); value = value.subtract(BigInteger.valueOf(take));
			current = current.withdrawExact(KEY, ProductAmount.of(take));
			assertEquals(value, current.balances().get(KEY).exact());
			assertEquals(value.subtract(BigInteger.valueOf(1234)), current.available(KEY).exact());
		}
	}
}
