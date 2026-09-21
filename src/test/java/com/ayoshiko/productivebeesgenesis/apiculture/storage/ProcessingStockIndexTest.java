package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProductMatcher;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingStockIndexTest {
	static ProductKey key(int value) {
		var components = new CompoundTag(); components.putString(ProductMatcher.BEE_TYPE, "test:iron"); components.putInt("test:value", value);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:comb"), components);
	}
	static ProductPolicyRegistry policy() { return new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(), List.of())); }
	static int finish(ProcessingStockIndex index) { int steps = 0; while (!index.ready()) { assertTrue(++steps < 30000); index.step(); } return steps; }
	@Test void largeInitialSnapshotIsBudgetedAndOneKeyChangeUsesOnlyOneStep() {
		var amounts = new PagedProductAmounts(); for (int i = 0; i < 10000; i++) amounts.set(key(i), ProductAmount.of(i + 1));
		var first = new LedgerCheckpoint(0, amounts.snapshot(), List.of()); var index = new ProcessingStockIndex(first);
		assertThrows(IllegalStateException.class, index::view); assertFalse(index.step()); assertTrue(finish(index) >= 10000);
		var ledger = ProductLedger.restore(policy(), 1, first); ledger.extract(key(9876), ProductAmount.of(7), ProductLedger.Action.EXECUTE);
		var next = ledger.checkpoint(); assertSame(next, ledger.checkpoint()); assertEquals(Set.of(key(9876)), next.changesSince(first));
		var before = index.view(); index.update(next); assertEquals(1, finish(index));
		assertEquals(ProductAmount.of(9870), index.view().amount(key(9876))); assertEquals(ProductAmount.of(9877), before.amount(key(9876)));
		assertEquals(ProductAmount.of(50_005_000L - 7), index.view().group(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, key(0))));
	}
	@Test void reservationsCancellationAndPaidCommitAllUpdateAvailableGroups() {
		var a = key(1); var b = key(2); var original = new LedgerCheckpoint(0, Map.of(a, ProductAmount.of(100), b, ProductAmount.of(100)), List.of());
		var index = new ProcessingStockIndex(original); finish(index); var ledger = ProductLedger.restore(policy(), 2, original);
		var held = ledger.prepare(Map.of(a, ProductAmount.of(60)), Map.of(), 0); var reserved = ledger.checkpoint(); index.update(reserved); finish(index);
		assertEquals(ProductAmount.of(40), index.view().amount(a)); assertEquals(ProductAmount.of(140), index.view().group(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, a)));
		assertTrue(ledger.cancel(held)); index.update(ledger.checkpoint()); finish(index); assertEquals(ProductAmount.of(100), index.view().amount(a));
		var paid = ledger.importPaidOutput(new LedgerCheckpoint.Pending(UUID.randomUUID(), 0, LedgerTransaction.State.PAID, Map.of(), Map.of(b, ProductAmount.of(9))));
		assertTrue(ledger.commit(paid)); index.update(ledger.checkpoint()); finish(index); assertEquals(ProductAmount.of(109), index.view().amount(b));
	}
	@Test void mutationDuringInitializationIncludingNewAndRemovedKeysIsNotLost() {
		var a = key(1); var b = key(2); var c = key(3);
		var first = new LedgerCheckpoint(0, Map.of(a, ProductAmount.of(5), b, ProductAmount.of(7)), List.of());
		var index = new ProcessingStockIndex(first); index.step();
		var ledger = ProductLedger.restore(policy(), 1, first); ledger.extract(a, ProductAmount.of(5), ProductLedger.Action.EXECUTE);
		var paid = ledger.importPaidOutput(new LedgerCheckpoint.Pending(UUID.randomUUID(), 0, LedgerTransaction.State.PAID, Map.of(), Map.of(c, ProductAmount.of(11)))); ledger.commit(paid);
		index.update(ledger.checkpoint()); finish(index);
		assertEquals(ProductAmount.ZERO, index.view().amount(a)); assertEquals(ProductAmount.of(18), index.view().group(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, b)));
		var keys = new ArrayList<ProductKey>(); index.view().keys().forEach(keys::add); assertEquals(Set.of(b, c), Set.copyOf(keys));
	}
	@Test void skippedOrUntrustedRootsRebuildAndHugeAmountsStayExact() {
		var a = key(1); var huge = ProductAmount.of(BigInteger.ONE.shiftLeft(150));
		var first = new LedgerCheckpoint(0, Map.of(a, huge), List.of()); var index = new ProcessingStockIndex(first); finish(index);
		var ledger = ProductLedger.restore(policy(), 1, first); ledger.extract(a, ProductAmount.of(1), ProductLedger.Action.EXECUTE); ledger.checkpoint();
		ledger.extract(a, ProductAmount.of(2), ProductLedger.Action.EXECUTE); var next = ledger.checkpoint(); assertNull(next.changesSince(first));
		index.update(next); assertThrows(IllegalStateException.class, index::view); finish(index);
		assertEquals(huge.subtract(ProductAmount.of(3)), index.view().group(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, a)));
	}
	@Test void longMutationRunsFallBackToBoundedRebuildInsteadOfGrowingTheJournal() {
		var amounts = new PagedProductAmounts(); for (int i = 0; i < 5000; i++) amounts.set(key(i), ProductAmount.of(2));
		var first = new LedgerCheckpoint(0, amounts.snapshot(), List.of()); var ledger = ProductLedger.restore(policy(), 1, first);
		for (int i = 0; i < 5000; i++) ledger.extract(key(i), ProductAmount.of(1), ProductLedger.Action.EXECUTE);
		var next = ledger.checkpoint(); assertNull(next.changesSince(first));
		var index = new ProcessingStockIndex(first); index.update(next); assertEquals(5001, finish(index));
		assertEquals(ProductAmount.of(5000), index.view().group(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, key(0))));
	}
	@Test void cursorLookupContinuesAfterRemovedKeysWithoutEnumeratingOrMutatingOldViews() {
		var first = new LedgerCheckpoint(0, Map.of(key(1), ProductAmount.of(1), key(2), ProductAmount.of(2)), List.of());
		var index = new ProcessingStockIndex(first); finish(index); var view = index.view();
		var a = view.nextKey(null); var b = view.nextKey(a); assertNotNull(b); assertNull(view.nextKey(b));
		var ledger = ProductLedger.restore(policy(), 1, first); ledger.extract(a, ProductAmount.of(10), ProductLedger.Action.EXECUTE);
		index.update(ledger.checkpoint()); finish(index);
		assertEquals(b, index.view().nextKey(a)); assertEquals(a, view.nextKey(null));
	}
}
