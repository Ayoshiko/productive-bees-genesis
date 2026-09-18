package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SparseProductAmountsTest {
	@Test
	void seededOperationsMatchUnboundedReferenceModel() {
		var store = new SparseProductAmounts<Integer>();
		Map<Integer, BigInteger> model = new ConcurrentHashMap<>();
		var random = new Random(0xD04BEE);
		for (int step = 0; step < 30_000; step++) {
			int key = random.nextInt(100);
			BigInteger old = model.getOrDefault(key, BigInteger.ZERO);
			BigInteger change = step % 19 == 0 ? new BigInteger(256, random) : BigInteger.valueOf(random.nextLong(Long.MAX_VALUE));
			if (random.nextBoolean()) {
				if (change.bitLength() <= 63) store.add(key, change.longValueExact());
				else store.add(key, ProductAmount.of(change));
				model.put(key, old.add(change));
			} else {
				BigInteger taken = old.min(change);
				if (change.bitLength() <= 63) assertEquals(taken.longValueExact(), store.extract(key, change.longValueExact()));
				else assertEquals(taken, store.extract(key, ProductAmount.of(change)).exact());
				if (old.equals(taken)) model.remove(key);
				else model.put(key, old.subtract(taken));
			}
			assertEquals(model.getOrDefault(key, BigInteger.ZERO), store.amount(key).exact());
			assertEquals(model.getOrDefault(key, BigInteger.ZERO).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(), store.visible(key));
			assertEquals(model.size(), store.size());
		}
		var snapshot = store.snapshot();
		model.forEach((key, value) -> assertEquals(value, snapshot.get(key).exact()));
	}
	@Test
	void zeroReclamationAndCollisionsCannotAliasAnOldKey() {
		var store = new SparseProductAmounts<String>();
		assertEquals("Aa".hashCode(), "BB".hashCode());
		store.add("Aa", Long.MAX_VALUE);
		store.add("Aa", 1);
		assertEquals(Long.MAX_VALUE, store.extract("Aa", Long.MAX_VALUE));
		assertTrue(store.amount("Aa").fitsLong());
		var snapshot = store.snapshot();
		assertEquals(1, store.extract("Aa", 10));
		store.add("BB", 23);
		assertEquals(0, store.visible("Aa"));
		assertEquals(1, snapshot.get("Aa").longSaturated());
		store.add("Aa", 0);
		assertEquals(1, store.size());
		assertThrows(IllegalArgumentException.class, () -> store.add("BB", -1));
		assertThrows(IllegalArgumentException.class, () -> store.extract("BB", -1));
		assertEquals(23, store.visible("BB"));
	}
}
