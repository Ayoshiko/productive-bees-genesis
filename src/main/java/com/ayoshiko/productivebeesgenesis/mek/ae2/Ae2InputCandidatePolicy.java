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
 * <p>
 * SMELTING 候选额外经过 {@link SmeltingTagGate}（标签表达式过滤）。蜜脾候选不走该门，
 * 因为蜜脾种类由 {@link Ae2InputFilter} 的槽位白/黑名单负责，两套配置互不干扰。
 */
final class Ae2InputCandidatePolicy {

	/**
	 * SMELTING 候选的标签准入抽象（DIP + ISP）。
	 * <br/>
	 * 只暴露一个判定方法，使分类逻辑不依赖具体的标签缓存实现，便于单测替换。
	 */
	@FunctionalInterface
	interface SmeltingTagGate {

		/** 全部放行的门（未配置标签过滤时使用，零开销）。 */
		SmeltingTagGate ALLOW_ALL = key -> true;

		boolean allows(AEItemKey key);
	}

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
		return classify(level, key, smeltingEnabled, smeltingCache, SmeltingTagGate.ALLOW_ALL);
	}

	/**
	 * 带标签门的分类。判定顺序刻意保持不变：先蜜脾、再 SMELTING、最后标签门，
	 * 使标签表达式只能收窄 smelt 输入范围，不会影响蜜脾拉取。
	 */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache, SmeltingTagGate tagGate) {
		if (key == null) return CandidateKind.REJECTED;
		if (CombFuzzyMatcher.isCombItem(key)) return CandidateKind.COMB;
		if (!smeltingEnabled || level == null || smeltingCache == null) return CandidateKind.REJECTED;
		if (!smeltingCache.contains(level, key)) return CandidateKind.REJECTED;
		return tagGate == null || tagGate.allows(key) ? CandidateKind.SMELTING : CandidateKind.REJECTED;
	}
}
