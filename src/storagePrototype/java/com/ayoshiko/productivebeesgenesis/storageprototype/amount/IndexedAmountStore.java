package com.ayoshiko.productivebeesgenesis.storageprototype.amount;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.ObjLongConsumer;

/** B：分段 long 数组与稀疏大数表；索引仅内部使用，零键槽位可回收。 */
public final class IndexedAmountStore<K> implements AmountStore<K> {
	private static final int SHIFT = 12;
	private static final int MASK = (1 << SHIFT) - 1;
	private final Object2IntOpenHashMap<K> ids = new Object2IntOpenHashMap<>();
	private final ArrayList<long[]> pages = new ArrayList<>();
	private final Int2ObjectOpenHashMap<BigInteger> large = new Int2ObjectOpenHashMap<>();
	private final IntArrayList free = new IntArrayList();
	private int next;

	public IndexedAmountStore() { ids.defaultReturnValue(-1); }

	private long value(int id) { return pages.get(id >>> SHIFT)[id & MASK]; }
	private void value(int id, long amount) { pages.get(id >>> SHIFT)[id & MASK] = amount; }

	private int allocate(K key) {
		int id;
		if (!free.isEmpty()) id = free.removeInt(free.size() - 1);
		else {
			id = next++;
			if ((id & MASK) == 0) pages.add(new long[1 << SHIFT]);
		}
		ids.put(key, id);
		return id;
	}

	@Override
	public BigInteger exact(K key) {
		int id = ids.getInt(key);
		if (id < 0) return BigInteger.ZERO;
		BigInteger amount = large.get(id);
		return amount == null ? BigInteger.valueOf(value(id)) : amount;
	}

	@Override
	public long visible(K key) {
		int id = ids.getInt(key);
		return id < 0 ? 0 : value(id);
	}

	@Override
	public void set(K key, BigInteger amount) {
		AmountStore.check(amount);
		int id = ids.getInt(key);
		if (amount.signum() == 0) {
			if (id >= 0) remove(key, id);
			return;
		}
		if (id < 0) id = allocate(key);
		if (amount.bitLength() > 63) {
			value(id, Long.MAX_VALUE);
			large.put(id, amount);
		} else {
			value(id, amount.longValueExact());
			large.remove(id);
		}
	}

	private void remove(K key, int id) {
		ids.removeInt(key);
		large.remove(id);
		value(id, 0);
		free.add(id);
		if (ids.isEmpty()) {
			pages.clear();
			free.clear();
			next = 0;
		}
	}

	@Override
	public void add(K key, long amount) {
		AmountStore.check(amount);
		if (amount == 0) return;
		int id = ids.getInt(key);
		if (id < 0) id = allocate(key);
		long current = value(id);
		if (current <= Long.MAX_VALUE - amount) value(id, current + amount);
		else set(key, exact(key).add(BigInteger.valueOf(amount)));
	}

	@Override
	public long extract(K key, long requested) {
		AmountStore.check(requested);
		int id = ids.getInt(key);
		if (id < 0 || requested == 0) return 0;
		long current = value(id);
		long taken = Math.min(requested, current);
		BigInteger big = current == Long.MAX_VALUE ? large.get(id) : null;
		if (big != null) set(key, big.subtract(BigInteger.valueOf(taken)));
		else if (taken == current) remove(key, id);
		else value(id, current - taken);
		return taken;
	}

	@Override
	public int size() { return ids.size(); }

	@Override
	public void visitVisible(ObjLongConsumer<K> visitor) {
		var iterator = ids.object2IntEntrySet().fastIterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			visitor.accept(entry.getKey(), value(entry.getIntValue()));
		}
	}

	@Override
	public void visitExact(BiConsumer<K, BigInteger> visitor) {
		visitVisible((key, amount) -> visitor.accept(key, exact(key)));
	}
}
