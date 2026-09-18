package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.math.BigInteger;
import java.util.Objects;

/** 展示与能力汇总使用精确分数，不能用浮点吞吐替代实际进程。 */
public record ExactRate(BigInteger numerator, BigInteger denominator) {
	public static final ExactRate ZERO = of(0, 1);

	public ExactRate {
		Objects.requireNonNull(numerator);
		Objects.requireNonNull(denominator);
		if (numerator.signum() < 0 || denominator.signum() <= 0) throw new IllegalArgumentException("Invalid rate");
		BigInteger gcd = numerator.gcd(denominator);
		numerator = numerator.divide(gcd);
		denominator = denominator.divide(gcd);
	}

	public static ExactRate of(long numerator, long denominator) {
		return new ExactRate(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}

	public ExactRate add(ExactRate other) {
		return new ExactRate(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
				denominator.multiply(other.denominator));
	}

	public ExactRate multiply(long count) {
		return new ExactRate(numerator.multiply(BigInteger.valueOf(count)), denominator);
	}
}
