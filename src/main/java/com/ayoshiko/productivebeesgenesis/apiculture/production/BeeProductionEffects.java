package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 周期开始时冻结的插件效果；只转换该已付事件，不消费宿主已有库存。 */
public record BeeProductionEffects(boolean essence, boolean discardByproducts, float geneChance,
		List<ProductKey> genes, List<Conversion> conversions, Set<ProductKey> discarded) {
	public static final BeeProductionEffects NONE = new BeeProductionEffects(false, false, 0, List.of(), List.of(), Set.of());
	public record Conversion(ProductKey source, ProductKey target, int input, int output, boolean chained) {
		public Conversion {
			Objects.requireNonNull(source);
			Objects.requireNonNull(target);
			if (source.kind() != ProductKey.Kind.ITEM || target.kind() != ProductKey.Kind.ITEM
					|| source.equals(target) || input < 1 || output < 1) throw new IllegalArgumentException("Invalid bee conversion");
		}
	}
	public BeeProductionEffects {
		genes = List.copyOf(genes);
		conversions = List.copyOf(conversions);
		discarded = Set.copyOf(discarded);
		if (!Float.isFinite(geneChance) || geneChance < 0 || geneChance > 1 || genes.size() > 24
				|| (geneChance == 0) != genes.isEmpty() || new HashSet<>(genes).size() != genes.size()
				|| genes.stream().anyMatch(key -> key.kind() != ProductKey.Kind.ITEM
						|| !key.id().toString().equals("productivebees:gene"))
				|| !essence && !conversions.isEmpty() || !discardByproducts && !discarded.isEmpty())
			throw new IllegalArgumentException("Invalid bee production effects");
		var sources = new HashSet<ProductKey>();
		for (var rule : conversions) if (!sources.add(rule.source())) throw new IllegalArgumentException("Duplicate bee conversion");
	}
	public boolean enabled() { return essence || discardByproducts || geneChance > 0; }
	public BeeProductionEffects withRules(List<Conversion> rules, Set<ProductKey> removed) {
		return new BeeProductionEffects(essence, discardByproducts, geneChance, genes, rules, removed);
	}
	void sampleGene(Map<ProductKey, ProductAmount> outputs, BeeCycleRandom cycle) {
		if (genes.isEmpty()) return;
		var random = cycle.outputStream(Integer.MAX_VALUE - 1, ProductAmount.ZERO);
		if (random.nextFloat() > geneChance) return;
		outputs.merge(genes.get(random.nextInt(genes.size())), ProductAmount.of(1), ProductAmount::add);
	}
	public Map<ProductKey, ProductAmount> finish(Map<ProductKey, ProductAmount> raw) {
		var result = new HashMap<ProductKey, ProductAmount>();
		var rules = new HashMap<ProductKey, Conversion>();
		for (var rule : conversions) rules.put(rule.source(), rule);
		for (var entry : raw.entrySet()) {
			if (discarded.contains(entry.getKey())) continue;
			var rule = rules.get(entry.getKey());
			if (rule == null) {
				add(result, entry.getKey(), entry.getValue());
				continue;
			}
			var divided = entry.getValue().exact().divideAndRemainder(BigInteger.valueOf(rule.input()));
			add(result, rule.source(), ProductAmount.of(divided[1]));
			var produced = ProductAmount.of(divided[0]).multiply(rule.output());
			var next = rule.chained() ? rules.get(rule.target()) : null;
			if (next == null) add(result, rule.target(), produced);
			else {
				var second = produced.exact().divideAndRemainder(BigInteger.valueOf(next.input()));
				add(result, rule.target(), ProductAmount.of(second[1]));
				add(result, next.target(), ProductAmount.of(second[0]).multiply(next.output()));
			}
		}
		return Map.copyOf(result);
	}
	public Set<ProductKey> resultKeys() {
		var keys = new HashSet<>(genes);
		for (var rule : conversions) {
			keys.add(rule.source());
			keys.add(rule.target());
		}
		return Set.copyOf(keys);
	}
	private static void add(Map<ProductKey, ProductAmount> outputs, ProductKey key, ProductAmount amount) {
		if (!amount.isZero()) outputs.merge(key, amount, ProductAmount::add);
	}
}
