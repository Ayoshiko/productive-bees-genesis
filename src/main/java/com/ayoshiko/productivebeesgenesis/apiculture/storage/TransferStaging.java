package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

/** 有限外部转移保管区；结果未知的调用不能自动退款、入账或重试。 */
public final class TransferStaging {
	public enum Direction { IMPORT, EXPORT }
	public enum Phase { CALLING, UNKNOWN, HELD, COMPLETE }
	public record View(UUID id, String endpoint, ProductKey key, Direction direction, long offered,
			ProductAmount held, Phase phase, String failure) {
		public View {
			Objects.requireNonNull(id); Objects.requireNonNull(key); Objects.requireNonNull(direction);
			Objects.requireNonNull(held); Objects.requireNonNull(phase); Objects.requireNonNull(failure);
			if (endpoint == null || endpoint.isBlank() || offered <= 0 || held.compareTo(ProductAmount.of(offered)) > 0) {
				throw new IllegalArgumentException("Invalid transfer checkpoint");
			}
			if ((phase == Phase.CALLING || phase == Phase.UNKNOWN)
					&& !held.equals(direction == Direction.EXPORT ? ProductAmount.of(offered) : ProductAmount.ZERO)) {
				throw new IllegalArgumentException("Unknown transfer has inconsistent custody");
			}
		}
	}
	public static final class Transfer {
		private final Object authority;
		private final Thread owner = Thread.currentThread();
		private final UUID id;
		private final String endpoint;
		private final ProductKey key;
		private final Direction direction;
		private final long offered;
		private ProductAmount held;
		private Phase phase = Phase.CALLING;
		private String failure = "";
		private Transfer(Object authority, String endpoint, ProductKey key, Direction direction, long offered, ProductAmount held) {
			this.id = UUID.randomUUID();
			this.authority = authority;
			this.endpoint = endpoint; this.key = key; this.direction = direction; this.offered = offered; this.held = held;
		}
		private Transfer(Object authority, View saved) {
			this.authority = authority; id = saved.id(); endpoint = saved.endpoint(); key = saved.key();
			direction = saved.direction(); offered = saved.offered(); held = saved.held();
			phase = saved.phase() == Phase.CALLING ? Phase.UNKNOWN : saved.phase();
			failure = saved.phase() == Phase.CALLING ? "Interrupted external call; receipt required" : saved.failure();
		}
		public View view() {
			if (Thread.currentThread() != owner) throw new IllegalStateException("Read a staging snapshot on its server thread");
			return new View(id, endpoint, key, direction, offered, held, phase, failure);
		}
	}
	private final Object authority = new Object();
	private final ProductLedger ledger;
	private final Thread owner = Thread.currentThread();
	private final Map<UUID, Transfer> pending = new ConcurrentHashMap<>();
	private final SnapshotRecords<UUID, View> records = new SnapshotRecords<>(Comparator.naturalOrder());
	private final int maxTransfers;
	private final long maxUnits;
	private boolean calling;

	public TransferStaging(ProductLedger ledger, int maxTransfers, long maxUnits) {
		this.ledger = Objects.requireNonNull(ledger);
		if (maxTransfers <= 0 || maxUnits <= 0) throw new IllegalArgumentException("Invalid staging budget");
		this.maxTransfers = maxTransfers; this.maxUnits = maxUnits;
	}
	public synchronized List<View> snapshot() { check(); return records.valuesSnapshot(); }
	public synchronized void validateCheckpointLedger(ProductLedger expected) {
		check();
		if (ledger != expected) throw new IllegalArgumentException("Transfer staging belongs to a different ledger");
	}
	public static TransferStaging restore(ProductLedger ledger, int maxTransfers, long maxUnits, List<View> saved) {
		var staging = new TransferStaging(ledger, maxTransfers, maxUnits);
		if (saved.size() > maxTransfers) throw new IllegalArgumentException("Recovery exceeds transfer budget");
		for (var view : saved) {
			if (view.phase() == Phase.COMPLETE || view.offered() > maxUnits
					|| view.phase() == Phase.HELD && view.held().isZero()) throw new IllegalArgumentException("Invalid pending transfer");
			if (staging.pending.putIfAbsent(view.id(), new Transfer(staging.authority, view)) != null) {
				throw new IllegalArgumentException("Duplicate transfer identity");
			}
			staging.refresh(staging.pending.get(view.id()));
		}
		return staging;
	}
	public synchronized Transfer pending(UUID id) { check(); return pending.get(Objects.requireNonNull(id)); }
	public synchronized Transfer transfer(String endpoint, ProductKey key, long requested, Direction direction, LongUnaryOperator external) {
		return guarded(() -> transferInternal(endpoint, key, requested, direction, external));
	}
	private Transfer transferInternal(String endpoint, ProductKey key, long requested, Direction direction, LongUnaryOperator external) {
		Objects.requireNonNull(direction); Objects.requireNonNull(key); Objects.requireNonNull(external);
		if (endpoint == null || endpoint.isBlank() || requested <= 0 || requested > maxUnits) throw new IllegalArgumentException("Invalid transfer request");
		if (pending.size() >= maxTransfers) return null;
		ProductAmount offer = ProductAmount.of(requested);
		ProductAmount accepted = direction == Direction.EXPORT ? ledger.extract(key, offer, ProductLedger.Action.SIMULATE)
				: ledger.insert(key, offer, ProductLedger.Action.SIMULATE);
		if (accepted.isZero()) return null;
		var transfer = new Transfer(authority, endpoint, key, direction, accepted.longSaturated(), ProductAmount.ZERO);
		if (direction == Direction.EXPORT) transfer.held = ledger.extract(key, accepted, ProductLedger.Action.EXECUTE);
		pending.put(transfer.id, transfer);
		try {
			try {
				long actual = ledger.externalCall(() -> external.applyAsLong(transfer.offered));
				applyActual(transfer, actual);
			} catch (RuntimeException failure) {
				transfer.phase = Phase.UNKNOWN;
				transfer.failure = failure.toString();
			}
			if (transfer.phase == Phase.HELD) settleInternal(transfer);
			return transfer;
		} finally { refresh(transfer); }
	}
	/** 外部适配器获得可靠收据后显式消除不确定性；不得以重试结果冒充原调用收据。 */
	public synchronized void resolve(Transfer transfer, long confirmedActual) {
		guarded(() -> {
			requirePending(transfer);
			if (transfer.phase != Phase.UNKNOWN) throw new IllegalStateException("Transfer is not unknown");
			try { applyActual(transfer, confirmedActual); settleInternal(transfer); }
			finally { refresh(transfer); }
			return null;
		});
	}
	public synchronized boolean settle(Transfer transfer) {
		return guarded(() -> {
			if (transfer.authority != authority) throw new IllegalArgumentException("Foreign transfer");
			if (transfer.phase == Phase.COMPLETE) return true;
			requirePending(transfer);
			if (transfer.phase != Phase.HELD) return false;
			try { settleInternal(transfer); }
			finally { refresh(transfer); }
			return transfer.phase == Phase.COMPLETE;
		});
	}
	private void applyActual(Transfer transfer, long actual) {
		if (actual < 0 || actual > transfer.offered) throw new IllegalArgumentException("Invalid external actual amount");
		transfer.held = ProductAmount.of(transfer.direction == Direction.IMPORT ? actual : transfer.offered - actual);
		transfer.phase = Phase.HELD;
		transfer.failure = "";
	}
	private void settleInternal(Transfer transfer) {
		if (transfer.held.isZero()) { complete(transfer); return; }
		if (transfer.direction == Direction.EXPORT) {
			ledger.restoreStaged(transfer.key, transfer.held);
			transfer.held = ProductAmount.ZERO;
		} else {
			var accepted = ledger.insert(transfer.key, transfer.held, ProductLedger.Action.EXECUTE);
			transfer.held = transfer.held.subtract(accepted);
		}
		if (transfer.held.isZero()) {
			complete(transfer);
		}
	}
	private void complete(Transfer transfer) { transfer.phase = Phase.COMPLETE; pending.remove(transfer.id); }
	private void refresh(Transfer transfer) {
		if (pending.containsKey(transfer.id)) records.put(transfer.id, transfer.view());
		else records.remove(transfer.id);
	}
	private void requirePending(Transfer transfer) {
		if (pending.get(transfer.id) != transfer) throw new IllegalArgumentException("Foreign transfer");
	}
	private void check() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Staging belongs to its server thread");
		if (calling) throw new IllegalStateException("Reentrant transfer callback");
	}
	private <T> T guarded(Supplier<T> operation) {
		check(); calling = true;
		try { return operation.get(); }
		finally { calling = false; }
	}
}
