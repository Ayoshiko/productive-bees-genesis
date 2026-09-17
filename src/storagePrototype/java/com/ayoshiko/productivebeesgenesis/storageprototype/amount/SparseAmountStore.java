package com.ayoshiko.productivebeesgenesis.storageprototype.amount;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.math.BigInteger;
import java.util.function.BiConsumer;
import java.util.function.ObjLongConsumer;

/** A：long 投影表加稀疏大数表；普通增减不分配 BigInteger。 */
public final class SparseAmountStore<K> implements AmountStore<K> {
	private final Object2LongOpenHashMap<K> small = new Object2LongOpenHashMap<>();
	private final Object2ObjectOpenHashMap<K, BigInteger> large = new Object2ObjectOpenHashMap<>();

	@Override
	public BigInteger exact(K key) {
		BigInteger amount = large.get(key);
		return amount != null ? amount : BigInteger.valueOf(small.getLong(key));
	}

	@Override
	public long visible(K key) { return small.getLong(key); }

	@Override
	public void set(K key, BigInteger amount) {
		AmountStore.check(amount);
		if (amount.signum() == 0) {
			small.removeLong(key);
			large.remove(key);
		} else if (amount.bitLength() <= 63) {
			small.put(key, amount.longValueExact());
			large.remove(key);
		} else {
			small.put(key, Long.MAX_VALUE);
			large.put(key, amount);
		}
	}

	@Override
	public void add(K key, long amount) {
		AmountStore.check(amount);
		if (amount == 0) return;
		long current = small.getLong(key);
		if (current <= Long.MAX_VALUE - amount) {
			small.put(key, current + amount);
		} else {
			set(key, exact(key).add(BigInteger.valueOf(amount)));
		}
	}

	@Override
	public long extract(K key, long requested) {
		AmountStore.check(requested);
		long taken = Math.min(requested, small.getLong(key));
		if (taken == 0) return 0;
		BigInteger big = small.getLong(key) == Long.MAX_VALUE ? large.get(key) : null;
		if (big != null) set(key, big.subtract(BigInteger.valueOf(taken)));
		else if (taken == small.getLong(key)) small.removeLong(key);
		else small.put(key, small.getLong(key) - taken);
		return taken;
	}

	@Override
	public int size() { return small.size(); }

	@Override
	public void visitVisible(ObjLongConsumer<K> visitor) {
		var iterator = small.object2LongEntrySet().fastIterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			visitor.accept(entry.getKey(), entry.getLongValue());
		}
	}

	@Override
	public void visitExact(BiConsumer<K, BigInteger> visitor) {
		visitVisible((key, amount) -> visitor.accept(key, exact(key)));
	}
}
