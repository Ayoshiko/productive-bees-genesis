package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.level.Level;

/**
 * Classifies AE2 item keys that may enter a centrifuge through network pulling.
 * <p>
 * Productive Bees comb semantics remain owned by {@link CombFuzzyMatcher}; ordinary
 * Mekanism SMELTING inputs are admitted only while the centrifuge compatibility mode
 * is enabled. Comb classification deliberately runs first so broad SMELTING tag recipes
 * such as {@code c:honeycombs} cannot steal Productive Bees inputs.
 */
final class Ae2InputCandidatePolicy {

	enum CandidateKind {
		REJECTED,
		COMB,
		SMELTING;

		boolean isAllowed() {
			return this != REJECTED;
		}

		boolean isSmelting() {
			return this == SMELTING;
		}
	}

	private Ae2InputCandidatePolicy() {
	}

	/** Classifies a key using the current per-pull switch snapshot and per-host recipe cache. */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache) {
		if (key == null) return CandidateKind.REJECTED;
		if (CombFuzzyMatcher.isCombItem(key)) return CandidateKind.COMB;
		if (!smeltingEnabled || level == null || smeltingCache == null) return CandidateKind.REJECTED;
		return smeltingCache.contains(level, key) ? CandidateKind.SMELTING : CandidateKind.REJECTED;
	}
}
