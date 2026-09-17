package com.ayoshiko.productivebeesgenesis.storageprototype.amount;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.math.BigInteger;
import java.util.function.BiConsumer;
import java.util.function.ObjLongConsumer;

/** C：全 BigInteger 对照，不当作性能优胜者的先验假设。 */
public final class BigAmountStore<K> implements AmountStore<K> {
	private final Object2ObjectOpenHashMap<K, BigInteger> amounts = new Object2ObjectOpenHashMap<>();

	@Override
	public BigInteger exact(K key) { return amounts.getOrDefault(key, BigInteger.ZERO); }

	@Override
	public long visible(K key) { return exact(key).min(MAX_LONG).longValueExact(); }

	@Override
	public void set(K key, BigInteger amount) {
		AmountStore.check(amount);
		if (amount.signum() == 0) amounts.remove(key);
		else amounts.put(key, amount);
	}

	@Override
	public void add(K key, long amount) {
		AmountStore.check(amount);
		if (amount > 0) set(key, exact(key).add(BigInteger.valueOf(amount)));
	}

	@Override
	public long extract(K key, long requested) {
		AmountStore.check(requested);
		long taken = Math.min(requested, visible(key));
		if (taken > 0) set(key, exact(key).subtract(BigInteger.valueOf(taken)));
		return taken;
	}

	@Override
	public int size() { return amounts.size(); }

	@Override
	public void visitVisible(ObjLongConsumer<K> visitor) {
		amounts.forEach((key, amount) -> visitor.accept(key, amount.min(MAX_LONG).longValueExact()));
	}

	@Override
	public void visitExact(BiConsumer<K, BigInteger> visitor) { amounts.forEach(visitor); }
}
