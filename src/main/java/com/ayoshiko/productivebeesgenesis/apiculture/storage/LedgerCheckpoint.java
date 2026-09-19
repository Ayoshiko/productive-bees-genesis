package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 完整恢复边界；预约汇总由明细重建，不能使用查询 Snapshot 代替。 */
public final class LedgerCheckpoint {
	private final long revision;
	private final Map<ProductKey, ProductAmount> balances;
	private final List<Pending> transactions;
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
	public LedgerCheckpoint(long revision, Map<ProductKey, ProductAmount> balances, List<Pending> transactions) {
		this(revision, positive(balances), List.copyOf(transactions), false);
	}
	private LedgerCheckpoint(long revision, Map<ProductKey, ProductAmount> balances, List<Pending> transactions, boolean captured) {
		if (revision < 0) throw new IllegalArgumentException("Negative ledger revision");
		this.revision = revision; this.balances = balances; this.transactions = transactions;
		if (captured) return;
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
	/** 仅账本在同一临界区提供已经维护好约束的余额与预约根；外部构造仍完整校验。 */
	static LedgerCheckpoint capture(long revision, PagedProductAmounts balances, ReservationBook reservations) {
		return new LedgerCheckpoint(revision, balances.snapshot(), reservations.checkpoint(), true);
	}
	public long revision() { return revision; }
	public Map<ProductKey, ProductAmount> balances() { return balances; }
	public List<Pending> transactions() { return transactions; }
	@Override public boolean equals(Object other) {
		return this == other || other instanceof LedgerCheckpoint checkpoint && revision == checkpoint.revision
				&& balances.equals(checkpoint.balances) && transactions.equals(checkpoint.transactions);
	}
	@Override public int hashCode() { return Objects.hash(revision, balances, transactions); }
	@Override public String toString() {
		return "LedgerCheckpoint[revision=" + revision + ", balances=" + balances.size() + ", transactions=" + transactions.size() + "]";
	}
	private static Map<ProductKey, ProductAmount> positive(Map<ProductKey, ProductAmount> amounts) {
		return PagedProductAmounts.immutablePositive(amounts);
	}
}
