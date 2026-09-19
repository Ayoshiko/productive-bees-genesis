package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Map;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 只在账本临界区访问；事务预约与玩家保留策略是不同账户。 */
final class ReservationBook {
	private final Map<UUID, LedgerTransaction> active = new ConcurrentHashMap<>();
	private final SnapshotRecords<UUID, LedgerCheckpoint.Pending> records = new SnapshotRecords<>(Comparator.naturalOrder());
	private final SnapshotRecords<Long, Integer> policies = new SnapshotRecords<>(Comparator.naturalOrder());
	private final SparseProductAmounts<ProductKey> reserved = new SparseProductAmounts<>();
	private long newestPolicyRevision = -1;
	ProductAmount amount(ProductKey key) { return reserved.amount(key); }
	int size() { return active.size(); }
	boolean owns(LedgerTransaction transaction) { return active.get(transaction.id()) == transaction; }
	void add(LedgerTransaction transaction) {
		if (active.containsKey(transaction.id())) throw new IllegalArgumentException("Duplicate reservation identity");
		transaction.inputs().forEach(reserved::add);
		active.put(transaction.id(), transaction);
		records.put(transaction.id(), transaction.checkpoint());
		Integer count = policies.get(transaction.policyRevision());
		policies.put(transaction.policyRevision(), count == null ? 1 : Math.incrementExact(count));
		newestPolicyRevision = Math.max(newestPolicyRevision, transaction.policyRevision());
	}
	void remove(LedgerTransaction transaction) {
		if (!owns(transaction)) throw new IllegalArgumentException("Foreign reservation");
		transaction.inputs().forEach((key, value) -> reserved.set(key, reserved.amount(key).subtract(value)));
		active.remove(transaction.id());
		records.remove(transaction.id());
		int count = policies.get(transaction.policyRevision());
		if (count == 1) policies.remove(transaction.policyRevision());
		else policies.put(transaction.policyRevision(), count - 1);
		newestPolicyRevision = policies.isEmpty() ? -1 : policies.lastKey();
	}
	void changed(LedgerTransaction transaction) {
		if (!owns(transaction)) throw new IllegalArgumentException("Foreign reservation");
		records.put(transaction.id(), transaction.checkpoint());
	}
	long newestPolicyRevision() { return newestPolicyRevision; }
	Map<ProductKey, ProductAmount> snapshot() { return reserved.snapshot(); }
	java.util.List<LedgerCheckpoint.Pending> checkpoint() { return records.valuesSnapshot(); }
	LedgerTransaction find(UUID id) { return active.get(id); }
}
