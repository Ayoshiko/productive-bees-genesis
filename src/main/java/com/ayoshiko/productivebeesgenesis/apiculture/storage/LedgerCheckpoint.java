package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 完整恢复边界；预约汇总由明细重建，不能使用查询 Snapshot 代替。 */
public record LedgerCheckpoint(long revision, Map<ProductKey, ProductAmount> balances, List<Pending> transactions) {
	public record Pending(UUID id, long policyRevision, LedgerTransaction.State state,
			Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs) {
		public Pending {
			Objects.requireNonNull(id);
			if (policyRevision < 0 || state != LedgerTransaction.State.RESERVED && state != LedgerTransaction.State.PAID) {
				throw new IllegalArgumentException("Invalid pending transaction");
			}
			inputs = positive(inputs); outputs = positive(outputs);
			if (inputs.isEmpty() && outputs.isEmpty()) throw new IllegalArgumentException("Empty transaction");
		}
	}
	public LedgerCheckpoint {
		if (revision < 0) throw new IllegalArgumentException("Negative ledger revision");
		balances = positive(balances); transactions = List.copyOf(transactions);
		var identities = ConcurrentHashMap.<UUID>newKeySet();
		Map<ProductKey, ProductAmount> reserved = new ConcurrentHashMap<>();
		for (var transaction : transactions) {
			if (!identities.add(transaction.id())) throw new IllegalArgumentException("Duplicate transaction identity");
			transaction.inputs().forEach((key, amount) -> reserved.merge(key, amount, ProductAmount::add));
		}
		for (var entry : reserved.entrySet()) {
			if (entry.getValue().compareTo(balances.getOrDefault(entry.getKey(), ProductAmount.ZERO)) > 0) {
				throw new IllegalArgumentException("Reservation exceeds owned balance");
			}
		}
	}
	private static Map<ProductKey, ProductAmount> positive(Map<ProductKey, ProductAmount> amounts) {
		var result = Map.copyOf(amounts);
		if (result.values().stream().anyMatch(ProductAmount::isZero)) throw new IllegalArgumentException("Zero stored amount");
		return result;
	}
}
