package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProductMatcher;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 可丢弃的可用量索引；初始化与追赶按键分步，不向调度器暴露半成品。 */
public final class ProcessingStockIndex {
	public static final class View {
		private final LedgerCheckpoint ledger;
		private final Map<ProductKey, ProductAmount> available;
		private final Map<ProductMatcher, ProductAmount> groups;
		private View(LedgerCheckpoint ledger, Map<ProductKey, ProductAmount> available, Map<ProductMatcher, ProductAmount> groups) {
			this.ledger = ledger; this.available = available; this.groups = groups;
		}
		public LedgerCheckpoint ledger() { return ledger; }
		public Iterable<ProductKey> keys() { return available.keySet(); }
		public ProductAmount amount(ProductKey key) { return available.getOrDefault(key, ProductAmount.ZERO); }
		public ProductAmount group(ProductMatcher matcher) { return matcher.mode() == ProductMatcher.Mode.EXACT ? amount(matcher.template()) : groups.getOrDefault(matcher, ProductAmount.ZERO); }
	}
	private static final int MAX_DIRTY_KEYS = 4096;
	private final Thread owner = Thread.currentThread();
	private SnapshotRecords<ProductKey, ProductAmount> available;
	private SnapshotRecords<ProductMatcher, ProductAmount> groups;
	private LedgerCheckpoint target;
	private Iterator<ProductKey> initial;
	private final ArrayDeque<ProductKey> pending = new ArrayDeque<>();
	private final Set<ProductKey> queued = ConcurrentHashMap.newKeySet();
	private boolean visitInitial;
	public ProcessingStockIndex(LedgerCheckpoint source) { reset(source); }
	private void reset(LedgerCheckpoint source) {
		target = source; initial = source.balances().keySet().iterator(); pending.clear(); queued.clear();
		available = new SnapshotRecords<>(Comparator.comparing(ProductKey::orderingKey));
		groups = new SnapshotRecords<>(Comparator.comparing(ProductMatcher::mode).thenComparing(value -> value.template().orderingKey()));
	}
	public void update(LedgerCheckpoint next) {
		check(); if (next == target) return;
		var changes = next.changesSince(target);
		if (changes == null || changes.size() > MAX_DIRTY_KEYS) { reset(next); return; }
		target = next;
		for (var key : changes) if (queued.add(key)) {
			if (queued.size() > MAX_DIRTY_KEYS) { reset(next); return; }
			pending.addLast(key);
		}
	}
	/** 一次最多读取一个产品的最新可用量；扫描旧根时仍使用当前根，变化键另行合并。 */
	public boolean step() {
		check(); ProductKey key;
		if (initial != null && (visitInitial || pending.isEmpty())) {
			visitInitial = false;
			if (!initial.hasNext()) { initial = null; return ready(); }
			key = initial.next();
		} else {
			visitInitial = true; key = pending.pollFirst(); if (key == null) return ready(); queued.remove(key);
		}
		var previous = available.get(key); if (previous == null) previous = ProductAmount.ZERO;
		var amount = target.available(key);
		if (!amount.equals(previous)) {
			adjust(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, key), previous, amount);
			if (key.component(ProductMatcher.BEE_TYPE).filter(value -> value.getId() == 8 && !value.getAsString().isBlank()).isPresent())
				adjust(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, key), previous, amount);
			if (amount.isZero()) available.remove(key); else available.put(key, amount);
		}
		return ready();
	}
	private void adjust(ProductMatcher matcher, ProductAmount previous, ProductAmount amount) {
		var total = groups.get(matcher); if (total == null) total = ProductAmount.ZERO;
		total = total.subtract(previous).add(amount);
		if (total.isZero()) groups.remove(matcher); else groups.put(matcher, total);
	}
	public boolean ready() { check(); return initial == null && pending.isEmpty(); }
	public View view() {
		check(); if (!ready()) throw new IllegalStateException("Processing stock index is catching up");
		return new View(target, available.snapshot(), groups.snapshot());
	}
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Processing stock index belongs to its server thread"); }
}
