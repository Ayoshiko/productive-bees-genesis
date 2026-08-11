package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import net.minecraft.resources.ResourceLocation;

/**
 * AE2 输入过滤器条目的纯静态解析与匹配工具
 * <br/>
 * 从 {@link Ae2InputFilter} 拆分而来，职责（SRP）：条目字符串的解析、格式化
 * 与模糊/直连匹配判定，不持有过滤器状态。
 */
final class Ae2FilterEntrySupport {

	private Ae2FilterEntrySupport() {
	}

	/** 解析条目字符串为 EntryInfo */
	static Ae2InputFilter.EntryInfo parseEntry(String entry) {
		if (Ae2InputFilter.isDirectFingerprint(entry)) {
			return new Ae2InputFilter.EntryInfo(null, false,
					entry.substring(Ae2InputFilter.DIRECT_ENTRY_PREFIX.length()));
		}
		if (entry.endsWith("#block")) {
			String beeTypeStr = entry.substring(0, entry.length() - 6);
			ResourceLocation beeType = ResourceLocation.tryParse(beeTypeStr);
			return new Ae2InputFilter.EntryInfo(beeType, true);
		} else {
			ResourceLocation beeType = ResourceLocation.tryParse(entry);
			return new Ae2InputFilter.EntryInfo(beeType, false);
		}
	}

	static String formatEntry(ResourceLocation beeType, boolean isBlock) {
		return isBlock ? beeType.toString() + "#block" : beeType.toString();
	}

	/** Matches fuzzy entries while accepting the old #block spelling in fuzzy mode. */
	static boolean matchesFuzzyEntry(String configured, ResourceLocation candidateBeeType,
			boolean candidateBlock, boolean precise) {
		if (configured == null || configured.isBlank() || candidateBeeType == null
				|| Ae2InputFilter.isDirectFingerprint(configured)) {
			return false;
		}
		String typeText = configured.endsWith("#block")
				? configured.substring(0, configured.length() - 6) : configured;
		if (ResourceLocation.tryParse(typeText) == null) {
			return false;
		}
		return Ae2FilterEntryMatcher.matches(configured, candidateBeeType.toString(), candidateBlock, precise);
	}

	static boolean matchesDirectEntry(String entry, AEItemKey configured,
			AEItemKey candidate, ResourceLocation candidateBeeType, boolean candidateBlock,
			boolean ignoreNbt, boolean precise) {
		String fingerprint = entry.substring(Ae2InputFilter.DIRECT_ENTRY_PREFIX.length());
		if (configured == null) {
			return Ae2ItemFingerprint.matchesLegacy(candidate, fingerprint);
		}
		ResourceLocation configuredBeeType = CombFuzzyMatcher.getBeeType(configured);
		return matchesLogicalComb(configured, candidate, configuredBeeType, candidateBeeType,
				ignoreNbt, precise, candidateBlock);
	}

	private static boolean matchesLogicalComb(AEItemKey configured, AEItemKey candidate,
			ResourceLocation configuredBeeType, ResourceLocation candidateBeeType,
			boolean ignoreNbt, boolean precise, boolean candidateBlock) {
		return Ae2FilterEntryMatcher.matchesDirect(configured.equals(candidate),
				configuredBeeType == null ? null : configuredBeeType.toString(),
				CombFuzzyMatcher.isCombBlock(configured),
				candidateBeeType == null ? null : candidateBeeType.toString(),
				candidateBlock, configured.getItem() == candidate.getItem(), ignoreNbt, precise);
	}
}
