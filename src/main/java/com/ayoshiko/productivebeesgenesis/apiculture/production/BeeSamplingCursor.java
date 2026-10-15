package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import java.util.Objects;

/** 当前已付周期的共同轮数与分项续采位置；提交后不再重抽额外轮数。 */
public record BeeSamplingCursor(ProductAmount rolls, int output, ProductAmount consumed) {
	public BeeSamplingCursor {
		Objects.requireNonNull(rolls); Objects.requireNonNull(consumed);
		if (output < 0 || consumed.compareTo(rolls) > 0) throw new IllegalArgumentException("Invalid bee sampling cursor");
	}
}
