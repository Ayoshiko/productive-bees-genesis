package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombFuzzyMatcherTest {

	@Test
	void acceptsFeywildHoneycombHandledByProductiveBeesCentrifuge() {
		assertTrue(ExternalCentrifugeCombIds.contains("feywild", "honeycomb"));
	}

	@Test
	void rejectsOtherCombLikeItemsWithoutProductiveBeesCentrifugeRecipes() {
		assertFalse(ExternalCentrifugeCombIds.contains("feywild", "honeycomb_block"));
		assertFalse(ExternalCentrifugeCombIds.contains("productivebees", "sugarbag_honeycomb"));
		assertFalse(ExternalCentrifugeCombIds.contains("gtceu", "lepidolite_honeycomb"));
	}
}
