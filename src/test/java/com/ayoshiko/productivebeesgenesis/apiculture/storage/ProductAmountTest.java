package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductAmountTest {
	@Test
	void promotionDemotionAndProjectionPreserveExactBalance() {
		var max = ProductAmount.of(Long.MAX_VALUE);
		var over = max.add(ProductAmount.of(1));
		assertFalse(over.fitsLong());
		assertEquals(Long.MAX_VALUE, over.longSaturated());
		assertEquals(BigInteger.ONE.shiftLeft(63), over.exact());
		assertEquals(max, over.subtract(ProductAmount.of(1)));
		assertTrue(over.subtract(ProductAmount.of(1)).fitsLong());
		var huge = ProductAmount.of(BigInteger.ONE.shiftLeft(512));
		assertEquals(huge, huge.add(over).subtract(over));
		assertSame(ProductAmount.ZERO, huge.subtract(huge));
	}
	@Test
	void batchMultiplicationAndDivisionRetainLargeRemainders() {
		var unit = ProductAmount.of(Long.MAX_VALUE);
		var total = unit.multiply(300).add(ProductAmount.of(19));
		assertEquals(ProductAmount.of(300), total.divide(unit));
		assertEquals(ProductAmount.of(19), total.subtract(unit.multiply(300)));
		assertEquals(ProductAmount.ZERO, total.multiply(0));
		assertThrows(IllegalArgumentException.class, () -> unit.multiply(-1));
		assertThrows(IllegalArgumentException.class, () -> unit.divide(ProductAmount.ZERO));
	}
	@Test
	void signedInputsAndUnderflowAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> ProductAmount.of(-1));
		assertThrows(IllegalArgumentException.class, () -> ProductAmount.of(Long.MIN_VALUE));
		assertThrows(IllegalArgumentException.class, () -> ProductAmount.of(BigInteger.ONE.negate()));
		assertThrows(IllegalArgumentException.class, () -> ProductAmount.of(1).subtract(ProductAmount.of(2)));
		assertEquals(ProductAmount.of(42), ProductAmount.of(BigInteger.valueOf(42)));
	}
}
