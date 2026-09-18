package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;

/** 有限外部转移保管区；结果未知的调用不能自动退款、入账或重试。 */
public final class TransferStaging {
	public enum Direction { IMPORT, EXPORT }
	public enum Phase { CALLING, UNKNOWN, HELD, COMPLETE }
	public record View(UUID id, String endpoint, ProductKey key, Direction direction, long offered,
			ProductAmount held, Phase phase, String failure) { }
	public static final class Transfer {
		private final Object authority;
		private final Thread owner = Thread.currentThread();
		private final UUID id = UUID.randomUUID();
		private final String endpoint;
		private final ProductKey key;
		private final Direction direction;
		private final long offered;
		private ProductAmount held;
		private Phase phase = Phase.CALLING;
		private String failure = "";
		private Transfer(Object authority, String endpoint, ProductKey key, Direction direction, long offered, ProductAmount held) {
			this.authority = authority;
			this.endpoint = endpoint; this.key = key; this.direction = direction; this.offered = offered; this.held = held;
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
	private final int maxTransfers;
	private final long maxUnits;
	private boolean calling;

	public TransferStaging(ProductLedger ledger, int maxTransfers, long maxUnits) {
		this.ledger = Objects.requireNonNull(ledger);
		if (maxTransfers <= 0 || maxUnits <= 0) throw new IllegalArgumentException("Invalid staging budget");
		this.maxTransfers = maxTransfers; this.maxUnits = maxUnits;
	}
	public synchronized List<View> snapshot() { check(); return pending.values().stream().map(Transfer::view).toList(); }
	public synchronized Transfer transfer(String endpoint, ProductKey key, long requested, Direction direction, LongUnaryOperator external) {
		check();
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
		calling = true;
		try {
			long actual = ledger.externalCall(() -> external.applyAsLong(transfer.offered));
			applyActual(transfer, actual);
		} catch (RuntimeException failure) {
			transfer.phase = Phase.UNKNOWN;
			transfer.failure = failure.toString();
		} finally { calling = false; }
		if (transfer.phase == Phase.HELD) settleInternal(transfer);
		return transfer;
	}
	/** 外部适配器获得可靠收据后显式消除不确定性；不得以重试结果冒充原调用收据。 */
	public synchronized void resolve(Transfer transfer, long confirmedActual) {
		check(); requirePending(transfer);
		if (transfer.phase != Phase.UNKNOWN) throw new IllegalStateException("Transfer is not unknown");
		applyActual(transfer, confirmedActual);
		settleInternal(transfer);
	}
	public synchronized boolean settle(Transfer transfer) {
		check();
		if (transfer.authority != authority) throw new IllegalArgumentException("Foreign transfer");
		if (transfer.phase == Phase.COMPLETE) return true;
		requirePending(transfer);
		if (transfer.phase != Phase.HELD) return false;
		settleInternal(transfer);
		return transfer.phase == Phase.COMPLETE;
	}
	private void applyActual(Transfer transfer, long actual) {
		if (actual < 0 || actual > transfer.offered) throw new IllegalArgumentException("Invalid external actual amount");
		transfer.held = ProductAmount.of(transfer.direction == Direction.IMPORT ? actual : transfer.offered - actual);
		transfer.phase = Phase.HELD;
		transfer.failure = "";
	}
	private void settleInternal(Transfer transfer) {
		if (transfer.direction == Direction.EXPORT) {
			ledger.restoreStaged(transfer.key, transfer.held);
			transfer.held = ProductAmount.ZERO;
		} else {
			var accepted = ledger.insert(transfer.key, transfer.held, ProductLedger.Action.EXECUTE);
			transfer.held = transfer.held.subtract(accepted);
		}
		if (transfer.held.isZero()) {
			transfer.phase = Phase.COMPLETE;
			pending.remove(transfer.id);
		}
	}
	private void requirePending(Transfer transfer) {
		if (pending.get(transfer.id) != transfer) throw new IllegalArgumentException("Foreign transfer");
	}
	private void check() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Staging belongs to its server thread");
		if (calling) throw new IllegalStateException("Reentrant transfer callback");
	}
}
