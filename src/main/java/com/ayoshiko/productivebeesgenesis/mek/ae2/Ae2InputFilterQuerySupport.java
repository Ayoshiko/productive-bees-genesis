package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter.DirectEntry;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter.EntryInfo;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter.FilterMode;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter.IndexedEntry;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

/**
	 * {@link Ae2InputFilter} 只读查询与 AE2 物品匹配/拉取上限工具（从过滤器拆分，SRP）
	 * <br/>
	 * 无状态：每个方法接收 volatile 读状态快照作为参数，绝不修改过滤器。
	 */
final class Ae2InputFilterQuerySupport {

	record FuzzyEntry(ResourceLocation beeType, boolean block) {
	}

	private Ae2InputFilterQuerySupport() {
	}

	/** Entry at the given slot index (split from {@link Ae2InputFilter#getEntryAt(int)}). */
	static EntryInfo entryAt(String[] slots, int index) {
		if (index < 0 || index >= slots.length || slots[index] == null) return null;
		return Ae2FilterEntrySupport.parseEntry(slots[index]);
	}

	/** Non-empty slots as index+entry pairs (split from {@link Ae2InputFilter#getNonEmptyEntries()}). */
	static List<IndexedEntry> nonEmptyEntries(String[] slots) {
		List<IndexedEntry> result = new ArrayList<>();
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] != null) {
				result.add(new IndexedEntry(i, slots[i]));
			}
		}
		return result;
	}

	/**
	 * 判断指定蜜蜂类型是否允许拉取
	 * <br/>
	 * 根据 filterMode 和 preciseMode 判断：
	 * <ul>
	 *   <li>DISABLED：所有蜜脾都允许（返回 true）</li>
	 *   <li>WHITELIST：仅在过滤列表中的蜜蜂类型允许</li>
	 *   <li>BLACKLIST：仅在过滤列表中的蜜蜂类型禁止</li>
	 * </ul>
	 * preciseMode=true 时，蜜脾和蜜脾块分别匹配；false 时共享同一过滤条目。
	 *
	 * @param beeType 蜜蜂类型 ID
	 * @param isBlock 是否为蜜脾块
	 * @return true 表示允许拉取
	 */
	static boolean isAllowed(ResourceLocation beeType, boolean isBlock, FilterMode mode, boolean precise,
			FuzzyEntry[] fuzzyEntries) {
		if (beeType == null) return true;
		boolean matched = false;
		for (FuzzyEntry configured : fuzzyEntries) {
			if (matchesFuzzyEntry(configured, beeType, isBlock, precise)) {
				matched = true;
				break;
			}
		}
		return switch (mode) {
			case WHITELIST -> matched;
			case BLACKLIST -> !matched;
			case DISABLED -> true;
		};
	}

	static boolean isAllowed(AEItemKey key, FilterMode mode, boolean precise, String[] slots,
			FuzzyEntry[] fuzzyEntries, AEItemKey[] keys, boolean ignoreNbt,
			HolderLookup.Provider registries) {
		if (key == null) return false;
		if (mode == FilterMode.DISABLED) return true;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(key);
		boolean isBlock = CombFuzzyMatcher.isCombBlock(key);
		for (int i = 0; i < slots.length; i++) {
			String entry = slots[i];
			boolean matches = false;
			if (Ae2InputFilter.isDirectFingerprint(entry)) {
				AEItemKey configured = keys != null && i < keys.length ? keys[i] : null;
				if (configured == null && registries != null) {
					configured = Ae2ItemFingerprint.decode(
							entry.substring(Ae2InputFilter.DIRECT_ENTRY_PREFIX.length()), registries);
				}
				matches = Ae2FilterEntrySupport.matchesDirectEntry(entry, configured, key, beeType, isBlock,
						ignoreNbt, precise);
			} else if (beeType != null) {
				matches = matchesFuzzyEntry(fuzzyEntries[i], beeType, isBlock, precise);
			}
			if (!matches) continue;
			return mode == FilterMode.WHITELIST;
		}
		return switch (mode) {
			case WHITELIST -> false;
			case BLACKLIST -> true;
			case DISABLED -> true;
		};
	}

	/**
	 * Returns true when the key matches at least one configured entry (direct or
	 * fuzzy), regardless of whitelist/blacklist mode. The puller uses this to rank
	 * marked entries ahead of unmarked ones ("mark first" AE2LT semantics).
	 */
	static boolean matchesAnyEntry(AEItemKey key, String[] slots, FuzzyEntry[] fuzzyEntries, AEItemKey[] keys,
			boolean precise, boolean ignoreNbt) {
		if (key == null) return false;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(key);
		boolean isBlock = CombFuzzyMatcher.isCombBlock(key);
		for (int i = 0; i < slots.length; i++) {
			String entry = slots[i];
			if (entry == null || entry.isBlank()) continue;
			if (Ae2InputFilter.isDirectFingerprint(entry)) {
				AEItemKey configured = keys != null && i < keys.length ? keys[i] : null;
				if (Ae2FilterEntrySupport.matchesDirectEntry(entry, configured, key, beeType, isBlock,
						ignoreNbt, precise)) return true;
			} else if (beeType != null && matchesFuzzyEntry(fuzzyEntries[i], beeType, isBlock, precise)) {
				return true;
			}
		}
		return false;
	}

	static FuzzyEntry[] compileFuzzyEntries(String[] slots) {
		FuzzyEntry[] compiled = new FuzzyEntry[slots.length];
		for (int i = 0; i < slots.length; i++) {
			String entry = slots[i];
			if (entry == null || entry.isBlank() || Ae2InputFilter.isDirectFingerprint(entry)) continue;
			boolean block = entry.endsWith("#block");
			String typeText = block ? entry.substring(0, entry.length() - 6) : entry;
			ResourceLocation beeType = ResourceLocation.tryParse(typeText);
			if (beeType != null) compiled[i] = new FuzzyEntry(beeType, block);
		}
		return compiled;
	}

	private static boolean matchesFuzzyEntry(FuzzyEntry configured, ResourceLocation candidateBeeType,
			boolean candidateBlock, boolean precise) {
		return configured != null && configured.beeType().equals(candidateBeeType)
				&& (!precise || configured.block() == candidateBlock);
	}

	static long directPullLimit(AEItemKey key, long visibleStock, boolean ignoreNbt,
			HolderLookup.Provider registries, String[] slots, AEItemKey[] keys,
			long[] amounts, boolean[] unlimited, boolean precise) {
		if (key == null) return -1L;
		ResourceLocation candidateBeeType = CombFuzzyMatcher.getBeeType(key);
		boolean candidateBlock = CombFuzzyMatcher.isCombBlock(key);
		boolean found = false;
		boolean liveStock = false;
		long requested = 0L;
		for (int i = 0; i < slots.length; i++) {
			if (!Ae2InputFilter.isDirectFingerprint(slots[i])) continue;
			AEItemKey configured = keys != null && i < keys.length ? keys[i] : null;
			if (configured == null && registries != null) {
				configured = Ae2ItemFingerprint.decode(
						slots[i].substring(Ae2InputFilter.DIRECT_ENTRY_PREFIX.length()), registries);
			}
			if (!Ae2FilterEntrySupport.matchesDirectEntry(slots[i], configured, key, candidateBeeType,
					candidateBlock, ignoreNbt, precise)) continue;
			found = true;
			if (unlimited[i]) {
				liveStock = true;
				continue;
			}
			requested = Ae2PullAmountMath.addConfigured(requested, amounts[i]);
		}
		if (!found) return -1L;
		return Ae2PullAmountMath.effectiveLimit(requested, visibleStock, liveStock,
				Ae2InputFilter.getMaxDirectAmount());
	}

	/**
	 * Combines filter admission and direct-entry pull-limit calculation in one slot walk.
	 * Returns {@link Ae2InputFilter#PULL_DISALLOWED} when rejected, {@code -1} when
	 * allowed without a direct limit, or a non-negative effective limit.
	 */
	static long pullLimitIfAllowed(AEItemKey key, long visibleStock, boolean ignoreNbt,
			FilterMode mode, boolean precise, String[] slots, FuzzyEntry[] fuzzyEntries,
			AEItemKey[] keys, long[] amounts, boolean[] unlimited) {
		if (key == null) return Ae2InputFilter.PULL_DISALLOWED;
		ResourceLocation candidateBeeType = CombFuzzyMatcher.getBeeType(key);
		boolean candidateBlock = CombFuzzyMatcher.isCombBlock(key);
		boolean filterMatched = false;
		boolean directFound = false;
		boolean liveStock = false;
		long requested = 0L;

		for (int i = 0; i < slots.length; i++) {
			String entry = slots[i];
			if (entry == null) continue;
			boolean matches;
			if (Ae2InputFilter.isDirectFingerprint(entry)) {
				AEItemKey configured = keys != null && i < keys.length ? keys[i] : null;
				matches = Ae2FilterEntrySupport.matchesDirectEntry(entry, configured, key,
						candidateBeeType, candidateBlock, ignoreNbt, precise);
				if (matches) {
					directFound = true;
					if (i < unlimited.length && unlimited[i]) {
						liveStock = true;
					} else if (i < amounts.length) {
						requested = Ae2PullAmountMath.addConfigured(requested, amounts[i]);
					}
				}
			} else {
				matches = candidateBeeType != null && i < fuzzyEntries.length
						&& matchesFuzzyEntry(fuzzyEntries[i], candidateBeeType, candidateBlock, precise);
			}
			if (!matches) continue;
			filterMatched = true;
			if (mode == FilterMode.BLACKLIST) return Ae2InputFilter.PULL_DISALLOWED;
		}

		if (mode == FilterMode.WHITELIST && !filterMatched) {
			return Ae2InputFilter.PULL_DISALLOWED;
		}
		if (!directFound) return -1L;
		return Ae2PullAmountMath.effectiveLimit(requested, visibleStock, liveStock,
				Ae2InputFilter.getMaxDirectAmount());
	}

	/** Returns true when at least one exact entry uses live network stock. */
	static boolean hasNetworkStockEntries(String[] slots, boolean[] unlimited) {
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) && unlimited[i]) return true;
		}
		return false;
	}

	/** True when at least one direct entry has unlimited provide enabled. */
	static boolean hasUnlimitedEntries(String[] slots, boolean[] unlimited) {
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) && unlimited[i]) return true;
		}
		return false;
	}

	static boolean hasFuzzyEntries(String[] slots) {
		for (String entry : slots) if (entry != null && !Ae2InputFilter.isDirectFingerprint(entry)) return true;
		return false;
	}

	/** True when every configured entry has opted into exact network-stock mode. */
	static boolean hasOnlyNetworkStockEntries(String[] slots, boolean[] unlimited) {
		boolean found = false;
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] == null) continue;
			if (!Ae2InputFilter.isDirectFingerprint(slots[i]) || !unlimited[i]) return false;
			found = true;
		}
		return found;
	}

	/**
	 * Builds the immutable direct-entry snapshot (split from {@link Ae2InputFilter#getDirectEntries()});
	 * the caller keeps the cache and publication semantics.
	 */
	static List<DirectEntry> collectDirectEntries(String[] slots, AEItemKey[] keys, long[] amounts, boolean[] unlimited) {
		List<DirectEntry> result = new ArrayList<>();
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i])) {
				// keys 懒创建可能为 null（NBT 恢复后未解析），此时 key 记为 null 表示待解析
				result.add(new DirectEntry(i, slots[i].substring(Ae2InputFilter.DIRECT_ENTRY_PREFIX.length()),
						keys == null ? null : keys[i], amounts[i], unlimited[i]));
			}
		}
		return result;
	}

	static AEItemKey resolvedDirectKey(AEItemKey[] keys, int index) {
		return keys != null && index >= 0 && index < keys.length ? keys[index] : null;
	}

	static long directAmountAt(long[] amounts, int index) {
		return index >= 0 && index < amounts.length ? amounts[index] : 0L;
	}

	static long directVisibleAmountAt(long[] visible, int index) {
		return index >= 0 && index < visible.length ? visible[index] : 0L;
	}

	static boolean isDirectUnlimitedAt(boolean[] unlimited, int index) {
		return index >= 0 && index < unlimited.length && unlimited[index];
	}

	static boolean isDirectEntry(String[] slots, int index) {
		return index >= 0 && index < slots.length && Ae2InputFilter.isDirectFingerprint(slots[index]);
	}

}
