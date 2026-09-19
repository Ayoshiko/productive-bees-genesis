package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import java.util.Map;
import java.util.Objects;

/** 三种消费作用域独立配置；全局与规则层取交集，规则 0 不放松全局层。 */
public record ReservePolicy(Scope scope, Layer global, Layer rule) {
	public enum Scope { LOCAL_PROCESSING, LOCAL_EXPORT, EXTERNAL_SOURCE }
	public record Layer(ReserveLimit fallback, Map<ProductMatcher, ReserveLimit> entries) {
		public static final Layer NONE = new Layer(ReserveLimit.NONE, Map.of());
		public Layer { Objects.requireNonNull(fallback); entries = com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords.immutableMap(entries); }
	}
	public ReservePolicy { Objects.requireNonNull(scope); Objects.requireNonNull(global); Objects.requireNonNull(rule); }
}
