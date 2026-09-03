package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备份槽位轮转与“整合包再次更新旧配置”叠加迁移的回归测试。
 * <p>
 * 旧实现在 {@code .migrated.bak} 已存在时直接拒绝迁移，整合包作者在新版本里继续调校
 * 旧单文件时更新完全不生效；这些测试锁定新行为：重复分发同一份文件静默跳过，
 * 内容变化则轮转备份并按键叠加，玩家自己改过的键始终保留。
 */
class ServerConfigMigrationBackupsTest {

	private static final String GAMEPLAY = ModConfig.GAMEPLAY_SERVER_FILE_NAME;
	private static final String MACHINES = ModConfig.MACHINES_SERVER_FILE_NAME;

	@TempDir
	Path tempDir;

	@Test
	void backupSlotsRotateInsteadOfRefusingMigration() throws Exception {
		Path primary = ServerConfigMigrationBackups.primaryBackup(tempDir);
		assertEquals(primary, ServerConfigMigrationBackups.nextFreeBackup(tempDir));

		Files.writeString(primary, "first");
		Path second = ServerConfigMigrationBackups.nextFreeBackup(tempDir);
		assertEquals(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated-2.bak",
				second.getFileName().toString());
		assertEquals(primary, ServerConfigMigrationBackups.newestBackup(tempDir));

		Files.writeString(second, "second");
		assertEquals(second, ServerConfigMigrationBackups.newestBackup(tempDir));
		assertEquals(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated-3.bak",
				ServerConfigMigrationBackups.nextFreeBackup(tempDir).getFileName().toString());
	}

	@Test
	void repeatedPackShipmentOfTheSameFileIsRecognizedByContent() throws Exception {
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = ServerConfigMigrationBackups.primaryBackup(tempDir);
		Files.writeString(legacy, "enabled = false\n");
		Files.writeString(backup, "enabled = false\n");
		assertTrue(ServerConfigMigrationBackups.sameContent(legacy, backup));

		Files.writeString(legacy, "enabled = true\n");
		assertFalse(ServerConfigMigrationBackups.sameContent(legacy, backup));
		assertFalse(ServerConfigMigrationBackups.sameContent(legacy, null));
	}

	@Test
	void packUpdateOverlaysNewValuesButKeepsPlayerEdits() throws Exception {
		Path previousLegacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		CommentedConfig previous = CommentedConfig.inMemory();
		previous.set("balanceProfile", BalancePreset.BASIC.name());
		previous.set("enabled", false);
		previous.set("limit", 8);
		writeToml(previousLegacy, previous);

		// 上一次迁移产物：enabled=false、limit=8；玩家随后把 limit 改成 16。
		List<ServerConfigMigrationService.MigrationTarget> targets =
				targets(current(false, 16));
		ServerConfigMigrationPlanner.OverwriteGuard guard =
				ServerConfigMigrationBackups.playerEditGuard(previousLegacy, targets);
		assertTrue(guard.allowsOverwrite(GAMEPLAY, "enabled"), "玩家未改动的键应允许整合包覆盖");
		assertFalse(guard.allowsOverwrite(MACHINES, "limit"), "玩家改过的键必须保留");

		CommentedConfig updatedLegacy = CommentedConfig.inMemory();
		updatedLegacy.set("balanceProfile", BalancePreset.BASIC.name());
		updatedLegacy.set("enabled", true);
		updatedLegacy.set("limit", 32);
		ServerConfigMigrationService.MigrationPlan plan =
				ServerConfigMigrationPlanner.createPlan(updatedLegacy, targets, guard);

		assertEquals(Boolean.TRUE, content(plan, GAMEPLAY).get("enabled"));
		assertEquals(16, content(plan, MACHINES).getInt("limit"));
		assertEquals(2, plan.copiedValues(), "balanceProfile 与 enabled 应被整合包新值覆盖");
		assertEquals(1, plan.preservedValues());
		assertEquals(0, plan.invalidValues());
	}

	/** 上一次备份不可解析时无法判断玩家改动，只允许覆盖仍是默认值的键。 */
	@Test
	void unreadablePreviousBackupOnlyOverwritesDefaultValuedKeys() throws Exception {
		Path previousLegacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(previousLegacy, "这不是合法 TOML = = =\n[");

		// enabled 仍是默认值 true，limit 已被玩家从默认 4 改成 16。
		ServerConfigMigrationPlanner.OverwriteGuard guard =
				ServerConfigMigrationBackups.playerEditGuard(
						previousLegacy, targets(current(true, 16)));

		assertTrue(guard.allowsOverwrite(GAMEPLAY, "enabled"));
		assertFalse(guard.allowsOverwrite(MACHINES, "limit"));
	}

	@Test
	void missingPreviousBackupAllowsEveryKey() {
		ServerConfigMigrationPlanner.OverwriteGuard guard =
				ServerConfigMigrationBackups.playerEditGuard(
						tempDir.resolve("absent.bak"), targets(current(false, 16)));

		assertSameGuard(guard);
	}

	private static void assertSameGuard(ServerConfigMigrationPlanner.OverwriteGuard guard) {
		assertEquals(ServerConfigMigrationPlanner.OverwriteGuard.ALLOW_ALL, guard);
	}

	private static void writeToml(Path path, CommentedConfig config) throws IOException {
		try (var writer = Files.newBufferedWriter(path)) {
			new com.electronwill.nightconfig.toml.TomlWriter().write(config, writer);
		}
	}

	private record CurrentValues(boolean enabled, int limit) {
	}

	private static CurrentValues current(boolean enabled, int limit) {
		return new CurrentValues(enabled, limit);
	}

	private List<ServerConfigMigrationService.MigrationTarget> targets(CurrentValues values) {
		ModConfigSpec.Builder gameplayBuilder = new ModConfigSpec.Builder();
		gameplayBuilder.defineEnum("balanceProfile", BalancePreset.BASIC);
		gameplayBuilder.define("enabled", true);
		ModConfigSpec gameplaySpec = gameplayBuilder.build();

		ModConfigSpec.Builder machinesBuilder = new ModConfigSpec.Builder();
		machinesBuilder.defineInRange("limit", 4, 1, 64);
		ModConfigSpec machinesSpec = machinesBuilder.build();

		CommentedConfig gameplay = CommentedConfig.inMemory();
		gameplaySpec.correct(gameplay);
		gameplay.set("enabled", values.enabled());

		CommentedConfig machines = CommentedConfig.inMemory();
		machinesSpec.correct(machines);
		machines.set("limit", values.limit());

		return List.of(
				new ServerConfigMigrationService.MigrationTarget(
						GAMEPLAY, gameplaySpec, tempDir.resolve(GAMEPLAY), gameplay),
				new ServerConfigMigrationService.MigrationTarget(
						MACHINES, machinesSpec, tempDir.resolve(MACHINES), machines));
	}

	private static CommentedConfig content(
			ServerConfigMigrationService.MigrationPlan plan, String fileName) {
		return plan.targets().stream()
				.filter(target -> target.target().fileName().equals(fileName))
				.findFirst()
				.orElseThrow()
				.content();
	}
}
