package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本机 New Age Science and Technology hard096 整合包验证 1.0.5 → 新版的真实升级路径。
 * <p>
 * 该整合包装的是 productivebeesgenesis-1.0.5.jar，配置目录里只有旧单文件与 NeoForge
 * 校正备份（{@code productivebeesgenesis-server-1.toml.bak}），没有 {@code .migrated.bak}，
 * 因此是首次迁移；测试同时覆盖整合包作者在下个版本继续调校旧文件的叠加迁移。
 */
class NewAgeMigrationCompatibilityTest {

	private static final Path PACK_ROOT = Path.of(
			"E:/mczuixin/.minecraft/versions/New Age Science and Technology hard096");
	private static final Path NEW_AGE_LEGACY = PACK_ROOT.resolve(
			"config/productivebeesgenesis-server.toml");
	private static final Path NEW_AGE_KUBEJS = PACK_ROOT.resolve(
			"kubejs/server_scripts/bee/rec.js");

	@TempDir
	Path tempDir;

	@Test
	void preservesNewAgeTuningWhenUpgradingFromSingleFileConfig() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(NEW_AGE_LEGACY),
				"本机未安装 New Age 整合包，跳过外部配置回归测试");

		CommentedConfig legacy = ServerConfigMigrationFiles.parse(NEW_AGE_LEGACY);
		ServerConfigMigrationService.MigrationPlan plan = ServerConfigMigrationService.createPlan(
				legacy, LegacyPackMigrationAssertions.defaultTargets(tempDir));
		CommentedConfig gameplay = LegacyPackMigrationAssertions.content(
				plan, ModConfig.GAMEPLAY_SERVER_FILE_NAME);
		CommentedConfig machines = LegacyPackMigrationAssertions.content(
				plan, ModConfig.MACHINES_SERVER_FILE_NAME);

		assertTrue(plan.copiedValues() > 0);
		assertEquals(0, plan.invalidValues(), "New Age 旧配置不应有值回退到当前默认值");
		assertTrue(plan.unknownPaths().isEmpty(),
				"New Age 旧配置存在未识别键: " + plan.unknownPaths());
		LegacyPackMigrationAssertions.assertEveryScalarAndListPreserved(legacy, plan);
		LegacyPackMigrationAssertions.assertCapacityMatrices(legacy,
				LegacyPackMigrationAssertions.content(plan, ModConfig.CAPACITIES_SERVER_FILE_NAME));

		// 整合包核心调校：万象蜜蜂由蜜糖块属性 + 自繁殖获取，机器倍率沿用作者数值。
		assertEquals(Boolean.TRUE, gameplay.get("myriadCreationsEnabled"));
		assertEquals("productivebees:honey_treat", gameplay.get("bee_attributes.flowerItem"));
		assertEquals("productivebees:myriadcreations", gameplay.get("bee_acquisition.breeding.parent1"));
		assertEquals(Boolean.TRUE, gameplay.get("bee_acquisition.breeding.enabled"));
		assertEquals(BalancePreset.BASIC.name(),
				String.valueOf((Object) gameplay.get("balanceProfile")));
		assertEquals(200, machines.getInt("mek_centrifuge.basic.processingTime"));
		assertEquals(1200, machines.getInt("mek_apiary.basic.processingTime"));
		assertEquals(8, machines.getInt("mek_centrifuge.me_upgrade.maxStackUpgrades"));
	}

	/**
	 * 完整落盘链路：首次迁移 → 整合包重复分发同一份旧文件 → 整合包新版改了旧文件。
	 * 断言备份槽位轮转、玩家改过的键保留、整合包新值仍然生效。
	 */
	@Test
	void packRedistributionAndLaterPackEditsBothBehaveCorrectly() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(NEW_AGE_LEGACY),
				"本机未安装 New Age 整合包，跳过外部配置回归测试");

		Path legacyPath = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Files.copy(NEW_AGE_LEGACY, legacyPath, StandardCopyOption.REPLACE_EXISTING);

		Path primaryBackup = ServerConfigMigrationBackups.primaryBackup(tempDir);
		commit(legacyPath, primaryBackup, ServerConfigMigrationService.createPlan(
				ServerConfigMigrationFiles.parse(legacyPath),
				LegacyPackMigrationAssertions.defaultTargets(tempDir)));

		assertFalse(Files.exists(legacyPath), "首次迁移后旧文件应移动为备份");
		assertTrue(Files.isRegularFile(primaryBackup));
		assertEquals(primaryBackup, ServerConfigMigrationBackups.newestBackup(tempDir));
		for (String fileName : List.of(
				ModConfig.GAMEPLAY_SERVER_FILE_NAME,
				ModConfig.MACHINES_SERVER_FILE_NAME,
				ModConfig.CAPACITIES_SERVER_FILE_NAME)) {
			assertTrue(Files.isRegularFile(tempDir.resolve(fileName)), fileName);
		}

		// 场景一：整合包更新时再次分发同一份旧文件 → 内容一致，必须识别为重复分发。
		Files.copy(NEW_AGE_LEGACY, legacyPath, StandardCopyOption.REPLACE_EXISTING);
		assertTrue(ServerConfigMigrationBackups.sameContent(legacyPath, primaryBackup));

		// 玩家把离心机处理时间从 200 改成 120。
		Path machinesPath = tempDir.resolve(ModConfig.MACHINES_SERVER_FILE_NAME);
		CommentedConfig machinesOnDisk = ServerConfigMigrationFiles.parse(machinesPath);
		machinesOnDisk.set("mek_centrifuge.basic.processingTime", 120);
		write(machinesPath, machinesOnDisk);

		// 场景二：整合包作者在新版把处理时间改成 400、并把蜂箱能耗改成 80。
		CommentedConfig updatedLegacy = ServerConfigMigrationFiles.parse(NEW_AGE_LEGACY);
		updatedLegacy.set("mek_centrifuge.basic.processingTime", 400);
		updatedLegacy.set("mek_apiary.basic.energyPerTick", 80L);
		write(legacyPath, updatedLegacy);
		assertFalse(ServerConfigMigrationBackups.sameContent(legacyPath, primaryBackup));

		List<ServerConfigMigrationService.MigrationTarget> currentTargets = onDiskTargets();
		ServerConfigMigrationPlanner.OverwriteGuard guard =
				ServerConfigMigrationBackups.playerEditGuard(primaryBackup, currentTargets);
		Path secondBackup = ServerConfigMigrationBackups.nextFreeBackup(tempDir);
		assertEquals(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated-2.bak",
				secondBackup.getFileName().toString());

		ServerConfigMigrationService.MigrationPlan overlay =
				ServerConfigMigrationPlanner.createPlan(
						ServerConfigMigrationFiles.parse(legacyPath), currentTargets, guard);
		commit(legacyPath, secondBackup, overlay);

		CommentedConfig machines = ServerConfigMigrationFiles.parse(machinesPath);
		assertEquals(120, machines.getInt("mek_centrifuge.basic.processingTime"),
				"玩家改过的处理时间必须保留");
		assertEquals(80, machines.getLong("mek_apiary.basic.energyPerTick"),
				"整合包新版对未被玩家改动键的调整必须生效");
		assertTrue(overlay.preservedValues() > 0, "应至少保留一个玩家改动");
		assertTrue(Files.isRegularFile(secondBackup), "备份槽位应轮转而不是拒绝迁移");
	}

	@Test
	void newAgeRecipeOverridesUseStableIdsAndDoNotDependOnLegacyConfigPath() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(NEW_AGE_KUBEJS),
				"本机未安装 New Age 配方脚本，跳过外部配方回归测试");

		String script = Files.readString(NEW_AGE_KUBEJS);
		assertTrue(script.contains(
				"event.remove({ id: 'productivebees:bee_conversion/myriadcreations' })"));
		assertTrue(script.contains("event.remove({ output: 'productivebeesgenesis:mek_apiary' })"));
		assertTrue(script.contains("productivebeesgenesis:mek_apiary"));
		assertFalse(script.contains(ModConfig.LEGACY_SERVER_FILE_NAME));
	}

	private void commit(
			Path legacyPath,
			Path backupPath,
			ServerConfigMigrationService.MigrationPlan plan) throws IOException {
		ServerConfigMigrationFiles.commitFiles(
				tempDir, legacyPath, backupPath, plan,
				ServerConfigMigrationFiles.ReplacementObserver.NONE);
	}

	/** 用磁盘上的当前拆分文件构造迁移目标，模拟玩家已经运行过一次新版的状态。 */
	private List<ServerConfigMigrationService.MigrationTarget> onDiskTargets() throws IOException {
		List<ServerConfigMigrationService.MigrationTarget> targets = new ArrayList<>(3);
		targets.add(onDiskTarget(ModConfig.GAMEPLAY_SERVER_FILE_NAME, ModConfig.GAMEPLAY_SERVER_SPEC));
		targets.add(onDiskTarget(ModConfig.MACHINES_SERVER_FILE_NAME, ModConfig.MACHINES_SERVER_SPEC));
		targets.add(onDiskTarget(
				ModConfig.CAPACITIES_SERVER_FILE_NAME, ModConfig.CAPACITIES_SERVER_SPEC));
		return List.copyOf(targets);
	}

	private ServerConfigMigrationService.MigrationTarget onDiskTarget(
			String fileName, ModConfigSpec spec) throws IOException {
		Path path = tempDir.resolve(fileName);
		return new ServerConfigMigrationService.MigrationTarget(
				fileName, spec, path, ServerConfigMigrationFiles.parse(path));
	}

	private static void write(Path path, CommentedConfig config) throws IOException {
		try (var writer = Files.newBufferedWriter(path)) {
			new com.electronwill.nightconfig.toml.TomlWriter().write(config, writer);
		}
	}
}
