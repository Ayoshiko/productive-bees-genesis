package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.Objects;

/** 配方／蜂种及上下文的能力查询身份；版本由调用方的配方快照提供。 */
public record WorkKey(Kind kind, String id, long recipeRevision, String contextKey) {
	public enum Kind { BEE_CYCLE, CENTRIFUGE_RECIPE }

	public WorkKey {
		Objects.requireNonNull(kind);
		if (id == null || id.isBlank() || contextKey == null || contextKey.isBlank() || recipeRevision < 0) {
			throw new IllegalArgumentException("Incomplete work identity");
		}
	}
}
