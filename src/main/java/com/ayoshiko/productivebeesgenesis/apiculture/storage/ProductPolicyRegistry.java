package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 统一准入入口；缓存及动态发现严格属于当前 policyRevision，不管理已有余额的提取。 */
public final class ProductPolicyRegistry {
	public enum Verdict { ALLOWED, NOT_A_PRODUCT, UNCONFIRMED_VARIANT, ADAPTER_FAILURE }
	public record Decision(long revision, Verdict verdict, String source) {
		public boolean allowed() { return verdict == Verdict.ALLOWED; }
	}
	public record Discovery(String adapterId, ProductKey key) {
		public Discovery {
			if (adapterId == null || adapterId.isBlank()) throw new IllegalArgumentException("Missing discovery adapter");
			Objects.requireNonNull(key);
		}
	}
	private static final int CACHE_LIMIT = 4096;
	private ProductPolicySnapshot snapshot;
	private final ConcurrentHashMap<ProductKey, Decision> cache = new ConcurrentHashMap<>();
	private final Set<Discovery> discoveries = ConcurrentHashMap.newKeySet();
	private boolean evaluating;

	public ProductPolicyRegistry(ProductPolicySnapshot snapshot) { this.snapshot = Objects.requireNonNull(snapshot); }
	public synchronized ProductPolicySnapshot snapshot() { return snapshot; }
	public synchronized Set<Discovery> discoveries() {
		if (evaluating) throw new IllegalStateException("Reentrant discovery snapshot");
		return Set.copyOf(discoveries);
	}
	/** 重建当前配方策略后重新验证发现；旧版本发现不得直接给新配方授予准入。 */
	public synchronized void restoreDiscoveries(long policyRevision, Set<Discovery> saved) {
		if (evaluating || !discoveries.isEmpty()) throw new IllegalStateException("Discovery restore requires an unused registry");
		if (policyRevision != snapshot.revision()) return;
		var restored = ConcurrentHashMap.<Discovery>newKeySet();
		evaluating = true;
		try {
			for (var discovery : saved) {
				boolean valid = snapshot.dynamicRules(discovery.key()).stream().anyMatch(rule -> rule.requiresDiscovery()
						&& rule.adapterId().equals(discovery.adapterId()) && rule.validator().test(discovery.key()));
				if (!valid) throw new IllegalArgumentException("Saved discovery no longer matches its policy");
				restored.add(discovery);
			}
			discoveries.addAll(restored); cache.clear();
		} finally { evaluating = false; }
	}
	public synchronized void replace(ProductPolicySnapshot next) {
		if (evaluating) throw new IllegalStateException("Policy callback cannot replace its registry");
		if (next.revision() <= snapshot.revision()) throw new IllegalArgumentException("Policy revision must advance");
		snapshot = next;
		cache.clear();
		discoveries.clear();
	}
	public synchronized Decision evaluate(ProductKey key) {
		Objects.requireNonNull(key);
		if (evaluating) throw new IllegalStateException("Reentrant product policy callback");
		Decision cached = cache.get(key);
		if (cached != null) return cached;
		evaluating = true;
		try {
			Decision result = decide(key);
			if (cache.size() >= CACHE_LIMIT) cache.clear();
			if (result.verdict() != Verdict.ADAPTER_FAILURE) cache.put(key, result);
			return result;
		} finally { evaluating = false; }
	}
	private Decision decide(ProductKey key) {
		for (var descriptor : snapshot.descriptors(key)) {
			if (descriptor.accepts(key)) return decision(Verdict.ALLOWED, descriptor.recipeId());
		}
		Verdict rejected = Verdict.NOT_A_PRODUCT;
		String source = "";
		for (var rule : snapshot.dynamicRules(key)) {
			try {
				if (!rule.validator().test(key)) continue;
				if (!rule.requiresDiscovery() || discoveries.contains(new Discovery(rule.adapterId(), key))) {
					return decision(Verdict.ALLOWED, rule.adapterId());
				}
				rejected = Verdict.UNCONFIRMED_VARIANT;
				source = rule.adapterId();
			} catch (RuntimeException failure) {
				rejected = Verdict.ADAPTER_FAILURE;
				source = rule.adapterId() + ": " + failure.getClass().getSimpleName();
			}
		}
		return decision(rejected, source);
	}
	/** 仅供已经验证真实生成结果的服务端适配器调用；尚未接入玩家、管道或数据包。 */
	public synchronized boolean recordVerifiedProduction(String adapterId, ProductKey key, long expectedRevision) {
		if (evaluating) throw new IllegalStateException("Reentrant discovery");
		if (snapshot.revision() != expectedRevision) return false;
		evaluating = true;
		try {
			for (var rule : snapshot.dynamicRules(key)) {
				if (rule.adapterId().equals(adapterId) && rule.requiresDiscovery() && rule.validator().test(key)) {
					discoveries.add(new Discovery(adapterId, key));
					cache.remove(key);
					return true;
				}
			}
			return false;
		} catch (RuntimeException failure) { return false; }
		finally { evaluating = false; }
	}
	private Decision decision(Verdict verdict, String source) { return new Decision(snapshot.revision(), verdict, source); }
}
