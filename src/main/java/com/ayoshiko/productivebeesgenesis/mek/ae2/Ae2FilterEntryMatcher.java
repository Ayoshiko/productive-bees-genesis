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
	 * <p>
	 * 入参只用「是否存在 bee_type」与「两者是否相等」三个布尔量，不接收类型字符串：
	 * 本方法处于「候选键 × 过滤槽位」乘积级的热路径上，把 ResourceLocation 转成 String
	 * 会为每次比较分配一个新串（{@code ResourceLocation.toString} 不缓存）。
	 *
	 * @param configuredBlock 保留参数，当前语义不区分配置端的方块形态
	 */
	static boolean matchesDirect(boolean exactKeyMatch,
			boolean hasConfiguredBeeType, boolean hasCandidateBeeType, boolean beeTypesEqual,
			boolean configuredBlock, boolean candidateBlock,
			boolean sameBaseItem, boolean ignoreNbt, boolean precise) {
		if (exactKeyMatch) return true;
		if (!precise) {
			// Precise mode off keeps the historical bee-type group semantics:
			// a marked comb and its comb block always share one pull quota,
			// regardless of the NBT-ignore switch.
			if (hasConfiguredBeeType && hasCandidateBeeType) {
				return beeTypesEqual;
			}
			// Items without bee_type may only fuzzy-match another component
			// variant of the same base item while NBT-ignore is active.
			// A typed comb never matches an untyped item.
			return ignoreNbt && !hasConfiguredBeeType && !hasCandidateBeeType && sameBaseItem;
		}
		// Precise mode mirrors AE2Utility's NBT Tear Card: an item may only
		// match another key with the same Item id. Components/NBT are ignored,
		// but a comb and a comb block remain different items. Without NBT-ignore
		// only the exact AE key (handled above) may match.
		return ignoreNbt && sameBaseItem;
	}

	/** 字符串入参重载，供单元测试直接表达蜜蜂类型语义。 */
	static boolean matchesDirect(boolean exactKeyMatch,
			String configuredBeeType, boolean configuredBlock,
			String candidateBeeType, boolean candidateBlock,
			boolean sameBaseItem, boolean ignoreNbt, boolean precise) {
		return matchesDirect(exactKeyMatch, configuredBeeType != null, candidateBeeType != null,
				configuredBeeType != null && configuredBeeType.equals(candidateBeeType),
				configuredBlock, candidateBlock, sameBaseItem, ignoreNbt, precise);
	}
}
