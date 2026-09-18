package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;

/** 低于低水位开始补充，达到高水位停止；中间区间保持原状态。 */
public record WatermarkState(boolean replenishing) {
	public WatermarkState update(ProductAmount current, ProductAmount lower, ProductAmount upper) {
		if (lower.compareTo(upper) >= 0) throw new IllegalArgumentException("Watermarks must have a nonempty hysteresis band");
		boolean next = current.compareTo(upper) >= 0 ? false : current.compareTo(lower) < 0 || replenishing;
		return next == replenishing ? this : new WatermarkState(next);
	}
}
