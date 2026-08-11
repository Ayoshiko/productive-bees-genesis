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
	void acceptsAe2EqualsPrefix() {
		assertEquals(OptionalLong.of(64L), Ae2AmountExpressionParser.parseLong("= 2^6", 0, 8_192));
	}
}
