package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatermarkStateTest {
	@Test
	void hysteresisKeepsStateBetweenDistinctBoundsEvenBeyondLong() {
		var lower = ProductAmount.of(BigInteger.ONE.shiftLeft(128));
		var upper = lower.multiply(2);
		var state = new WatermarkState(false);
		assertFalse(state.update(lower, lower, upper).replenishing());
		state = state.update(lower.subtract(ProductAmount.of(1)), lower, upper);
		assertTrue(state.replenishing());
		assertTrue(state.update(upper.subtract(ProductAmount.of(1)), lower, upper).replenishing());
		state = state.update(upper, lower, upper);
		assertFalse(state.replenishing());
		assertFalse(state.update(lower.add(ProductAmount.of(1)), lower, upper).replenishing());
		assertThrows(IllegalArgumentException.class, () -> new WatermarkState(false).update(lower, lower, lower));
	}
}
