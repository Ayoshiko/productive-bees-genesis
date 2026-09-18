package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import java.util.Objects;

/** 非负精确值；只有超 long 的值持有 BigInteger，投影绝不回写余额。 */
public final class ProductAmount implements Comparable<ProductAmount> {
	public static final ProductAmount ZERO = new ProductAmount(0, null);
	private final long small;
	private final BigInteger large;

	private ProductAmount(long small, BigInteger large) { this.small = small; this.large = large; }
	public static ProductAmount of(long value) {
		if (value < 0) throw new IllegalArgumentException("Negative product amount");
		return value == 0 ? ZERO : new ProductAmount(value, null);
	}
	public static ProductAmount of(BigInteger value) {
		Objects.requireNonNull(value);
		if (value.signum() < 0) throw new IllegalArgumentException("Negative product amount");
		return value.bitLength() <= 63 ? of(value.longValueExact()) : new ProductAmount(0, value);
	}
	public boolean isZero() { return large == null && small == 0; }
	public boolean fitsLong() { return large == null; }
	public long longSaturated() { return large == null ? small : Long.MAX_VALUE; }
	public BigInteger exact() { return large == null ? BigInteger.valueOf(small) : large; }
	public ProductAmount add(ProductAmount other) {
		if (other.isZero()) return this;
		if (isZero()) return other;
		if (large == null && other.large == null && small <= Long.MAX_VALUE - other.small) return of(small + other.small);
		return of(exact().add(other.exact()));
	}
	public ProductAmount subtract(ProductAmount other) {
		if (compareTo(other) < 0) throw new IllegalArgumentException("Insufficient product amount");
		if (other.isZero()) return this;
		return large == null ? of(small - other.small) : of(large.subtract(other.exact()));
	}
	public ProductAmount min(ProductAmount other) { return compareTo(other) <= 0 ? this : other; }
	@Override
	public int compareTo(ProductAmount other) {
		if (large == null) return other.large == null ? Long.compare(small, other.small) : -1;
		return other.large == null ? 1 : large.compareTo(other.large);
	}
	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof ProductAmount amount && small == amount.small && Objects.equals(large, amount.large);
	}
	@Override
	public int hashCode() { return large == null ? Long.hashCode(small) : large.hashCode(); }
	@Override
	public String toString() { return large == null ? Long.toString(small) : large.toString(); }
}
