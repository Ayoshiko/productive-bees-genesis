package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 内部数量后端，无配额、准入或转移权限；账本独占它，双表在同一临界区更新。 */
public final class SparseProductAmounts<K> {
	private final Object2LongOpenHashMap<K> small = new Object2LongOpenHashMap<>();
	private final Map<K, BigInteger> large = new ConcurrentHashMap<>();

	public synchronized ProductAmount amount(K key) {
		Objects.requireNonNull(key);
		BigInteger big = large.get(key);
		return big == null ? ProductAmount.of(small.getLong(key)) : ProductAmount.of(big);
	}
	public synchronized long visible(K key) { return small.getLong(Objects.requireNonNull(key)); }
	public synchronized int size() { return small.size(); }

	public synchronized void add(K key, long value) {
		Objects.requireNonNull(key);
		if (value < 0) throw new IllegalArgumentException("Negative product amount");
		if (value == 0) return;
		long old = small.getLong(key);
		if (old <= Long.MAX_VALUE - value) small.put(key, old + value);
		else set(key, amount(key).add(ProductAmount.of(value)));
	}
	public synchronized void add(K key, ProductAmount value) {
		Objects.requireNonNull(key);
		if (value.fitsLong()) add(key, value.longSaturated());
		else set(key, amount(key).add(value));
	}
	public synchronized long extract(K key, long requested) {
		Objects.requireNonNull(key);
		if (requested < 0) throw new IllegalArgumentException("Negative product amount");
		long taken = Math.min(requested, small.getLong(key));
		if (taken == 0) return 0;
		BigInteger big = large.get(key);
		if (big != null) set(key, ProductAmount.of(big.subtract(BigInteger.valueOf(taken))));
		else if (taken == small.getLong(key)) small.removeLong(key);
		else small.put(key, small.getLong(key) - taken);
		return taken;
	}
	public synchronized ProductAmount extract(K key, ProductAmount requested) {
		if (requested.fitsLong()) return ProductAmount.of(extract(key, requested.longSaturated()));
		ProductAmount old = amount(key);
		ProductAmount taken = old.min(requested);
		set(key, old.subtract(taken));
		return taken;
	}
	public synchronized void set(K key, ProductAmount value) {
		Objects.requireNonNull(key);
		if (value.isZero()) {
			small.removeLong(key);
			large.remove(key);
		} else {
			small.put(key, value.longSaturated());
			if (value.fitsLong()) large.remove(key);
			else large.put(key, value.exact());
		}
	}
	/** 仅供显式快照／模型验证，热路径不用全表复制。 */
	public synchronized Map<K, ProductAmount> snapshot() {
		Map<K, ProductAmount> copy = new ConcurrentHashMap<>();
		for (K key : small.keySet()) copy.put(key, amount(key));
		return Map.copyOf(copy);
	}
}
