package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** Pure threshold policy kept separate so it can be tested without loading AE2 classes. */
final class Ae2OutputMergePolicy {

	/** A single slot is cheaper direct; two or more slots benefit from key coalescing. */
	private static final int BATCH_MERGE_THRESHOLD = 1;

	private Ae2OutputMergePolicy() {
	}

	static boolean shouldMergeEntries(int entryCount) {
		return entryCount > BATCH_MERGE_THRESHOLD;
	}
}
