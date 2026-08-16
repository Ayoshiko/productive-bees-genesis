package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompiledBeeTypeFilterTest {

	@Test
	void disabledModeAlwaysAllows() {
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile(
				"DISABLED", List.of("productivebees:iron"));

		assertTrue(filter.allows("productivebees:iron"));
		assertTrue(filter.allows("productivebees:diamond"));
	}

	@Test
	void emptyBlacklistAllowsEverything() {
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile("BLACKLIST", List.of());

		assertTrue(filter.allows("productivebees:iron"));
	}

	@Test
	void emptyWhitelistAllowsNothing() {
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile("WHITELIST", List.of());

		assertFalse(filter.allows("productivebees:iron"));
		assertTrue(filter.isEmptyWhitelist());
	}

	@Test
	void blacklistExcludesOnlyNormalizedEntries() {
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile(
				"BLACKLIST", List.of("  productivebees:iron  ", "productivebees:iron", "  "));

		assertFalse(filter.allows("productivebees:iron"));
		assertTrue(filter.allows("productivebees:diamond"));
		assertEquals(1, filter.entryCount());
	}

	@Test
	void whitelistAllowsOnlyNormalizedEntries() {
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile(
				"whitelist", List.of("  productivebees:iron  "));

		assertTrue(filter.allows("productivebees:iron"));
		assertFalse(filter.allows("productivebees:diamond"));
	}
}
