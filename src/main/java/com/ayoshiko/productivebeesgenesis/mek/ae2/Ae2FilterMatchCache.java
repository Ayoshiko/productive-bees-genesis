package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.Arrays;

/** 服务器拉取线程的匹配快照；仅保留槽位命中，不缓存数量或网络库存。 */
final class Ae2FilterMatchCache {

	private static final int MAX_CANDIDATES_PER_MODE = 128;
	private final String[] slots;
	private final AEItemKey[] keys;
	private final boolean precise;
	private final Ae2InputFilterQuerySupport.FuzzyEntry[] fuzzyEntries;
	final boolean hasConfiguredEntries;
	private final Object2ObjectLinkedOpenHashMap<AEItemKey, int[]> exact = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2ObjectLinkedOpenHashMap<AEItemKey, int[]> ignoringNbt = new Object2ObjectLinkedOpenHashMap<>();

	Ae2FilterMatchCache(String[] slots, AEItemKey[] keys, boolean precise,
			Ae2InputFilterQuerySupport.FuzzyEntry[] fuzzyEntries) {
		this.slots = slots;
		this.keys = keys;
		this.precise = precise;
		this.fuzzyEntries = fuzzyEntries;
		boolean configured = false;
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) || fuzzyEntries[i] != null) {
				configured = true;
				break;
			}
		}
		hasConfiguredEntries = configured;
	}

	boolean valid(String[] currentSlots, AEItemKey[] currentKeys, boolean currentPrecise) {
		return slots == currentSlots && keys == currentKeys && precise == currentPrecise;
	}

	int[] slotsFor(AEItemKey candidate, boolean ignoreNbt) {
		var cache = ignoreNbt ? ignoringNbt : exact;
		int[] cached = cache.getAndMoveToLast(candidate);
		if (cached != null) return cached;
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
		int[] result = Arrays.copyOf(hits, count);
		if (cache.size() >= MAX_CANDIDATES_PER_MODE) cache.removeFirst();
		cache.put(candidate, result);
		return result;
	}
}
