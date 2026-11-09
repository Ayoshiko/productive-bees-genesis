package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerNumberRangeTest {
	@Test
	void boundsRejectExtremeExponentsWithoutExpandingIntegers() {
		var range = DesignerNumberRange.of(1, 536870911, true);
		assertFalse(range.valid("1e2147483647"));
		assertFalse(range.valid("1e-2147483647"));
		assertFalse(range.valid("1.5"));
		assertTrue(range.valid("100.0"));
		assertTrue(range.valid("1e2"));
		assertFalse(range.valid("0"));
	}
}
