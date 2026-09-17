package com.ayoshiko.productivebeesgenesis.storageprototype.amount;

import java.math.BigInteger;
import java.util.function.BiConsumer;
import java.util.function.ObjLongConsumer;

/** D02 数量候选，实例仅由创建它的测试线程或服务器线程拥有。 */
public interface AmountStore<K> {

	BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

	BigInteger exact(K key);

	long visible(K key);

	void set(K key, BigInteger amount);

	void add(K key, long amount);

	long extract(K key, long requested);

	int size();

	void visitVisible(ObjLongConsumer<K> visitor);

	void visitExact(BiConsumer<K, BigInteger> visitor);

	static void check(long amount) {
		if (amount < 0) throw new IllegalArgumentException("Negative amount");
	}

	static void check(BigInteger amount) {
		if (amount.signum() < 0) throw new IllegalArgumentException("Negative amount");
	}
}
