package com.ayoshiko.productivebeesgenesis.util;

/** Small, allocation-free helpers for fair circular slot scans. */
public final class RoundRobinSlotTraversal {

	private RoundRobinSlotTraversal() {
	}

	public static int normalize(int cursor, int size) {
		return size <= 0 ? 0 : Math.floorMod(cursor, size);
	}

	public static int index(int start, int offset, int size) {
		if (size <= 0) return 0;
		return (int) Math.floorMod((long) start + offset, size);
	}

	public static int advance(int current, int size) {
		return size <= 1 ? 0 : index(current, 1, size);
	}
}
