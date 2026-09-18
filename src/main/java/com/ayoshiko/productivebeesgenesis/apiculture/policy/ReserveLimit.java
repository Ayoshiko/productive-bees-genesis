package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import java.util.Objects;

public record ReserveLimit(Mode mode, ProductAmount floor) {
	public enum Mode { FLOOR, ALL }
	public static final ReserveLimit NONE = floor(ProductAmount.ZERO);
	public static final ReserveLimit ALL = new ReserveLimit(Mode.ALL, ProductAmount.ZERO);
	public ReserveLimit {
		Objects.requireNonNull(mode); Objects.requireNonNull(floor);
		if (mode == Mode.ALL && !floor.isZero()) throw new IllegalArgumentException("ALL is not a numeric floor");
	}
	public static ReserveLimit floor(ProductAmount amount) { return new ReserveLimit(Mode.FLOOR, amount); }
	public ProductAmount usable(ProductAmount available) {
		return mode == Mode.ALL || available.compareTo(floor) <= 0 ? ProductAmount.ZERO : available.subtract(floor);
	}
}
