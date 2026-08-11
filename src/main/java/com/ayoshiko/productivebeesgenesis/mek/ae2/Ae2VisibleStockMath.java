package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** Pure amount math shared by the AE2 cached and simulated stock paths. */
final class Ae2VisibleStockMath {

	private Ae2VisibleStockMath() {
	}

	static long merge(long reported, long simulated, long cap) {
		if (cap <= 0L) return 0L;
		long visible = Math.max(Math.max(0L, reported), Math.max(0L, simulated));
		return Math.min(visible, cap);
	}
}
