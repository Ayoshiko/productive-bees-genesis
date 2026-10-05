package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductQuantityIndexTest {
	@Test void randomDifferencesIncludeDeletesAndCompareToIndependentMap() {
		var store = new PagedProductAmounts(); var random = new Random(18102);
		Map<ProductKey, ProductAmount> model = new HashMap<>();
		for (int round = 0; round < 30; round++) {
			var before = store.snapshot(); var old = new HashMap<>(model);
			for (int i = 0; i < 140; i++) {
				var key = key(random.nextInt(600));
				var amount = ProductAmount.of(random.nextInt(4) == 0 ? BigInteger.ZERO : new BigInteger(140, random).add(BigInteger.ONE));
				store.set(key, amount);
				if (amount.isZero()) model.remove(key); else model.put(key, amount);
			}
			var after = store.snapshot(); var changes = PagedProductAmounts.changes(before, after);
			var actual = new HashMap<ProductKey, ProductAmount>(); int loops = 0;
			while (!changes.complete()) {
				assertTrue(changes.step(7, (key, amount) -> assertNull(actual.put(key, amount))) <= 7);
				assertTrue(++loops < 10_000);
			}
			var expected = new HashMap<ProductKey, ProductAmount>(); var keys = new HashSet<>(old.keySet()); keys.addAll(model.keySet());
			for (var key : keys) if (!Objects.equals(old.get(key), model.get(key))) expected.put(key, model.getOrDefault(key, ProductAmount.ZERO));
			assertEquals(expected, actual); assertEquals(old, before); assertEquals(model, after);
		}
	}
	@Test void sharedSubtreesMakeASingleChangedKeyCheaperThanAFullScan() {
		var store = new PagedProductAmounts();
		for (int i = 0; i < 10_000; i++) store.set(key(i), ProductAmount.of(i + 1));
		var before = store.snapshot(); store.set(key(4321), ProductAmount.of(BigInteger.ONE.shiftLeft(130))); var after = store.snapshot();
		var changes = PagedProductAmounts.changes(before, after); var actual = new HashMap<ProductKey, ProductAmount>(); int used = 0;
		while (!changes.complete()) used += changes.step(32, actual::put);
		assertEquals(Map.of(key(4321), ProductAmount.of(BigInteger.ONE.shiftLeft(130))), actual);
		assertTrue(used < 300, "Sparse change unexpectedly scanned the whole inventory: " + used);
		assertTrue(PagedProductAmounts.changes(after, after).complete());
	}
	@Test void subscribersShareAtomicOrdersAndReleaseAllRootsWhenUnused() {
		var store = new PagedProductAmounts(); var index = new ProductQuantityIndex();
		var a = UUID.randomUUID(); var b = UUID.randomUUID();
		for (int i = 0; i < 83; i++) store.set(key(i), ProductAmount.of(i % 7 + 1));
		store.set(key(0), ProductAmount.of(BigInteger.ONE.shiftLeft(100)));
		assertEquals(0, index.step(store.snapshot(), 0, 32)); assertNull(index.order());
		index.retain(a); index.retain(b); index.step(store.snapshot(), 0, 1); assertNull(index.order());
		finish(index, store.snapshot(), 0); var frozen = index.order();
		assertEquals(key(0), frozen.next(null, false).getValue());
		var descending = walk(frozen, false); var ascending = walk(frozen, true);
		var reversed = new ArrayList<>(descending); Collections.reverse(reversed); assertEquals(reversed, ascending);
		assertEquals(83, new HashSet<>(descending).size());
		var reference = new ArrayList<>(store.snapshot().entrySet());
		reference.sort(Comparator.<Map.Entry<ProductKey, ProductAmount>, BigInteger>comparing(e -> e.getValue().exact()).reversed()
				.thenComparing(e -> e.getKey().id()).thenComparing(e -> e.getKey().kind()).thenComparing(e -> e.getKey().orderingKey()));
		assertEquals(reference.stream().map(Map.Entry::getKey).toList(), descending);
		store.set(key(0), ProductAmount.ZERO); store.set(key(1), ProductAmount.of(BigInteger.ONE.shiftLeft(150)));
		assertEquals(0, index.step(store.snapshot(), 39, 32)); assertSame(frozen, index.order());
		index.step(store.snapshot(), 40, 1); assertSame(frozen, index.order()); finish(index, store.snapshot(), 40);
		assertEquals(key(1), index.order().next(null, false).getValue()); assertEquals(82, index.order().rows().size());
		assertEquals(83, frozen.rows().size()); assertEquals(key(0), frozen.next(null, false).getValue());
		index.release(a); assertNotNull(index.order()); index.release(b); assertNull(index.order());
		index.retain(a); finish(index, store.snapshot(), 41); assertEquals(82, index.order().rows().size());
		index.clear(); assertNull(index.order()); assertEquals(0, index.step(store.snapshot(), 100, 32));
	}
	private static List<ProductKey> walk(ProductQuantityIndex.Order order, boolean reverse) {
		var keys = new ArrayList<ProductKey>(); ProductQuantityIndex.Rank cursor = null;
		for (var entry = order.next(cursor, reverse); entry != null; entry = order.next(cursor, reverse)) {
			keys.add(entry.getValue()); cursor = entry.getKey(); assertTrue(keys.size() < 100);
		}
		return keys;
	}
	private static void finish(ProductQuantityIndex index, Map<ProductKey, ProductAmount> balances, long now) {
		int steps = 0; ProductQuantityIndex.Order old = index.order();
		do { assertTrue(index.step(balances, now, 7) <= 7); assertTrue(++steps < 10_000); }
		while (index.order() == old);
	}
	private static ProductKey key(int i) {
		var tag = new CompoundTag(); tag.putInt("test:variant", i);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), tag);
	}
}
