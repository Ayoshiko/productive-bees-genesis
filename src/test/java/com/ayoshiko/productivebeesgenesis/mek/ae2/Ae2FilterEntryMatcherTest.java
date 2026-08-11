package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2FilterEntryMatcherTest {

	@Test
	void fuzzyModeGroupsCombAndCombBlockByBeeType() {
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron", "productivebees:iron", false, false));
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron", "productivebees:iron", true, false));
		assertFalse(Ae2FilterEntryMatcher.matches("productivebees:iron", "productivebees:gold", false, false));
	}

	@Test
	void preciseModeKeepsCombAndCombBlockSeparate() {
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron", "productivebees:iron", false, true));
		assertFalse(Ae2FilterEntryMatcher.matches("productivebees:iron", "productivebees:iron", true, true));
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block", "productivebees:iron", true, true));
		assertFalse(Ae2FilterEntryMatcher.matches("productivebees:iron#block", "productivebees:iron", false, true));
	}

	@Test
	void legacyBlockSuffixRemainsCompatibleWhenPreciseModeIsOff() {
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block", "productivebees:iron", false, false));
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block", "productivebees:iron", true, false));
	}

	@Test
	void directFingerprintsNeverEnterFuzzyMatching() {
		assertFalse(Ae2FilterEntryMatcher.matches("@{id:\"minecraft:honeycomb\"}",
				"minecraft:honeycomb", false, false));
		assertFalse(Ae2FilterEntryMatcher.matches(null, "minecraft:honeycomb", false, false));
	}

	@Test
	void directEntriesShareCombAndCombBlockWhenPreciseModeIsOff() {
		// Precise mode off: a marked comb pulls its comb block too, even when
		// NBT matching is enabled (NBT-ignore switch off).
		assertTrue(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:gold", true,
				false, false, false));
		assertTrue(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:gold", false,
				false, false, false));
		assertTrue(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", true, "productivebees:gold", false,
				false, false, false));
		// A different bee type never matches.
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:iron", false,
				false, false, false));
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:iron", false,
				true, true, false));
	}

	@Test
	void preciseModeDirectEntriesMirrorNbtTearCard() {
		// Precise mode: comb and comb block are different items and never share.
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:gold", true,
				false, true, true));
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:gold", true,
				false, false, true));
		// NBT-ignore (tear-card semantics): same base item matches.
		assertTrue(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:gold", false,
				true, true, true));
		// Without NBT-ignore only the exact AE key may match.
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, "productivebees:gold", false,
				true, false, true));
	}

	@Test
	void untypedItemsOnlyFuzzyMatchTheSameBaseItem() {
		assertTrue(Ae2FilterEntryMatcher.matchesDirect(false,
				null, false, null, false, true, true, false));
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				null, false, null, false, false, true, false));
		// Untyped items cannot match typed combs even with NBT-ignore.
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				null, false, "productivebees:gold", false, true, true, false));
		assertFalse(Ae2FilterEntryMatcher.matchesDirect(false,
				"productivebees:gold", false, null, false, true, true, false));
	}
}
