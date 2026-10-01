package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeeCycleRandomTest {
	@Test void persistedStreamMatchesJdkSplitMixAndIgnoresPartitions() {
		for (long seed : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 731901}) {
			var expected = new SplittableRandom(seed);
			var state = new BeeCycleRandom(seed, 0);
			for (int i = 0; i < 2048; i++) assertEquals(expected.nextDouble(), state.draw(i));
			for (int start : new int[]{1, 17, 63, 64, 511})
				for (int i = 0; i < 128; i++) assertEquals(state.draw(start + i), state.advance(start).draw(i));
			assertEquals(0, state.cursor());
		}
	}
	@Test void identityAndCounterAreStableAndOverflowIsExplicit() {
		var a = BeeCycleRandom.initial(new UUID(1, 2));
		assertEquals(a, BeeCycleRandom.initial(new UUID(1, 2)));
		assertNotEquals(a, BeeCycleRandom.initial(new UUID(2, 1)));
		assertThrows(IllegalArgumentException.class, () -> new BeeCycleRandom(0, -1));
		assertThrows(IllegalArgumentException.class, () -> a.draw(-1));
		assertThrows(IllegalArgumentException.class, () -> a.advance(-1));
		var end = new BeeCycleRandom(1, Long.MAX_VALUE);
		assertThrows(ArithmeticException.class, () -> end.advance(1));
		assertThrows(ArithmeticException.class, () -> end.draw(1));
		assertSame(a, a.advance(0));
		assertEquals(a.seed(), a.advance(10).seed());
	}
}
