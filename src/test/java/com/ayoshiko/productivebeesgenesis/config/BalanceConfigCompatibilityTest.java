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
