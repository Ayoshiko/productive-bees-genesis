package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoundRobinSlotTraversalTest {

	@Test
	void successiveStartsVisitBothOutputPages() {
		int[] visits = new int[8];
		int cursor = 0;
		for (int round = 0; round < visits.length; round++) {
			int start = RoundRobinSlotTraversal.normalize(cursor, visits.length);
			visits[start]++;
			cursor = RoundRobinSlotTraversal.advance(start, visits.length);
		}
		assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1}, visits);
	}

	@Test
	void wrappedScanRetainsCircularPhysicalOrder() {
		int[] order = new int[6];
		for (int offset = 0; offset < order.length; offset++) {
			order[offset] = RoundRobinSlotTraversal.index(4, offset, order.length);
		}
		assertArrayEquals(new int[]{4, 5, 0, 1, 2, 3}, order);
		assertEquals(5, RoundRobinSlotTraversal.normalize(-1, 6));
	}
}
