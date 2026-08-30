package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** Pure pull-limit policy applied after blacklist/whitelist admission. */
final class Ae2FilterPullPolicy {

	static final long PULL_DISALLOWED = Long.MIN_VALUE;

	private Ae2FilterPullPolicy() {
	}

	/** 统一黑白名单准入真值表，供线性与精确索引路径共用。 */
	static boolean isAdmitted(Ae2InputFilter.FilterMode mode, boolean filterMatched) {
		return switch (mode) {
			case BLACKLIST -> !filterMatched;
			case WHITELIST -> filterMatched;
			case DISABLED -> true;
		};
	}

	/** Clamps an extract request to the amount currently above its reserve floor. */
	static int reserveSafeRequest(int requested, long liveExtractable, long reserveFloor) {
		if (requested <= 0 || liveExtractable < 0L || reserveFloor < 0L) return 0;
		long aboveFloor = Math.max(0L, liveExtractable - reserveFloor);
		return (int) Math.min(requested, aboveFloor);
	}

	/** Resolves the entry-level stock floor first, then the global fallback. */
	static long effectiveReserveFloor(boolean entryStockMatched, long entryReserve,
			boolean globalNetworkStock, long globalReserve) {
		if (entryStockMatched) return Math.max(0L, entryReserve);
		return globalNetworkStock ? Math.max(0L, globalReserve) : -1L;
	}

	static long effectiveLimit(boolean admitted, boolean directFound, long requested, long visibleStock,
			boolean liveStock, long reserve, boolean perEntryUnlimited, boolean unlimitedAll,
			boolean globalNetworkStock, long globalReserve, long globalCap) {
		if (!admitted) return PULL_DISALLOWED;
		boolean effectiveUnlimited = unlimitedAll || perEntryUnlimited;
		long reserveFloor = effectiveReserveFloor(liveStock, reserve, globalNetworkStock, globalReserve);
		if (!directFound) return reserveFloor < 0L ? -1L : Math.max(0L, visibleStock - reserveFloor);
		// An exact entry's stock floor overrides the global default. Otherwise the
		// global floor still applies, including while unlimited-all is enabled.
		if (reserveFloor >= 0L) {
			liveStock = true;
			reserve = reserveFloor;
		}
		return Ae2PullAmountMath.effectiveLimit(requested, visibleStock, liveStock, effectiveUnlimited,
				globalCap, reserve);
	}
}
