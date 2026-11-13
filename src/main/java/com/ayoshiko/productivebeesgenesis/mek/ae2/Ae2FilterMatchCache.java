package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.Arrays;
import java.util.List;

/** 单次收集使用固定配置；仅缓存配置规则，库存数量和资源资格仍在使用时查询。 */
final class Ae2FilterMatchCache {

	private static final int MAX_CANDIDATES_PER_MODE = 128;
	private final String[] slots;
	private final AEItemKey[] keys;
	private final long[] amounts;
	private final long[] reserves;
	private final boolean[] unlimited;
	private final boolean[] networkStock;
	final Ae2InputFilter.FilterMode mode;
	final boolean precise;
	final boolean globalNetworkStock;
	final boolean stockPolicyActive;
	final List<Ae2InputFilter.DirectEntry> directEntries;
	private final boolean unlimitedAll;
	private final long globalReserve;
	private final long globalCap;
	private final boolean tagFilterActive;
	private final Ae2InputFilterQuerySupport.FuzzyEntry[] fuzzyEntries;
	private final Ae2DirectKeyIndex<AEItemKey> directIndex;
	final boolean hasConfiguredEntries;
	final boolean hasFuzzyEntries;
	private final Ae2FilterPullPolicy.PullRule unconfiguredRule;
	private final Object2ObjectLinkedOpenHashMap<AEItemKey, Ae2FilterPullPolicy.PullRule> exact =
			new Object2ObjectLinkedOpenHashMap<>();
	private final Object2ObjectLinkedOpenHashMap<AEItemKey, Ae2FilterPullPolicy.PullRule> ignoringNbt =
			new Object2ObjectLinkedOpenHashMap<>();

	Ae2FilterMatchCache(Ae2InputFilter.FilterMode mode, String[] slots, AEItemKey[] keys, boolean precise,
			long[] amounts, long[] reserves, boolean[] unlimited, boolean[] networkStock,
			boolean unlimitedAll, boolean globalNetworkStock, long globalReserve, long globalCap,
			boolean tagFilterActive, Ae2InputFilterQuerySupport.FuzzyEntry[] fuzzyEntries,
			Ae2DirectKeyIndex<AEItemKey> directIndex, List<Ae2InputFilter.DirectEntry> directEntries) {
		this.mode = mode;
		this.slots = slots;
		this.keys = keys;
		this.precise = precise;
		this.amounts = amounts;
		this.reserves = reserves;
		this.unlimited = unlimited;
		this.networkStock = networkStock;
		this.unlimitedAll = unlimitedAll;
		this.globalNetworkStock = globalNetworkStock;
		this.globalReserve = globalReserve;
		this.globalCap = globalCap;
		this.tagFilterActive = tagFilterActive;
		this.fuzzyEntries = fuzzyEntries;
		this.directIndex = directIndex;
		this.directEntries = directEntries;
		stockPolicyActive = globalNetworkStock
				|| Ae2InputFilterQuerySupport.hasNetworkStockEntries(slots, networkStock);
		boolean configured = false;
		boolean fuzzy = false;
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i])) {
				configured = true;
			} else if (fuzzyEntries[i] != null) {
				configured = true;
				fuzzy = true;
			}
		}
		hasConfiguredEntries = configured;
		hasFuzzyEntries = fuzzy;
		unconfiguredRule = configured ? null : compile(null);
	}

	boolean valid(boolean tagFilterActive, long globalCap) {
		return this.tagFilterActive == tagFilterActive && this.globalCap == globalCap;
	}

	long pullLimit(AEItemKey candidate, long visibleStock, boolean ignoreNbt, Ae2PullDecision decision) {
		if (candidate == null) return Ae2FilterPullPolicy.PullRule.REJECTED.limit(visibleStock, globalCap, decision);
		if (unconfiguredRule != null) return unconfiguredRule.limit(visibleStock, globalCap, decision);
		var cache = ignoreNbt ? ignoringNbt : exact;
		Ae2FilterPullPolicy.PullRule rule = cache.getAndMoveToLast(candidate);
		if (rule == null) {
			int[] hitSlots;
			// 完整精确索引保留同键多槽额度；模糊和忽略组件模式继续按既有规则匹配。
			if (!ignoreNbt && directIndex.isComplete()
					&& (precise && !hasFuzzyEntries || CombFuzzyMatcher.getBeeType(candidate) == null)) {
				hitSlots = directIndex.slotsFor(candidate);
			} else {
				hitSlots = matchingSlots(candidate, ignoreNbt);
			}
			rule = compile(hitSlots);
			if (cache.size() >= MAX_CANDIDATES_PER_MODE) cache.removeFirst();
			cache.put(candidate, rule);
		}
		return rule.limit(visibleStock, globalCap, decision);
	}

	private Ae2FilterPullPolicy.PullRule compile(int[] hitSlots) {
		return Ae2InputFilterQuerySupport.compileMatchedPullRule(mode, slots, amounts, reserves,
				unlimited, networkStock, unlimitedAll, tagFilterActive, hasConfiguredEntries,
				globalNetworkStock, globalReserve, hitSlots);
	}

	private int[] matchingSlots(AEItemKey candidate, boolean ignoreNbt) {
		var beeType = CombFuzzyMatcher.getBeeType(candidate);
		boolean block = CombFuzzyMatcher.isCombBlock(candidate);
		int[] hits = new int[slots.length];
		int count = 0;
		for (int i = 0; i < slots.length; i++) {
			boolean matches;
			if (Ae2InputFilter.isDirectFingerprint(slots[i])) {
				AEItemKey configured = keys != null && i < keys.length ? keys[i] : null;
				matches = Ae2FilterEntrySupport.matchesDirectEntry(slots[i], configured, candidate,
						beeType, block, ignoreNbt, precise);
			} else {
				matches = Ae2InputFilterQuerySupport.matchesFuzzyEntry(fuzzyEntries[i], beeType, block, precise);
			}
			if (matches) hits[count++] = i;
		}
		return Arrays.copyOf(hits, count);
	}
}
