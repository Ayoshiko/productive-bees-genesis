package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** Pure pull-limit policy applied after blacklist/whitelist admission. */
final class Ae2FilterPullPolicy {

	static final long PULL_DISALLOWED = Long.MIN_VALUE;

	private Ae2FilterPullPolicy() {
	}

	static long effectiveLimit(boolean admitted, boolean directFound, long requested, long visibleStock,
			boolean liveStock, long reserve, boolean perEntryUnlimited, boolean unlimitedAll,
			boolean globalNetworkStock, long globalReserve, long globalCap) {
		if (!admitted) return PULL_DISALLOWED;
		boolean effectiveUnlimited = unlimitedAll || perEntryUnlimited;
		if (!directFound) {
			if (!globalNetworkStock) return -1L;
			return Math.max(0L, visibleStock - Math.max(0L, globalReserve));
		}
		// An exact entry's stock floor overrides the global default. Otherwise the
		// global floor still applies, including while unlimited-all is enabled.
		if (!liveStock && globalNetworkStock) {
			liveStock = true;
			reserve = Math.max(0L, globalReserve);
		}
		return Ae2PullAmountMath.effectiveLimit(requested, visibleStock, liveStock, effectiveUnlimited,
				globalCap, reserve);
	}
}
