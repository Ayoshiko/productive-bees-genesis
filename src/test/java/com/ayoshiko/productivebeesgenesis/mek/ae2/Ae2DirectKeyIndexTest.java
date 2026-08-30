package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 精确条目索引测试 — 用 String 当键，避开 AEItemKey 的 Minecraft 运行时依赖。
 * 覆盖：命中/未命中、同键多槽位累加顺序、未解析条目导致不完备、数组身份判版本。
 */
class Ae2DirectKeyIndexTest {

	private static final String DIRECT = "@fingerprint";

	@Test
	void exactHitReturnsSlotIndex() {
		String[] slots = { DIRECT, DIRECT, "productivebees:sugarbag" };
		String[] keys = { "wax", "comb", null };
		Ae2DirectKeyIndex<String> index = Ae2DirectKeyIndex.of(slots, keys, null);
		assertTrue(index.isComplete());
		assertArrayEquals(new int[] { 0 }, index.slotsFor("wax"));
		assertArrayEquals(new int[] { 1 }, index.slotsFor("comb"));
	}

	@Test
	void missingKeyReturnsNull() {
		String[] slots = { DIRECT };
		String[] keys = { "wax" };
		Ae2DirectKeyIndex<String> index = Ae2DirectKeyIndex.of(slots, keys, null);
		assertNull(index.slotsFor("iron_ingot"));
		assertNull(index.slotsFor(null));
	}

	@Test
	void duplicateKeyCollectsEverySlotInAscendingOrder() {
		String[] slots = { DIRECT, DIRECT, DIRECT };
		String[] keys = { "wax", "wax", "wax" };
		Ae2DirectKeyIndex<String> index = Ae2DirectKeyIndex.of(slots, keys, null);
		assertArrayEquals(new int[] { 0, 1, 2 }, index.slotsFor("wax"));
	}

	@Test
	void unresolvedDirectEntryMarksIndexIncomplete() {
		String[] slots = { DIRECT, DIRECT };
		String[] keys = { "wax", null };
		Ae2DirectKeyIndex<String> index = Ae2DirectKeyIndex.of(slots, keys, null);
		assertFalse(index.isComplete());
	}

	@Test
	void nullKeyArrayIsIncompleteWhenDirectEntriesExist() {
		String[] slots = { DIRECT };
		Ae2DirectKeyIndex<String> index = Ae2DirectKeyIndex.of(slots, null, null);
		assertFalse(index.isComplete());
	}

	@Test
	void onlyFuzzyEntriesStillCountAsComplete() {
		String[] slots = { "productivebees:sugarbag", "productivebees:ashy#block" };
		String[] keys = { null, null };
		Ae2DirectKeyIndex<String> index = Ae2DirectKeyIndex.of(slots, keys, null);
		assertTrue(index.isComplete());
		assertNull(index.slotsFor("wax"));
	}

	@Test
	void sameArrayIdentityReusesCachedIndex() {
		String[] slots = { DIRECT };
		String[] keys = { "wax" };
		Ae2DirectKeyIndex<String> first = Ae2DirectKeyIndex.of(slots, keys, null);
		assertSame(first, Ae2DirectKeyIndex.of(slots, keys, first));
	}

	@Test
	void keyArrayReplacementInvalidatesCachedIndex() {
		String[] slots = { DIRECT };
		String[] oldKeys = { null };
		Ae2DirectKeyIndex<String> stale = Ae2DirectKeyIndex.of(slots, oldKeys, null);
		assertFalse(stale.isComplete());
		// resolveDirectKey 只克隆 keys 不动 slots，索引必须据此重建
		String[] resolved = { "wax" };
		Ae2DirectKeyIndex<String> fresh = Ae2DirectKeyIndex.of(slots, resolved, stale);
		assertTrue(fresh.isComplete());
		assertArrayEquals(new int[] { 0 }, fresh.slotsFor("wax"));
	}

	@Test
	void slotArrayReplacementInvalidatesCachedIndex() {
		String[] keys = { "wax" };
		Ae2DirectKeyIndex<String> first = Ae2DirectKeyIndex.of(new String[] { DIRECT }, keys, null);
		Ae2DirectKeyIndex<String> second = Ae2DirectKeyIndex.of(new String[] { DIRECT }, keys, first);
		assertFalse(first == second);
	}
}
