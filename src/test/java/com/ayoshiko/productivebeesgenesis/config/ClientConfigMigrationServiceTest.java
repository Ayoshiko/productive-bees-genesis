package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigMigrationServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void migratesLegacyWindowFieldsAndKeepsLegacyKeys() {
		CommentedConfig disk = CommentedConfig.inMemory();
		disk.set("window_positions.pb_upgrade.x", 123);
		disk.set("window_positions.pb_upgrade.y", -45);
		disk.set("window_positions.pb_upgrade.pinned", true);
		CommentedConfig loaded = defaults();

		ClientConfigMigrationService.MigrationResult result =
				ClientConfigMigrationService.migrateWindowPositions(disk, loaded);

		assertEquals(3, result.copiedValues());
		assertEquals(Integer.valueOf(123), loaded.get("window_positions.window_pb_upgrade.x"));
		assertEquals(Integer.valueOf(-45), loaded.get("window_positions.window_pb_upgrade.y"));
		assertEquals(Boolean.TRUE, loaded.get("window_positions.window_pb_upgrade.pinned"));
		assertEquals(Integer.valueOf(123), loaded.get("window_positions.pb_upgrade.x"));
	}

	@Test
	void treatsMissingNewFieldsAsDefaults() {
		CommentedConfig disk = CommentedConfig.inMemory();
		disk.set("window_positions.feeder.x", 12);
		CommentedConfig loaded = CommentedConfig.inMemory();

		ClientConfigMigrationService.MigrationResult result =
				ClientConfigMigrationService.migrateWindowPositions(disk, loaded);

		assertEquals(1, result.copiedValues());
		assertEquals(Integer.valueOf(12), loaded.get("window_positions.window_feeder.x"));
	}

	@Test
	void preservesAlreadyConfiguredNewFieldsAndMigratesOnlyDefaults() {
		CommentedConfig disk = CommentedConfig.inMemory();
		disk.set("window_positions.ae_input.x", 300);
		disk.set("window_positions.ae_input.y", 400);
		disk.set("window_positions.ae_input.pinned", true);
		CommentedConfig loaded = defaults();
		loaded.set("window_positions.window_ae_input.x", 99);

		ClientConfigMigrationService.MigrationResult result =
				ClientConfigMigrationService.migrateWindowPositions(disk, loaded);

		assertEquals(2, result.copiedValues());
		assertEquals(Integer.valueOf(99), loaded.get("window_positions.window_ae_input.x"));
		assertEquals(Integer.valueOf(400), loaded.get("window_positions.window_ae_input.y"));
		assertEquals(Boolean.TRUE, loaded.get("window_positions.window_ae_input.pinned"));
	}

	@Test
	void ignoresInvalidLegacyTypesWithoutChangingDefaults() {
		CommentedConfig disk = CommentedConfig.inMemory();
		disk.set("window_positions.feeder.x", "not-an-int");
		disk.set("window_positions.feeder.y", Integer.MIN_VALUE);
		disk.set("window_positions.feeder.pinned", "true");
		CommentedConfig loaded = defaults();

		ClientConfigMigrationService.MigrationResult result =
				ClientConfigMigrationService.migrateWindowPositions(disk, loaded);

		assertEquals(1, result.copiedValues());
		assertEquals(2, result.invalidValues());
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), loaded.get("window_positions.window_feeder.x"));
		assertEquals(Integer.valueOf(Integer.MIN_VALUE), loaded.get("window_positions.window_feeder.y"));
	}

	@Test
	void findsOnlyRecentNeoForgeCorrectionBackups() throws Exception {
		Path current = tempDir.resolve("productivebeesgenesis-client.toml");
		Path recent = tempDir.resolve("productivebeesgenesis-client-1.toml.bak");
		Path stale = tempDir.resolve("productivebeesgenesis-client-2.toml.bak");
		Files.writeString(current, "rainbow_effects = {}\n");
		Files.writeString(recent, "legacy\n");
		Files.writeString(stale, "legacy\n");
		long now = System.currentTimeMillis();
		Files.setLastModifiedTime(current, FileTime.fromMillis(now));
		Files.setLastModifiedTime(recent, FileTime.fromMillis(now - 30_000));
		Files.setLastModifiedTime(stale, FileTime.fromMillis(now - 10 * 60_000));

		assertEquals(recent, ClientConfigMigrationService.recentCorrectionBackup(current));
	}

	private static CommentedConfig defaults() {
		CommentedConfig loaded = CommentedConfig.inMemory();
		for (String name : new String[]{
				"window_pb_upgrade", "window_ae_input", "window_feeder", "window_multi_fluid_tanks"}) {
			loaded.set("window_positions." + name + ".x", Integer.MAX_VALUE);
			loaded.set("window_positions." + name + ".y", Integer.MAX_VALUE);
			loaded.set("window_positions." + name + ".pinned", false);
		}
		return loaded;
	}
}
