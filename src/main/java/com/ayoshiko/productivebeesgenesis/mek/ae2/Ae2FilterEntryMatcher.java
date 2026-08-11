package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** Pure string-level matcher for persisted fuzzy filter entries. */
final class Ae2FilterEntryMatcher {

	private Ae2FilterEntryMatcher() {
	}

	static boolean matches(String configured, String candidateBeeType,
			boolean candidateBlock, boolean precise) {
		if (configured == null || configured.isBlank() || candidateBeeType == null
				|| candidateBeeType.isBlank() || configured.startsWith("@")) return false;
		boolean configuredBlock = configured.endsWith("#block");
		String configuredType = configuredBlock
				? configured.substring(0, configured.length() - 6) : configured;
		return configuredType.equals(candidateBeeType)
				&& (!precise || configuredBlock == candidateBlock);
	}

	/**
	 * Shared direct-entry semantics for whitelist matching and configured pull limits.
	 * Exact AE keys always match. Fuzzy matching is only enabled by NBT-ignore and
	 * still preserves the logical bee type plus the optional comb/block distinction.
	 */
	static boolean matchesDirect(boolean exactKeyMatch,
			String configuredBeeType, boolean configuredBlock,
			String candidateBeeType, boolean candidateBlock,
			boolean sameBaseItem, boolean ignoreNbt, boolean precise) {
		if (exactKeyMatch) return true;
		if (!precise) {
			// Precise mode off keeps the historical bee-type group semantics:
			// a marked comb and its comb block always share one pull quota,
			// regardless of the NBT-ignore switch.
			if (configuredBeeType != null && candidateBeeType != null) {
				return configuredBeeType.equals(candidateBeeType);
			}
			// Items without bee_type may only fuzzy-match another component
			// variant of the same base item while NBT-ignore is active.
			// A typed comb never matches an untyped item.
			return ignoreNbt && configuredBeeType == null && candidateBeeType == null && sameBaseItem;
		}
		// Precise mode mirrors AE2Utility's NBT Tear Card: an item may only
		// match another key with the same Item id. Components/NBT are ignored,
		// but a comb and a comb block remain different items. Without NBT-ignore
		// only the exact AE key (handled above) may match.
		return ignoreNbt && sameBaseItem;
	}
}
