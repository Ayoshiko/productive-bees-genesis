package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 在显式库存快照上一次联合分配；返回额度不是预约，消费前必须重验账本 revision。 */
public final class GroupAllowanceAllocator {
	public record Plan(long ledgerRevision, ReservePolicy.Scope scope, Map<ProductKey, ProductAmount> allowances) {
		public Plan { allowances = Map.copyOf(allowances); }
	}
	private static final class Budget {
		private ProductAmount remaining;
		private Budget(ProductAmount remaining) { this.remaining = remaining; }
	}
	private GroupAllowanceAllocator() { }

	public static Plan allocate(ProductLedger.Snapshot snapshot, ReservePolicy policy) {
		Map<ProductKey, ProductAmount> available = new ConcurrentHashMap<>();
		snapshot.balances().forEach((key, amount) -> available.put(key,
				amount.subtract(snapshot.reserved().getOrDefault(key, ProductAmount.ZERO))));
		if (snapshot.reserved().keySet().stream().anyMatch(key -> !available.containsKey(key))) {
			throw new IllegalArgumentException("Reservation has no balance");
		}
		return new Plan(snapshot.revision(), policy.scope(), allocateAvailable(available, policy));
	}
	public static Map<ProductKey, ProductAmount> allocateAvailable(Map<ProductKey, ProductAmount> stock, ReservePolicy policy) {
		var available = Map.copyOf(stock);
		var keys = available.keySet().stream().sorted(Comparator.comparing(ProductKey::orderingKey)).toList();
		Map<ProductKey, ProductAmount> caps = new ConcurrentHashMap<>(available);
		Map<ProductKey, List<Budget>> constraints = new ConcurrentHashMap<>();
		for (var layer : List.of(policy.global(), policy.rule())) {
			addLayer(layer, keys, available, caps, constraints);
		}
		Map<ProductKey, ProductAmount> result = new ConcurrentHashMap<>();
		for (ProductKey key : keys) {
			ProductAmount allowance = caps.get(key);
			var applicable = constraints.getOrDefault(key, List.of());
			for (Budget budget : applicable) allowance = allowance.min(budget.remaining);
			result.put(key, allowance);
			for (Budget budget : applicable) budget.remaining = budget.remaining.subtract(allowance);
		}
		return Map.copyOf(result);
	}
	private static void addLayer(ReservePolicy.Layer layer, List<ProductKey> keys,
			Map<ProductKey, ProductAmount> available, Map<ProductKey, ProductAmount> caps,
			Map<ProductKey, List<Budget>> constraints) {
		Map<ProductKey, ReserveLimit> exact = new ConcurrentHashMap<>();
		layer.entries().forEach((matcher, limit) -> {
			if (matcher.mode() == ProductMatcher.Mode.EXACT) exact.put(matcher.template(), limit);
		});
		var grouped = ConcurrentHashMap.<ProductKey>newKeySet();
		layer.entries().forEach((matcher, limit) -> {
			if (matcher.mode() == ProductMatcher.Mode.EXACT) return;
			var included = keys.stream().filter(key -> !exact.containsKey(key) && matcher.matches(key)).toList();
			ProductAmount total = ProductAmount.ZERO;
			for (ProductKey key : included) total = total.add(available.get(key));
			var budget = new Budget(limit.usable(total));
			for (ProductKey key : included) {
				constraints.computeIfAbsent(key, ignored -> new ArrayList<>()).add(budget);
				grouped.add(key);
			}
		});
		for (ProductKey key : keys) {
			ReserveLimit limit = exact.get(key);
			if (limit == null && !grouped.contains(key)) limit = layer.fallback();
			if (limit != null) caps.put(key, caps.get(key).min(limit.usable(available.get(key))));
		}
	}
}
