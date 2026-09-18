package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record ProcessingRule(String id, long revision, boolean enabled, int priority, int weight, int batchLimit,
		Selector selector, ReservePolicy reserves) {
	public sealed interface Selector permits Match, Tag, Goal { }
	public record Match(ProductMatcher matcher) implements Selector {
		public Match { Objects.requireNonNull(matcher); }
	}
	public record Tag(ProductKey.Kind kind, ResourceLocation tag) implements Selector {
		public Tag { Objects.requireNonNull(kind); Objects.requireNonNull(tag); }
	}
	public record Goal(ProductKey product, ProductAmount lower, ProductAmount upper) implements Selector {
		public Goal {
			Objects.requireNonNull(product);
			if (lower.compareTo(upper) >= 0) throw new IllegalArgumentException("Invalid goal watermarks");
		}
	}
	public ProcessingRule {
		if (id == null || id.isBlank() || revision < 0 || weight <= 0 || batchLimit <= 0) throw new IllegalArgumentException("Invalid processing rule");
		Objects.requireNonNull(selector); Objects.requireNonNull(reserves);
		if (reserves.scope() != ReservePolicy.Scope.LOCAL_PROCESSING) throw new IllegalArgumentException("Wrong reserve consumer scope");
	}
}
