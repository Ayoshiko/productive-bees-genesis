package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** 在黑白名单准入后计算 AE2 输入上限的纯策略。 */
final class Ae2FilterPullPolicy {

	static final long PULL_DISALLOWED = Long.MIN_VALUE;

	private Ae2FilterPullPolicy() {
	}

	/** 配置决定的规则只编译一次；库存数量仍由本次查询提供。 */
	record PullRule(boolean admitted, boolean marked, boolean directFound, long requested,
			boolean unlimited, long reserveFloor) {
		static final PullRule REJECTED = new PullRule(false, false, false, 0L, false, -1L);

		long limit(long visibleStock, long globalCap, Ae2PullDecision decision) {
			if (decision != null) decision.set(admitted, marked, unlimited, reserveFloor);
			if (!admitted) return PULL_DISALLOWED;
			if (!directFound) return reserveFloor < 0L ? -1L : Math.max(0L, visibleStock - reserveFloor);
			return Ae2PullAmountMath.effectiveLimit(requested, visibleStock, reserveFloor >= 0L,
					unlimited, globalCap, reserveFloor);
		}
	}

	/** 统一黑白名单准入真值表，供线性与精确索引路径共用。 */
	static boolean isAdmitted(Ae2InputFilter.FilterMode mode, boolean filterMatched) {
		return switch (mode) {
			case BLACKLIST -> !filterMatched;
			case WHITELIST -> filterMatched;
			case DISABLED -> true;
		};
	}

	/**
	 * 在独立标签过滤放行候选后解析外层标记条目模式。
	 * <p>
	 * 未配置任何标记时，标签表达式独立决定候选范围；存在标记时，外层黑白名单必须
	 * 对标签结果再过滤一次。这样白名单只拉取「标签命中且已标记」的物品，黑名单仍排除
	 * 标签命中且已标记的物品。
	 */
	static boolean isAdmitted(Ae2InputFilter.FilterMode mode, boolean filterMatched,
			boolean tagFilterActive, boolean hasConfiguredEntries) {
		if (tagFilterActive && !hasConfiguredEntries) return true;
		return isAdmitted(mode, filterMatched);
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
