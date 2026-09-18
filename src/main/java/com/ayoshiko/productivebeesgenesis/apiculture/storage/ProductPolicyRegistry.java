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
	private record Discovery(String adapterId, ProductKey key) { }
	private static final int CACHE_LIMIT = 4096;
	private ProductPolicySnapshot snapshot;
	private final ConcurrentHashMap<ProductKey, Decision> cache = new ConcurrentHashMap<>();
	private final Set<Discovery> discoveries = ConcurrentHashMap.newKeySet();
	private boolean evaluating;

	public ProductPolicyRegistry(ProductPolicySnapshot snapshot) { this.snapshot = Objects.requireNonNull(snapshot); }
	public synchronized ProductPolicySnapshot snapshot() { return snapshot; }
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
