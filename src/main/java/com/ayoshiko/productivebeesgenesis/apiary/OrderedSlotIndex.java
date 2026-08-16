package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * Reusable primitive index that preserves physical slot order when entries are consumed.
 */
final class OrderedSlotIndex {

	private int[] slots = new int[0];
	private int size;

	void reset(int capacity) {
		if (slots.length != capacity) slots = new int[capacity];
		size = 0;
	}

	void add(int slot) {
		slots[size++] = slot;
	}

	int size() {
		return size;
	}

	int get(int position) {
		return slots[position];
	}

	void consume(int position) {
		slots[position] = -1;
	}
}
