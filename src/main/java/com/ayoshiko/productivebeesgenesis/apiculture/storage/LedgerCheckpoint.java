package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 完整恢复边界；预约汇总由明细重建，不能使用查询 Snapshot 代替。 */
public final class LedgerCheckpoint {
	private final long revision;
	private final Map<ProductKey, ProductAmount> balances;
	private final List<Pending> transactions;
	private final Map<ProductKey, ProductAmount> reserved;
	private final Object token = new Object();
	private final Object previousToken;
	private final Set<ProductKey> changedKeys;
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
		this(revision, PagedProductAmounts.frozenPositive(balances), List.copyOf(transactions), null, null, Set.of());
	}
	private LedgerCheckpoint(long revision, Map<ProductKey, ProductAmount> balances, List<Pending> transactions,
			Map<ProductKey, ProductAmount> capturedReserved, Object previousToken, Set<ProductKey> changedKeys) {
		if (revision < 0) throw new IllegalArgumentException("Negative ledger revision");
		this.revision = revision; this.balances = balances; this.transactions = transactions;
		this.previousToken = previousToken; this.changedKeys = changedKeys;
		if (capturedReserved != null) { reserved = capturedReserved; return; }
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
		this.reserved = Map.copyOf(reserved);
	}
	/** 仅账本在同一临界区提供已经维护好约束的余额与预约根；外部构造仍完整校验。 */
	static LedgerCheckpoint capture(long revision, PagedProductAmounts balances, ReservationBook reservations, LedgerCheckpoint previous, Set<ProductKey> changed) {
		return new LedgerCheckpoint(revision, balances.snapshot(), reservations.checkpoint(), reservations.snapshot(), previous == null ? null : previous.token, Set.copyOf(changed));
	}
	/** 只由账本签发相邻根的键变化；不保留历史根链，未知来源必须分步重建索引。 */
	public Set<ProductKey> changesSince(LedgerCheckpoint previous) { return previous != null && previous.token == previousToken ? changedKeys : null; }
	public ProductAmount available(ProductKey key) { return balances.getOrDefault(key, ProductAmount.ZERO).subtract(reserved.getOrDefault(key, ProductAmount.ZERO)); }
	/** 单键精确扣减，只分叉余额页；既有预约和变化键凭据随同保留。 */
	public LedgerCheckpoint withdrawExact(ProductKey key, ProductAmount amount) {
		Objects.requireNonNull(key); Objects.requireNonNull(amount);
		if (amount.isZero() || amount.compareTo(available(key)) > 0) throw new IllegalArgumentException("Unfunded product withdrawal");
		var changed = PagedProductAmounts.restore(balances);
		changed.set(key, balances.get(key).subtract(amount));
		return new LedgerCheckpoint(Math.incrementExact(revision), changed.snapshot(), transactions, reserved, token, Set.of(key));
	}
	/** 逐记录恢复；输入摘要与余额的比较必须由调用者按预算推进，结束才可封装。 */
	public static final class RestoreBuilder {
		private final PagedProductAmounts balances = new PagedProductAmounts();
		private final PagedProductAmounts reserved = new PagedProductAmounts();
		private final SnapshotRecords<Integer, Pending> transactions = new SnapshotRecords<>(Integer::compare);
		private final java.util.Set<UUID> identities = ConcurrentHashMap.newKeySet();
		private java.util.Iterator<Map.Entry<ProductKey, ProductAmount>> checks;
		private java.util.Iterator<Map.Entry<ProductKey, ProductAmount>> inputs = java.util.Collections.emptyIterator();
		private java.util.Iterator<Pending> pending;
		private boolean checking;
		public void balance(ProductKey key, ProductAmount amount) {
			if (checking || amount.isZero() || !balances.amount(key).isZero()) throw new IllegalArgumentException("Duplicate or invalid balance");
			balances.set(key, amount);
		}
		public void transaction(Pending value) {
			if (checking || !identities.add(value.id())) throw new IllegalArgumentException("Duplicate transaction");
			transactions.put(transactions.size(), value);
		}
		public boolean validateStep() {
			if (!checking) { checking = true; pending = transactions.valuesSnapshot().iterator(); }
			if (inputs.hasNext()) {
				var entry = inputs.next(); reserved.set(entry.getKey(), reserved.amount(entry.getKey()).add(entry.getValue())); return false;
			}
			if (pending.hasNext()) { inputs = pending.next().inputs().entrySet().iterator(); return false; }
			if (checks == null) checks = reserved.snapshot().entrySet().iterator();
			if (checks.hasNext()) {
				var entry = checks.next();
				if (entry.getValue().compareTo(balances.amount(entry.getKey())) > 0) throw new IllegalArgumentException("Reservation exceeds owned balance");
				return false;
			}
			return true;
		}
		public LedgerCheckpoint finish(long revision) {
			if (!checking || checks == null || checks.hasNext()) throw new IllegalStateException("Ledger validation incomplete");
			return new LedgerCheckpoint(revision, balances.snapshot(), transactions.valuesSnapshot(), reserved.snapshot(), null, Set.of());
		}
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
