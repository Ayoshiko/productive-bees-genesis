package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistryTest.*;

class TransferStagingTest {
	private static final ProductKey KEY = item("test:product");
	private static ProductAmount amount(long value) { return ProductAmount.of(value); }
	@Test
	void partialAcceptanceReturnsExactlyTheRemainderAndRejectsCallbackReentry() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, KEY)), 4);
		ledger.insert(KEY, amount(50), ProductLedger.Action.EXECUTE);
		var staging = new TransferStaging(ledger, 2, 64);
		var external = new AtomicLong();
		var transfer = staging.transfer("test:output", KEY, 10, TransferStaging.Direction.EXPORT, offered -> {
			assertThrows(IllegalStateException.class, () -> ledger.extract(KEY, amount(1), ProductLedger.Action.EXECUTE));
			assertThrows(IllegalStateException.class, staging::snapshot);
			external.addAndGet(4); return 4;
		});
		assertEquals(amount(46), ledger.available(KEY));
		assertEquals(4, external.get());
		var after = ledger.snapshot();
		assertTrue(staging.settle(transfer));
		assertTrue(staging.settle(transfer));
		assertEquals(after, ledger.snapshot());
		assertTrue(staging.snapshot().isEmpty());
	}
	@Test
	void unknownExportRetainsQuarantineUntilOriginalReceiptIsKnown() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, KEY)), 4);
		ledger.insert(KEY, amount(50), ProductLedger.Action.EXECUTE);
		var staging = new TransferStaging(ledger, 1, 64);
		var calls = new AtomicLong();
		var transfer = staging.transfer("test:output", KEY, 10, TransferStaging.Direction.EXPORT, offered -> {
			calls.incrementAndGet(); throw new IllegalStateException("accepted six then disconnected");
		});
		assertEquals(TransferStaging.Phase.UNKNOWN, transfer.view().phase());
		assertEquals(amount(40), ledger.available(KEY));
		assertEquals(amount(10), transfer.view().held());
		assertFalse(staging.settle(transfer));
		assertNull(staging.transfer("test:output", KEY, 10, TransferStaging.Direction.EXPORT, offered -> calls.incrementAndGet()));
		assertEquals(1, calls.get());
		staging.resolve(transfer, 6);
		assertEquals(amount(44), ledger.available(KEY));
		assertThrows(IllegalArgumentException.class, () -> staging.resolve(transfer, 6));
	}
	@Test
	void unknownImportDoesNotCreateBalanceBeforeReliableReceipt() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, KEY)), 4);
		var staging = new TransferStaging(ledger, 1, 64);
		var transfer = staging.transfer("test:input", KEY, 10, TransferStaging.Direction.IMPORT, offered -> {
			throw new IllegalStateException("source may have removed five");
		});
		assertEquals(ProductAmount.ZERO, ledger.available(KEY));
		assertEquals(ProductAmount.ZERO, transfer.view().held());
		staging.resolve(transfer, 5);
		assertEquals(amount(5), ledger.available(KEY));
		assertTrue(staging.snapshot().isEmpty());
	}
	@Test
	void reloadDuringImportKeepsOwnedItemsAndHistoricalExportReturnsSafely() {
		var registry = new ProductPolicyRegistry(policy(1, KEY));
		var ledger = new ProductLedger(registry, 4);
		var staging = new TransferStaging(ledger, 1, 64);
		var transfer = staging.transfer("test:input", KEY, 10, TransferStaging.Direction.IMPORT, offered -> {
			registry.replace(policy(2)); return 7;
		});
		assertEquals(amount(7), transfer.view().held());
		assertEquals(ProductAmount.ZERO, ledger.available(KEY));
		assertFalse(staging.settle(transfer));
		registry.replace(policy(3, KEY));
		assertTrue(staging.settle(transfer));
		registry.replace(policy(4));
		var export = staging.transfer("test:output", KEY, 7, TransferStaging.Direction.EXPORT, offered -> 3);
		assertEquals(TransferStaging.Phase.COMPLETE, export.view().phase());
		assertEquals(amount(4), ledger.available(KEY));
	}
	@Test
	void invalidExternalAmountDoesNotMintOrRefundItems() {
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy(1, KEY)), 4);
		ledger.insert(KEY, amount(50), ProductLedger.Action.EXECUTE);
		var staging = new TransferStaging(ledger, 2, 64);
		assertThrows(IllegalArgumentException.class, () -> staging.transfer("endpoint", KEY, 65, TransferStaging.Direction.IMPORT, n -> n));
		var bad = staging.transfer("endpoint", KEY, 10, TransferStaging.Direction.EXPORT, offered -> 11);
		assertEquals(amount(40), ledger.available(KEY));
		assertEquals(TransferStaging.Phase.UNKNOWN, bad.view().phase());
		assertThrows(IllegalArgumentException.class, () -> staging.resolve(bad, -1));
		assertEquals(TransferStaging.Phase.UNKNOWN, bad.view().phase());
	}
}
