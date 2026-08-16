package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiaryPbUpgradeNbtMigratorTest {

	@Test
	void restoresCountsAboveNewInstallLimitWithoutTruncation() {
		Map<PbUpgradeType, Integer> counts = new EnumMap<>(PbUpgradeType.class);
		ApiaryPbUpgradeNbtMigrator migrator = new ApiaryPbUpgradeNbtMigrator(counts);

		migrator.restorePersistedCount(PbUpgradeType.PRODUCTIVITY_4, 8);
		migrator.restorePersistedCount(PbUpgradeType.TIME_2, 12);

		assertEquals(8, counts.get(PbUpgradeType.PRODUCTIVITY_4));
		assertEquals(12, counts.get(PbUpgradeType.TIME_2));
	}
}
