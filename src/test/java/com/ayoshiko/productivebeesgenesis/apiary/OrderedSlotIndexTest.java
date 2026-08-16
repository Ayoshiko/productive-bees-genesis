package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderedSlotIndexTest {

	@Test
	void firstFullPageContinuesAtFirstSlotOfSecondPage() {
		OrderedSlotIndex index = new OrderedSlotIndex();
		index.reset(4);
		index.add(2);
		index.add(3);

		assertEquals(2, index.get(0));
		assertEquals(3, index.get(1));
	}

	@Test
	void consumingAnEntryDoesNotReverseTheRemainingPhysicalOrder() {
		OrderedSlotIndex index = new OrderedSlotIndex();
		index.reset(6);
		for (int slot = 0; slot < 6; slot++) index.add(slot);

		index.consume(0);

		assertEquals(-1, index.get(0));
		assertEquals(1, index.get(1));
		assertEquals(5, index.get(5));
	}
}
