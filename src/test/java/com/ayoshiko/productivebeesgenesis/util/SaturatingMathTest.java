package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SaturatingMathTest {

	@Test
	void multiplicationAndAdditionSaturateInsteadOfWrapping() {
		assertEquals(Long.MAX_VALUE, SaturatingMath.saturatingMultiply(Long.MAX_VALUE, 2L));
		assertEquals(Long.MAX_VALUE, SaturatingMath.saturatingMultiply(Long.MAX_VALUE, 2L, 256L));
		assertEquals(Long.MAX_VALUE, SaturatingMath.saturatingAdd(Long.MAX_VALUE, 1L));
		assertEquals(0L, SaturatingMath.saturatingMultiply(-1L, 10L));
	}

	@Test
	void floatingPointConversionsRejectNanAndSaturateInfinity() {
		assertEquals(0L, SaturatingMath.saturatingCeilToLong(Double.NaN));
		assertEquals(Long.MAX_VALUE, SaturatingMath.saturatingCeilToLong(Double.POSITIVE_INFINITY));
		assertEquals(Integer.MAX_VALUE, SaturatingMath.saturatingRoundToInt(Double.POSITIVE_INFINITY));
		assertEquals(0, SaturatingMath.saturatingRoundToInt(-100.0D));
	}

	@Test
	void externalResultsAreClampedToTheirRequest() {
		assertEquals(0L, SaturatingMath.clampToRequest(-1L, 100L));
		assertEquals(100L, SaturatingMath.clampToRequest(Long.MAX_VALUE, 100L));
		assertEquals(Integer.MAX_VALUE, SaturatingMath.saturatingPowerOfTwo(31));
	}
}
