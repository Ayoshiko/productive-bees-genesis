package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UselessByproductUpgradeHelperTest {

	@Test
	void upgradeMetadataEnforcesTheSingleItemLimit() {
		assertEquals("useless_byproduct", PbUpgradeType.USELESS_BYPRODUCT.getId());
		assertEquals(1, PbUpgradeType.USELESS_BYPRODUCT.getMaxCount());
	}
}
