package com.ayoshiko.productivebeesgenesis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceConfigCompatibilityTest {

	@TempDir
	Path tempDir;

	@Test
	void recognizesFreshNeoForgeCorrectionOfLegacyConfig() throws Exception {
		Path current = tempDir.resolve("productivebeesgenesis-server.toml");
		Path backup = tempDir.resolve("productivebeesgenesis-server-1.toml.bak");
		Files.writeString(backup, legacyConfig());
		Files.writeString(current, "balanceProfile = \"BASIC\"\n" + legacyConfig());
		setCorrectionTimes(backup, current, 1);

		assertEquals(backup, BalanceConfigCompatibility.firstCorrectionBackup(current));
		assertEquals(backup, BalanceConfigCompatibility.recentLegacyBackup(current));
		assertFalse(BalanceConfigCompatibility.containsPath(backup,
				"mek_apiary.pb_upgrade.timeMaxCount"));
		assertTrue(BalanceConfigCompatibility.containsPath(backup,
				"mek_apiary.pb_upgrade.productivityMaxCount"));
	}

	@Test
	void ignoresBackupThatAlreadyHasBalanceProfile() throws Exception {
		Path current = tempDir.resolve("productivebeesgenesis-server.toml");
		Path backup = tempDir.resolve("productivebeesgenesis-server-1.toml.bak");
		Files.writeString(backup, "balanceProfile = \"CUSTOM\"\n" + legacyConfig());
		Files.writeString(current, "balanceProfile = \"BASIC\"\n" + legacyConfig());
		setCorrectionTimes(backup, current, 1);

		assertNull(BalanceConfigCompatibility.recentLegacyBackup(current));
	}

	@Test
	void ignoresStaleBackupSoExplicitBasicChoiceIsNotReverted() throws Exception {
		Path current = tempDir.resolve("productivebeesgenesis-server.toml");
		Path backup = tempDir.resolve("productivebeesgenesis-server-1.toml.bak");
		Files.writeString(backup, legacyConfig());
		Files.writeString(current, "balanceProfile = \"BASIC\"\n" + legacyConfig());
		setCorrectionTimes(backup, current, 600);

		assertNull(BalanceConfigCompatibility.recentLegacyBackup(current));
	}

	@Test
	void selectsTheNewestLegacyBackupWhenEarlierCorrectionSlotsAreStale() throws Exception {
		Path current = tempDir.resolve("productivebeesgenesis-server.toml");
		Path stale = tempDir.resolve("productivebeesgenesis-server-1.toml.bak");
		Path fresh = tempDir.resolve("productivebeesgenesis-server-2.toml.bak");
		Files.writeString(stale, legacyConfig());
		Files.writeString(fresh, legacyConfig());
		Files.writeString(current, "balanceProfile = \"BASIC\"\n" + legacyConfig());
		Instant correctionTime = Instant.parse("2026-08-16T00:00:00Z");
		Files.setLastModifiedTime(stale, FileTime.from(correctionTime.minusSeconds(600)));
		Files.setLastModifiedTime(fresh, FileTime.from(correctionTime));
		Files.setLastModifiedTime(current, FileTime.from(correctionTime.plusSeconds(1)));

		assertEquals(fresh, BalanceConfigCompatibility.recentLegacyBackup(current));
	}

	@Test
	void restoresLegacyLimitValuesAndUsesSafeDefaultsForMissingOrInvalidValues() throws Exception {
		Path legacy = tempDir.resolve("legacy-limits.toml");
		Files.writeString(legacy, """
				[mek_apiary.pb_upgrade]
				productivityMaxCount = 12

				[mek_centrifuge.me_upgrade]
				maxStackUpgrades = 99
				""");

		assertEquals(12, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_apiary.pb_upgrade.productivityMaxCount", 8, 1, 64));
		assertEquals(8, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_apiary.pb_upgrade.timeMaxCount", 8, 1, 64));
		assertEquals(16, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_centrifuge.me_upgrade.maxStackUpgrades", 16, 8, 32));
	}

	@Test
	void preservesEveryLegacyStrengthAndRequiredCustomSwitch() throws Exception {
		Path legacy = tempDir.resolve("legacy-strengths.toml");
		Files.writeString(legacy, legacyConfig());

		assertFalse(BalanceConfig.LEGACY_PRODUCTIVITY_EXCLUSIVE);
		assertFalse(BalanceConfig.LEGACY_SPEED_EXCLUSIVE);
		assertTrue(BalanceConfig.LEGACY_CENTRIFUGE_OUTPUT);
		assertFalse(BalanceConfig.LEGACY_APIARY_BEE_GENES_AFFECT_WORK);
		assertEquals(2, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_apiary.pb_upgrade.productivityMaxCount", 8, 1, 64));
		assertEquals(8, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_apiary.pb_upgrade.timeMaxCount", 8, 1, 64));
		assertEquals(8, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_centrifuge.pb_upgrade.productivityMaxCount", 8, 1, 64));
		assertEquals(12, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_centrifuge.pb_upgrade.timeMaxCount", 8, 1, 64));
		assertEquals(24, BalanceConfigCompatibility.legacyLimit(legacy,
				"mek_centrifuge.me_upgrade.maxStackUpgrades", 16, 8, 32));
	}

	private static String legacyConfig() {
		return """
				[mek_apiary.pb_upgrade]
				productivityMaxCount = 2

				[mek_centrifuge.pb_upgrade]
				timeMaxCount = 12

				[mek_centrifuge.me_upgrade]
				maxStackUpgrades = 24
				""";
	}

	private static void setCorrectionTimes(Path backup, Path current, long delaySeconds)
			throws Exception {
		Instant correctionTime = Instant.parse("2026-08-16T00:00:00Z");
		Files.setLastModifiedTime(backup, FileTime.from(correctionTime));
		Files.setLastModifiedTime(current, FileTime.from(correctionTime.plusSeconds(delaySeconds)));
	}
}
