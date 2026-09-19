package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagedProductAmountsTest {
	private static ProductKey key(int index) {
		var components = new CompoundTag(); components.putInt("test:variant", index);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), components);
	}
	@Test void seededChangesAndRetainedSnapshotsMatchIndependentExactModels() {
		var store = new PagedProductAmounts(); var random = new Random(0xD09B1);
		Map<ProductKey, ProductAmount> model = new ConcurrentHashMap<>();
		var keys = new ArrayList<ProductKey>();
		for (int i = 0; i < 8_000; i++) keys.add(key(i));
		var snapshots = new ArrayList<Map<ProductKey, ProductAmount>>();
		var models = new ArrayList<Map<ProductKey, ProductAmount>>();
		for (int step = 0; step < 60_000; step++) {
			var key = keys.get(random.nextInt(keys.size()));
			var value = step % 11 == 0 ? ProductAmount.of(new BigInteger(256, random))
					: ProductAmount.of(random.nextInt(3) == 0 ? 0 : random.nextLong(Long.MAX_VALUE));
			store.set(key, value);
			if (value.isZero()) model.remove(key); else model.put(key, value);
			assertEquals(value, store.amount(key)); assertEquals(model.size(), store.size());
			if (step % 5_000 == 0) { snapshots.add(store.snapshot()); models.add(Map.copyOf(model)); }
		}
		assertEquals(model, store.snapshot());
		for (int i = 0; i < snapshots.size(); i++) {
			assertEquals(models.get(i), snapshots.get(i));
			assertEquals(snapshots.get(i), models.get(i));
		}
		for (var key : keys) store.set(key, ProductAmount.ZERO);
		assertEquals(0, store.size()); assertTrue(store.snapshot().isEmpty());
		for (int i = 0; i < snapshots.size(); i++) assertEquals(models.get(i), snapshots.get(i));
	}
	@Test void collisionsSplitsMergesAndReusePreserveEveryOldIdentity() {
		var store = new PagedProductAmounts(); var keys = new ArrayList<ProductKey>();
		for (int i = 0; i < 1024; i++) {
			var text = new StringBuilder();
			for (int bit = 0; bit < 10; bit++) text.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
			var components = new CompoundTag(); components.putString("test:collision", text.toString());
			keys.add(new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), components));
			assertEquals(keys.getFirst().hashCode(), keys.getLast().hashCode());
			store.set(keys.getLast(), ProductAmount.of(i + 1));
		}
		var original = store.snapshot();
		for (int i = 0; i < keys.size(); i += 2) store.set(keys.get(i), ProductAmount.ZERO);
		var half = store.snapshot();
		for (int i = 0; i < keys.size(); i += 2) store.set(keys.get(i), ProductAmount.of(5000 + i));
		for (int i = 0; i < keys.size(); i++) {
			assertEquals(ProductAmount.of(i + 1), original.get(keys.get(i)));
			assertEquals(i % 2 == 0 ? null : ProductAmount.of(i + 1), half.get(keys.get(i)));
			assertEquals(ProductAmount.of(i % 2 == 0 ? 5000 + i : i + 1), store.amount(keys.get(i)));
		}
		for (int i = 0; i < 5_000; i++) store.set(key(i), ProductAmount.of(8));
		for (int i = 0; i < 5_000; i++) store.set(key(i), ProductAmount.ZERO);
		for (var key : keys) store.set(key, ProductAmount.ZERO);
		assertEquals(0, store.size());
		for (int i = 0; i < keys.size(); i++) store.set(keys.get(i), ProductAmount.of(i + 1));
		assertEquals(original, store.snapshot());
	}
	@Test void freezeAndForkDoNotShareWritablePagesOrLargeNumbers() {
		var store = new PagedProductAmounts(); var key = key(1);
		var huge = ProductAmount.of(BigInteger.ONE.shiftLeft(512)); store.set(key, huge);
		var frozen = store.snapshot(); var a = PagedProductAmounts.restore(frozen); var b = PagedProductAmounts.restore(frozen);
		a.set(key, ProductAmount.of(Long.MAX_VALUE)); b.set(key, ProductAmount.ZERO); store.set(key, ProductAmount.of(3));
		assertEquals(huge, frozen.get(key)); assertEquals(ProductAmount.of(Long.MAX_VALUE), a.amount(key));
		assertEquals(ProductAmount.ZERO, b.amount(key)); assertEquals(ProductAmount.of(3), store.amount(key));
		assertThrows(UnsupportedOperationException.class, () -> frozen.put(key, ProductAmount.of(4)));
		assertThrows(UnsupportedOperationException.class, () -> frozen.remove(key));
		assertThrows(UnsupportedOperationException.class, () -> frozen.entrySet().iterator().next().setValue(ProductAmount.ZERO));
		var iterator = frozen.entrySet().iterator(); iterator.next();
		assertFalse(iterator.hasNext()); assertThrows(java.util.NoSuchElementException.class, iterator::next);
		assertNull(frozen.get("not a product"));
	}
	@Test void readerCanTraverseFrozenPagesWhileOwnerMutatesAndReclaims() throws Exception {
		var store = new PagedProductAmounts();
		for (int i = 0; i < 12_000; i++) store.set(key(i), ProductAmount.of(i + 1));
		var frozen = store.snapshot();
		try (var worker = Executors.newSingleThreadExecutor()) {
			var read = worker.submit(() -> {
				for (int pass = 0; pass < 8; pass++) {
					assertEquals(72_006_000L, frozen.values().stream().mapToLong(ProductAmount::longSaturated).sum());
					assertEquals(ProductAmount.of(701), frozen.get(key(700)));
				}
			});
			for (int i = 0; i < 12_000; i++) { store.set(key(i), ProductAmount.ZERO); store.set(key(i + 12_000), ProductAmount.of(2)); }
			read.get();
		}
		assertEquals(12_000, frozen.size()); assertEquals(12_000, store.size());
	}
	@Test void checkpointKeepsFrozenBalancesButStillValidatesUntrustedMapsAndReservations() {
		var store = new PagedProductAmounts(); var key = key(1); store.set(key, ProductAmount.of(10));
		var frozen = store.snapshot();
		var checkpoint = new LedgerCheckpoint(3, frozen, List.of());
		assertSame(frozen, checkpoint.balances());
		Map<ProductKey, ProductAmount> mutable = new ConcurrentHashMap<>(); mutable.put(key, ProductAmount.of(20));
		var copied = new LedgerCheckpoint(4, mutable, List.of()); mutable.clear();
		assertEquals(ProductAmount.of(20), copied.balances().get(key));
		assertThrows(IllegalArgumentException.class, () -> new LedgerCheckpoint(0, Map.of(key, ProductAmount.ZERO), List.of()));
		var pending = new LedgerCheckpoint.Pending(java.util.UUID.randomUUID(), 0, LedgerTransaction.State.PAID,
				Map.of(key, ProductAmount.of(11)), Map.of());
		assertThrows(IllegalArgumentException.class, () -> new LedgerCheckpoint(3, frozen, List.of(pending)));
	}
}
