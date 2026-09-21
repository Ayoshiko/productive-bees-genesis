package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 对单个候选精确求额度；组总量来自索引，层内 EXACT 例外逐条处理。额度本身不是预约。 */
public final class ReserveAllowanceScan {
	public static final class Permit {
		private final LedgerCheckpoint ledger;
		private final ReservePolicy policy;
		private final ProductKey key;
		private final ProductAmount amount;
		private Permit(LedgerCheckpoint ledger, ReservePolicy policy, ProductKey key, ProductAmount amount) {
			this.ledger = ledger; this.policy = policy; this.key = key; this.amount = amount;
		}
		public ProductAmount amount() { return amount; }
		public boolean matches(LedgerCheckpoint current, ReservePolicy expected, ProductKey input) { return ledger == current && policy == expected && key.equals(input); }
	}
	private static final class Group {
		final ProductMatcher matcher; final ReserveLimit limit; ProductAmount total;
		Group(ProductMatcher matcher, ReserveLimit limit, ProductAmount total) { this.matcher = matcher; this.limit = limit; this.total = total; }
	}
	private final Thread owner = Thread.currentThread();
	private final ProcessingStockIndex.View stock;
	private final ReservePolicy policy;
	private final ProductKey key;
	private final List<Group> groups = new ArrayList<>(2);
	private ProductAmount allowance;
	private Iterator<Map.Entry<ProductMatcher, ReserveLimit>> exceptions;
	private int layer;
	public ReserveAllowanceScan(ProcessingStockIndex.View stock, ReservePolicy policy, ProductKey key) {
		this.stock = stock; this.policy = policy; this.key = key; allowance = stock.amount(key);
	}
	public boolean step() {
		check(); if (layer == 2) return true;
		if (exceptions != null) {
			if (exceptions.hasNext()) {
				var entry = exceptions.next();
				if (entry.getKey().mode() == ProductMatcher.Mode.EXACT) for (var group : groups)
					if (group.matcher.matches(entry.getKey().template())) group.total = group.total.subtract(stock.amount(entry.getKey().template()));
				return false;
			}
			for (var group : groups) allowance = allowance.min(group.limit.usable(group.total));
			exceptions = null; groups.clear(); layer++; return layer == 2;
		}
		var current = layer == 0 ? policy.global() : policy.rule();
		var exact = current.entries().get(new ProductMatcher(ProductMatcher.Mode.EXACT, key));
		if (exact != null) { allowance = allowance.min(exact.usable(stock.amount(key))); layer++; return layer == 2; }
		addGroup(current, new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, key));
		if (key.component(ProductMatcher.BEE_TYPE).filter(value -> value.getId() == 8 && !value.getAsString().isBlank()).isPresent())
			addGroup(current, new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, key));
		if (groups.isEmpty()) { allowance = allowance.min(current.fallback().usable(stock.amount(key))); layer++; }
		else exceptions = current.entries().entrySet().iterator();
		return layer == 2;
	}
	private void addGroup(ReservePolicy.Layer current, ProductMatcher matcher) {
		var limit = current.entries().get(matcher); if (limit != null) groups.add(new Group(matcher, limit, stock.group(matcher)));
	}
	public Permit permit() { check(); if (layer != 2) throw new IllegalStateException("Reserve check incomplete"); return new Permit(stock.ledger(), policy, key, allowance); }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Reserve check belongs to its server thread"); }
}
