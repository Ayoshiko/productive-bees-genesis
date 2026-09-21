package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 一个网络的权威余额；绑定创建线程，所有多字段操作和外部回调均阻止重入。 */
public final class ProductLedger {
	public enum Action { SIMULATE, EXECUTE }
	public record Snapshot(long revision, Map<ProductKey, ProductAmount> balances,
			Map<ProductKey, ProductAmount> reserved, int pendingTransactions) {
		public Snapshot { balances = Map.copyOf(balances); reserved = Map.copyOf(reserved); }
	}
	private final Object authority = new Object();
	private final Thread owner = Thread.currentThread();
	private final ProductPolicyRegistry policy;
	private final PagedProductAmounts balances;
	private final ReservationBook reservations = new ReservationBook();
	private final int maxPending;
	private long revision;
	private boolean entered;
	private LedgerCheckpoint captured;
	private final java.util.Set<ProductKey> changedKeys = ConcurrentHashMap.newKeySet();
	private boolean changesOverflowed;

	public ProductLedger(ProductPolicyRegistry policy, int maxPending) {
		this(policy, maxPending, new PagedProductAmounts());
	}
	private ProductLedger(ProductPolicyRegistry policy, int maxPending, PagedProductAmounts balances) {
		this.policy = Objects.requireNonNull(policy);
		if (maxPending <= 0) throw new IllegalArgumentException("Invalid pending work budget");
		this.maxPending = maxPending; this.balances = balances;
	}
	public Snapshot snapshot() {
		return guarded(() -> new Snapshot(revision, balances.snapshot(), reservations.snapshot(), reservations.size()));
	}
	public LedgerCheckpoint checkpoint() {
		return guarded(() -> {
			if (captured == null || captured.revision() != revision) {
				captured = LedgerCheckpoint.capture(revision, balances, reservations, changesOverflowed ? null : captured, changedKeys);
				changedKeys.clear(); changesOverflowed = false;
			}
			return captured;
		});
	}
	public void validateCheckpointPolicy(ProductPolicyRegistry expected) {
		guarded(() -> {
			if (policy != expected || reservations.newestPolicyRevision() > policy.snapshot().revision()) {
				throw new IllegalArgumentException("Checkpoint policy differs from ledger authority");
			}
			return null;
		});
	}
	public static ProductLedger restore(ProductPolicyRegistry policy, int maxPending, LedgerCheckpoint checkpoint) {
		Objects.requireNonNull(checkpoint);
		if (checkpoint.transactions().size() > maxPending) throw new IllegalArgumentException("Pending recovery exceeds configured work budget");
		var ledger = new ProductLedger(policy, maxPending, PagedProductAmounts.restore(checkpoint.balances()));
		checkpoint.transactions().forEach(pending -> ledger.reservations.add(new LedgerTransaction(ledger.authority, pending)));
		ledger.revision = checkpoint.revision();
		ledger.captured = checkpoint;
		return ledger;
	}
	/** 只返回本次加载重建的句柄；旧实例句柄不能跨恢复重用。 */
	public LedgerTransaction pending(java.util.UUID id) { return guarded(() -> reservations.find(Objects.requireNonNull(id))); }
	/** 领域私有候选接收已付费产物；调用方必须把来源清空与本账本一同发布，不能用于外部存入。 */
	public LedgerTransaction importPaidOutput(LedgerCheckpoint.Pending paid) {
		return guarded(() -> {
			if (paid.state() != LedgerTransaction.State.PAID || !paid.inputs().isEmpty()
					|| paid.policyRevision() > policy.snapshot().revision() || reservations.find(paid.id()) != null
					|| reservations.size() >= maxPending) throw new IllegalArgumentException("Invalid paid output receipt");
			var transaction = new LedgerTransaction(authority, paid);
			advanceRevision(); reservations.add(transaction); return transaction;
		});
	}
	public ProductAmount available(ProductKey key) { return guarded(() -> availableInternal(key)); }
	private ProductAmount availableInternal(ProductKey key) { return balances.amount(key).subtract(reservations.amount(key)); }

	public ProductAmount insert(ProductKey key, ProductAmount offered, Action action) {
		return guarded(() -> {
			Objects.requireNonNull(action);
			if (offered.isZero() || !policy.evaluate(key).allowed()) return ProductAmount.ZERO;
			if (action == Action.EXECUTE) addOwned(key, offered);
			return offered;
		});
	}
	public ProductAmount extract(ProductKey key, ProductAmount requested, Action action) {
		return guarded(() -> {
			Objects.requireNonNull(action);
			ProductAmount taken = availableInternal(key).min(requested);
			if (action == Action.EXECUTE && !taken.isZero()) {
				ProductAmount after = balances.amount(key).subtract(taken);
				advanceRevision();
				balances.set(key, after);
				changed(key);
			}
			return taken;
		});
	}
	public boolean canPrepare(Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs, long policyRevision) {
		return guarded(() -> eligible(normalize(inputs), normalize(outputs), policyRevision));
	}
	public LedgerTransaction prepare(Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs, long policyRevision) {
		return guarded(() -> prepareInternal(inputs, outputs, policyRevision));
	}
	public LedgerTransaction prepareAtRevision(Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs,
			long expectedLedgerRevision, long policyRevision) {
		return guarded(() -> revision == expectedLedgerRevision ? prepareInternal(inputs, outputs, policyRevision) : null);
	}
	private LedgerTransaction prepareInternal(Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs, long policyRevision) {
		var debit = normalize(inputs);
		var credit = normalize(outputs);
		if (!eligible(debit, credit, policyRevision)) return null;
		var transaction = new LedgerTransaction(authority, policyRevision, debit, credit);
		advanceRevision();
		reservations.add(transaction);
		debit.keySet().forEach(this::changed);
		return transaction;
	}
	private boolean eligible(Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs, long policyRevision) {
		if (reservations.size() >= maxPending || policy.snapshot().revision() != policyRevision || inputs.isEmpty() && outputs.isEmpty()) return false;
		for (var input : inputs.entrySet()) if (availableInternal(input.getKey()).compareTo(input.getValue()) < 0) return false;
		for (var output : outputs.keySet()) if (!policy.evaluate(output).allowed()) return false;
		return true;
	}
	/** 调用方确认工作已实际付费后冻结旧版本输出资格；此方法不代替扣除能量。 */
	public boolean markPaid(LedgerTransaction transaction) {
		return guarded(() -> {
			requireOwned(transaction);
			if (transaction.state() == LedgerTransaction.State.PAID) return true;
			if (transaction.policyRevision() != policy.snapshot().revision()) {
				cancelInternal(transaction);
				return false;
			}
			advanceRevision();
			transaction.state(LedgerTransaction.State.PAID);
			reservations.changed(transaction);
			return true;
		});
	}
	public boolean commit(LedgerTransaction transaction) {
		return guarded(() -> {
			requireAuthority(transaction);
			if (transaction.state() == LedgerTransaction.State.COMMITTED) return true;
			if (transaction.state() == LedgerTransaction.State.CANCELLED) return false;
			requireOwned(transaction);
			if (transaction.state() != LedgerTransaction.State.PAID && transaction.policyRevision() != policy.snapshot().revision()) {
				cancelInternal(transaction);
				return false;
			}
			// 先完成全部精确计算，任意校验失败均不修改任何余额。
			Map<ProductKey, ProductAmount> updates = new ConcurrentHashMap<>();
			transaction.inputs().forEach((key, value) -> updates.put(key, balances.amount(key).subtract(value)));
			transaction.outputs().forEach((key, value) -> updates.put(key, updates.getOrDefault(key, balances.amount(key)).add(value)));
			advanceRevision();
			updates.forEach(balances::set);
			updates.keySet().forEach(this::changed);
			reservations.remove(transaction);
			transaction.state(LedgerTransaction.State.COMMITTED);
			return true;
		});
	}
	public boolean cancel(LedgerTransaction transaction) {
		return guarded(() -> {
			requireAuthority(transaction);
			if (transaction.state() == LedgerTransaction.State.CANCELLED) return true;
			if (transaction.state() == LedgerTransaction.State.COMMITTED || transaction.state() == LedgerTransaction.State.PAID) return false;
			requireOwned(transaction);
			cancelInternal(transaction);
			return true;
		});
	}
	private void cancelInternal(LedgerTransaction transaction) {
		advanceRevision();
		transaction.inputs().keySet().forEach(this::changed);
		reservations.remove(transaction);
		transaction.state(LedgerTransaction.State.CANCELLED);
	}
	private void requireOwned(LedgerTransaction transaction) {
		requireAuthority(transaction);
		if (!reservations.owns(Objects.requireNonNull(transaction))) throw new IllegalArgumentException("Foreign or finished ledger transaction");
	}
	private void requireAuthority(LedgerTransaction transaction) {
		if (!Objects.requireNonNull(transaction).belongsTo(authority)) throw new IllegalArgumentException("Foreign ledger transaction");
	}
	private static Map<ProductKey, ProductAmount> normalize(Map<ProductKey, ProductAmount> amounts) {
		Map<ProductKey, ProductAmount> copy = new ConcurrentHashMap<>();
		amounts.forEach((key, value) -> { Objects.requireNonNull(key); if (!value.isZero()) copy.put(key, value); });
		return Map.copyOf(copy);
	}
	private void advanceRevision() { revision = Math.incrementExact(revision); }
	private void addOwned(ProductKey key, ProductAmount amount) {
		if (amount.isZero()) return;
		ProductAmount after = balances.amount(key).add(amount);
		advanceRevision();
		balances.set(key, after);
		changed(key);
	}
	private void changed(ProductKey key) {
		if (captured == null || changesOverflowed) return;
		changedKeys.add(key);
		if (changedKeys.size() > 4096) { changedKeys.clear(); changesOverflowed = true; }
	}
	/** 仅归还由 TransferStaging 实际扣出但未交付的本账本资产，不能供外部普通存入调用。 */
	void restoreStaged(ProductKey key, ProductAmount amount) { guarded(() -> { addOwned(key, amount); return null; }); }
	<T> T externalCall(Supplier<T> callback) { return guarded(callback); }
	private synchronized <T> T guarded(Supplier<T> action) {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Ledger belongs to its server thread");
		if (entered) throw new IllegalStateException("Reentrant ledger operation");
		entered = true;
		try { return action.get(); }
		finally { entered = false; }
	}
}
