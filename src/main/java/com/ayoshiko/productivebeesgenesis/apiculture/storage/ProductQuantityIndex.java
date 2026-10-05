package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 有订阅才维护的核心级数量索引；冻结排序与实时可提取量分离。 */
public final class ProductQuantityIndex {
	public record Rank(ProductAmount amount, ProductKey key) implements Comparable<Rank> {
		@Override public int compareTo(Rank other) {
			int count = other.amount.compareTo(amount);
			if (count != 0) return count;
			int id = key.id().compareTo(other.key.id()); if (id != 0) return id;
			int kind = key.kind().compareTo(other.key.kind()); return kind != 0 ? kind : key.orderingKey().compareTo(other.key.orderingKey());
		}
	}
	public record Order(Map<Rank, ProductKey> rows, long sampledAt) {
		public Map.Entry<Rank, ProductKey> next(Rank cursor, boolean reverse) {
			return reverse ? SnapshotRecords.previousEntry(rows, cursor) : SnapshotRecords.nextEntry(rows, cursor);
		}
	}
	private final Set<UUID> subscribers = new HashSet<>();
	private SnapshotRecords<Rank, ProductKey> sorted = new SnapshotRecords<>(Comparator.naturalOrder());
	private Map<ProductKey, ProductAmount> base = new PagedProductAmounts().snapshot(), target;
	private PagedProductAmounts.Changes changes;
	private long sampledAt, checkedAt = Long.MIN_VALUE;
	private Order published;
	public void retain(UUID session) { subscribers.add(session); }
	public void release(UUID session) { subscribers.remove(session); if (subscribers.isEmpty()) clear(); }
	public Order order() { return published; }
	public void clear() {
		subscribers.clear(); sorted = new SnapshotRecords<>(Comparator.naturalOrder());
		base = new PagedProductAmounts().snapshot(); target = null; changes = null; published = null; checkedAt = Long.MIN_VALUE;
	}
	/** 初次逐项建立，后续共享子树跳过；只在完整差量处理后发布。 */
	public int step(Map<ProductKey, ProductAmount> balances, long now, int budget) {
		if (subscribers.isEmpty()) return 0;
		if (changes == null) {
			if (checkedAt != Long.MIN_VALUE && now - checkedAt < 40) return 0;
			target = balances; sampledAt = now; checkedAt = now; changes = PagedProductAmounts.changes(base, target);
		}
		int used = changes.step(budget, (key, value) -> {
			var old = base.get(key); if (old != null) sorted.remove(new Rank(old, key));
			if (!value.isZero()) sorted.put(new Rank(value, key), key);
		});
		if (changes.complete()) {
			published = new Order(sorted.snapshot(), sampledAt); base = target; target = null; changes = null;
		}
		return used;
	}
}
