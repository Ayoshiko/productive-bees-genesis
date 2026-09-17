package com.ayoshiko.productivebeesgenesis.storageprototype;

import com.ayoshiko.productivebeesgenesis.storageprototype.amount.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmountStoreTest {
	private static final List<Supplier<AmountStore<String>>> STORES = List.of(
			SparseAmountStore::new, IndexedAmountStore::new, BigAmountStore::new);

	@Test
	void randomizedOperationsMatchIndependentExactModel() {
		for (var factory : STORES) {
			var store = factory.get();
			var reference = new ConcurrentHashMap<String, BigInteger>();
			var random = new Random(0xD02BEE);
			for (int step = 0; step < 20_000; step++) {
				String key = "product-" + random.nextInt(64);
				BigInteger before = reference.getOrDefault(key, BigInteger.ZERO);
				long amount = random.nextBoolean() ? random.nextInt(1000) : Long.MAX_VALUE;
				BigInteger expected;
				switch (random.nextInt(3)) {
					case 0 -> {
						store.add(key, amount);
						expected = before.add(BigInteger.valueOf(amount));
					}
					case 1 -> {
						long take = before.min(BigInteger.valueOf(amount)).longValueExact();
						assertEquals(take, store.extract(key, amount));
						expected = before.subtract(BigInteger.valueOf(take));
					}
					default -> {
						expected = random.nextBoolean() ? BigInteger.ONE.shiftLeft(180) : BigInteger.ZERO;
						store.set(key, expected);
					}
				}
				if (expected.signum() == 0) reference.remove(key);
				else reference.put(key, expected);
				assertEquals(expected, store.exact(key));
				assertEquals(expected.min(AmountStore.MAX_LONG).longValueExact(), store.visible(key));
				assertEquals(reference.size(), store.size());
			}
			store.visitExact((key, amount) -> assertEquals(reference.get(key), amount));
		}
	}

	@Test
	void crossingsReclaimZerosAndNeverTruncateRealAmounts() {
		for (var factory : STORES) {
			var store = factory.get();
			for (int bits : new int[]{63, 126, 180, 1024}) {
				BigInteger boundary = BigInteger.ONE.shiftLeft(bits);
				store.set("key", boundary.subtract(BigInteger.ONE));
				store.add("key", 1);
				assertEquals(boundary, store.exact("key"));
				assertEquals(1, store.extract("key", 1));
				assertEquals(boundary.subtract(BigInteger.ONE), store.exact("key"));
			}
			store.set("key", BigInteger.ZERO);
			assertEquals(0, store.size());
			store.add("new-key", 4);
			assertEquals(BigInteger.ZERO, store.exact("key"));
			assertEquals(BigInteger.valueOf(4), store.exact("new-key"));
			assertThrows(IllegalArgumentException.class, () -> store.add("key", -1));
			assertThrows(IllegalArgumentException.class, () -> store.extract("key", -1));
			assertThrows(IllegalArgumentException.class, () -> store.set("key", BigInteger.valueOf(-1)));
		}
	}
}
