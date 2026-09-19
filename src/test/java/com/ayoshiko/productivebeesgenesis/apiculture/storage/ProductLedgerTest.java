package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistryTest.*;

class ProductLedgerTest {
	private static final ProductKey RAW = item("test:raw");
	private static final ProductKey PRODUCT = item("test:product");
	private static ProductAmount amount(long value) { return ProductAmount.of(value); }

	@Test
	void frozenReservationIndexKeepsEveryPaymentStateAcrossMassSettlementAndPolicyReload() {
		var registry = new ProductPolicyRegistry(policy(1, RAW, PRODUCT)); var ledger = new ProductLedger(registry, 1024);
		ledger.insert(RAW, amount(2048), ProductLedger.Action.EXECUTE); var pending = new ArrayList<LedgerTransaction>();
		for (int i = 0; i < 1024; i++) {
			var transaction = ledger.prepare(Map.of(RAW, amount(1)), Map.of(PRODUCT, amount(1)), 1); pending.add(transaction);
			if (i % 2 == 0) assertTrue(ledger.markPaid(transaction));
		}
		var frozen = ledger.checkpoint(); registry.replace(policy(2, RAW, PRODUCT));
		for (int i = 0; i < pending.size(); i++) assertEquals(i % 2 == 0, ledger.commit(pending.get(i)));
		ledger.validateCheckpointPolicy(registry);
		assertTrue(ledger.checkpoint().transactions().isEmpty()); assertEquals(amount(1536), ledger.available(RAW));
		assertEquals(amount(512), ledger.available(PRODUCT)); assertEquals(amount(2048), frozen.balances().get(RAW));
		assertEquals(512, frozen.transactions().stream().filter(value -> value.state() == LedgerTransaction.State.PAID).count());
		assertEquals(512, frozen.transactions().stream().filter(value -> value.state() == LedgerTransaction.State.RESERVED).count());
		var restored = ProductLedger.restore(registry, 1024, frozen); assertEquals(amount(1024), restored.available(RAW));
		assertEquals(frozen, restored.checkpoint());
	}

	@Test
	void captureAndTwoRestoredAuthoritiesKeepBalancesAndPaymentStatesAtTheSameBoundary() {
		var policy = new ProductPolicyRegistry(policy(1, RAW, PRODUCT));
		var ledger = new ProductLedger(policy, 4);
		ledger.insert(RAW, amount(100), ProductLedger.Action.EXECUTE);
		var pending = ledger.prepare(Map.of(RAW, amount(80)), Map.of(PRODUCT, amount(8)), 1);
		var reserved = ledger.checkpoint();
		ledger.markPaid(pending); var paid = ledger.checkpoint(); ledger.commit(pending);
		var a = ProductLedger.restore(policy, 4, paid); var b = ProductLedger.restore(policy, 4, paid);
		assertEquals(LedgerTransaction.State.RESERVED, reserved.transactions().getFirst().state());
		assertEquals(LedgerTransaction.State.PAID, paid.transactions().getFirst().state());
		assertEquals(amount(100), paid.balances().get(RAW)); assertFalse(paid.balances().containsKey(PRODUCT));
		assertTrue(a.commit(a.pending(pending.id())));
		assertEquals(amount(8), a.extract(PRODUCT, amount(100), ProductLedger.Action.EXECUTE));
		assertEquals(amount(20), b.available(RAW)); assertEquals(ProductAmount.ZERO, b.available(PRODUCT));
		assertEquals(paid, b.checkpoint()); assertEquals(amount(8), ledger.available(PRODUCT));
		assertTrue(b.commit(b.pending(pending.id()))); assertEquals(amount(8), b.available(PRODUCT));
	}

	@Test
	void simulationDoesNotAllocateReservationsChangeRevisionOrBalance() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, RAW, PRODUCT)), 4);
		ledger.insert(RAW, amount(100), ProductLedger.Action.EXECUTE);
		var before = ledger.snapshot();
		assertEquals(amount(20), ledger.insert(PRODUCT, amount(20), ProductLedger.Action.SIMULATE));
		assertEquals(amount(100), ledger.extract(RAW, amount(150), ProductLedger.Action.SIMULATE));
		assertTrue(ledger.canPrepare(Map.of(RAW, amount(30)), Map.of(PRODUCT, amount(3)), 1));
		assertEquals(before, ledger.snapshot());
	}
	@Test
	void reservationsPreventDoubleSpendingAndCommitIsIdempotent() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, RAW, PRODUCT)), 4);
		ledger.insert(RAW, amount(100), ProductLedger.Action.EXECUTE);
		var work = ledger.prepare(Map.of(RAW, amount(80)), Map.of(PRODUCT, amount(8)), 1);
		assertNotNull(work);
		assertNull(ledger.prepare(Map.of(RAW, amount(80)), Map.of(PRODUCT, amount(8)), 1));
		assertEquals(amount(20), ledger.extract(RAW, amount(100), ProductLedger.Action.EXECUTE));
		assertTrue(ledger.commit(work));
		var after = ledger.snapshot();
		assertTrue(ledger.commit(work));
		assertFalse(ledger.cancel(work));
		assertEquals(after, ledger.snapshot());
		assertEquals(amount(8), ledger.available(PRODUCT));
		assertEquals(0, after.pendingTransactions());
		var foreign = new ProductLedger(new ProductPolicyRegistry(policy(1, RAW, PRODUCT)), 4);
		assertThrows(IllegalArgumentException.class, () -> foreign.commit(work));
	}
	@Test
	void reloadCancelsUnpaidWorkButPaidOutputsAndHistoricalWithdrawalSurvive() {
		var registry = new ProductPolicyRegistry(policy(1, RAW, PRODUCT));
		var ledger = new ProductLedger(registry, 4);
		ledger.insert(RAW, amount(100), ProductLedger.Action.EXECUTE);
		var unpaid = ledger.prepare(Map.of(RAW, amount(10)), Map.of(PRODUCT, amount(1)), 1);
		var paid = ledger.prepare(Map.of(RAW, amount(20)), Map.of(PRODUCT, amount(2)), 1);
		assertTrue(ledger.markPaid(paid));
		assertFalse(ledger.cancel(paid));
		registry.replace(policy(2));
		assertFalse(ledger.commit(unpaid));
		assertTrue(ledger.commit(paid));
		assertEquals(ProductAmount.ZERO, ledger.insert(PRODUCT, amount(1), ProductLedger.Action.EXECUTE));
		assertEquals(amount(2), ledger.extract(PRODUCT, amount(2), ProductLedger.Action.EXECUTE));
		assertEquals(amount(80), ledger.available(RAW));
	}
	@Test
	void seededTransactionsMatchIndependentBigIntegerAccounts() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, RAW, PRODUCT)), 32);
		var active = new ArrayList<LedgerTransaction>();
		Map<ProductKey, BigInteger> model = new ConcurrentHashMap<>();
		var initial = BigInteger.ONE.shiftLeft(160);
		ledger.insert(RAW, ProductAmount.of(initial), ProductLedger.Action.EXECUTE); model.put(RAW, initial);
		var random = new Random(0xD06BEE);
		for (int step = 0; step < 5000; step++) {
			ProductKey key = random.nextBoolean() ? RAW : PRODUCT;
			long count = random.nextInt(1000) + 1;
			int operation = random.nextInt(5);
			if (operation == 0) {
				ledger.insert(key, amount(count), ProductLedger.Action.EXECUTE);
				model.merge(key, BigInteger.valueOf(count), BigInteger::add);
			} else if (operation == 1) {
				BigInteger reserved = active.stream().map(tx -> tx.inputs().getOrDefault(key, ProductAmount.ZERO).exact()).reduce(BigInteger.ZERO, BigInteger::add);
				BigInteger expected = model.getOrDefault(key, BigInteger.ZERO).subtract(reserved).min(BigInteger.valueOf(count));
				assertEquals(expected, ledger.extract(key, amount(count), ProductLedger.Action.EXECUTE).exact());
				model.put(key, model.getOrDefault(key, BigInteger.ZERO).subtract(expected));
			} else if (operation == 2) {
				var tx = ledger.prepare(Map.of(key, amount(count)), Map.of(key == RAW ? PRODUCT : RAW, amount(count)), 1);
				if (tx != null) active.add(tx);
			} else if (!active.isEmpty()) {
				var tx = active.remove(random.nextInt(active.size()));
				if (operation == 3) {
					assertTrue(ledger.commit(tx));
					tx.inputs().forEach((k, v) -> model.put(k, model.getOrDefault(k, BigInteger.ZERO).subtract(v.exact())));
					tx.outputs().forEach((k, v) -> model.merge(k, v.exact(), BigInteger::add));
				} else assertTrue(ledger.cancel(tx));
			}
			var snapshot = ledger.snapshot();
			for (var k : new ProductKey[] {RAW, PRODUCT}) {
				assertEquals(model.getOrDefault(k, BigInteger.ZERO), snapshot.balances().getOrDefault(k, ProductAmount.ZERO).exact());
				BigInteger reserved = active.stream().map(tx -> tx.inputs().getOrDefault(k, ProductAmount.ZERO).exact()).reduce(BigInteger.ZERO, BigInteger::add);
				assertEquals(reserved, snapshot.reserved().getOrDefault(k, ProductAmount.ZERO).exact());
			}
			assertEquals(active.size(), snapshot.pendingTransactions());
		}
	}
	@Test
	void workBudgetAndOwnerThreadCannotBeBypassed() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, PRODUCT)), 1);
		var first = ledger.prepare(Map.of(), Map.of(PRODUCT, amount(1)), 1);
		assertNull(ledger.prepare(Map.of(), Map.of(PRODUCT, amount(1)), 1));
		ledger.cancel(first);
		assertNotNull(ledger.prepare(Map.of(), Map.of(PRODUCT, amount(1)), 1));
		assertTrue(CompletableFuture.supplyAsync(() -> {
			try { ledger.insert(PRODUCT, amount(1), ProductLedger.Action.EXECUTE); return false; }
			catch (IllegalStateException expected) { return true; }
		}).join());
	}
}
