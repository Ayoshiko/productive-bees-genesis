package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Ae2VisibleStockMathTest {

	@Test
	void usesTheLargerOfCachedAndSimulatedStock() {
		assertEquals(900L, Ae2VisibleStockMath.merge(900L, 640L, Long.MAX_VALUE));
		assertEquals(900L, Ae2VisibleStockMath.merge(640L, 900L, Long.MAX_VALUE));
	}

	@Test
	void appliesCapacityAfterMerging() {
		assertEquals(256L, Ae2VisibleStockMath.merge(1_024L, 2_048L, 256L));
	}

	@Test
	void rejectsNegativeAmountsAndNonPositiveCapacity() {
		assertEquals(0L, Ae2VisibleStockMath.merge(-10L, -20L, Long.MAX_VALUE));
		assertEquals(0L, Ae2VisibleStockMath.merge(10L, 20L, 0L));
	}
}
