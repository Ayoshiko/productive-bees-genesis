package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class Ae2AmountExpressionParserTest {

	@Test
	void evaluatesAe2Expressions() {
		assertEquals(OptionalLong.of(12L), Ae2AmountExpressionParser.parseLong("3*4", 0, 8_192));
		assertEquals(OptionalLong.of(14L), Ae2AmountExpressionParser.parseLong("(2+5)*2", 0, 8_192));
		assertEquals(OptionalLong.of(16L), Ae2AmountExpressionParser.parseLong("2^4", 0, 8_192));
	}

	@Test
	void rejectsInvalidOrOutOfRangeValues() {
		assertTrue(Ae2AmountExpressionParser.parseLong("3/0", 0, 8_192).isEmpty());
		assertTrue(Ae2AmountExpressionParser.parseLong("8193", 0, 8_192).isEmpty());
		assertTrue(Ae2AmountExpressionParser.parseLong("-1", 0, 8_192).isEmpty());
		assertTrue(Ae2AmountExpressionParser.parseLong("3.5", 0, 8_192).isEmpty());
	}

	@Test
	void preservesFullLongIntegerPrecision() {
		assertEquals(OptionalLong.of(Long.MAX_VALUE),
				Ae2AmountExpressionParser.parseLong("9223372036854775807", 0, Long.MAX_VALUE));
		assertEquals(OptionalLong.of(Long.MAX_VALUE - 1),
				Ae2AmountExpressionParser.parseLong("9223372036854775807-1", 0, Long.MAX_VALUE));
		assertTrue(Ae2AmountExpressionParser.parseLong("9223372036854775807+1", 0, Long.MAX_VALUE).isEmpty());
	}

	@Test
	void rejectsPowerExpansionBeforeAllocatingLargeIntermediateValues() {
		assertTrue(Ae2AmountExpressionParser.parse("0.999^30^30^30^30^30^30").isEmpty());
		assertTrue(Ae2AmountExpressionParser.parse("0.1^30^30^30^30^30^30").isEmpty());
		assertEquals(OptionalLong.of(1),
				Ae2AmountExpressionParser.parseLong("(0.1^30)/(0.1^30)", 0, Long.MAX_VALUE));
	}

	@Test
	void acceptsAe2EqualsPrefix() {
		assertEquals(OptionalLong.of(64L), Ae2AmountExpressionParser.parseLong("= 2^6", 0, 8_192));
	}
}
