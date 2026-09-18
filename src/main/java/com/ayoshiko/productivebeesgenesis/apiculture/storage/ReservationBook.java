package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 只在账本临界区访问；事务预约与玩家保留策略是不同账户。 */
final class ReservationBook {
	private final Map<UUID, LedgerTransaction> active = new ConcurrentHashMap<>();
	private final SparseProductAmounts<ProductKey> reserved = new SparseProductAmounts<>();
	ProductAmount amount(ProductKey key) { return reserved.amount(key); }
	int size() { return active.size(); }
	boolean owns(LedgerTransaction transaction) { return active.get(transaction.id()) == transaction; }
	void add(LedgerTransaction transaction) {
		transaction.inputs().forEach(reserved::add);
		active.put(transaction.id(), transaction);
	}
	void remove(LedgerTransaction transaction) {
		if (!owns(transaction)) throw new IllegalArgumentException("Foreign reservation");
		transaction.inputs().forEach((key, value) -> reserved.set(key, reserved.amount(key).subtract(value)));
		active.remove(transaction.id());
	}
	Map<ProductKey, ProductAmount> snapshot() { return reserved.snapshot(); }
	java.util.List<LedgerCheckpoint.Pending> checkpoint() { return active.values().stream().map(LedgerTransaction::checkpoint).toList(); }
	LedgerTransaction find(UUID id) { return active.get(id); }
}
