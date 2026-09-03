package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用本机 Skyhive 整合包配置验证拆分迁移不会覆盖整合包作者的调校。 */
class SkyhiveMigrationCompatibilityTest {

	private static final Path SKYHIVE_LEGACY = Path.of(
			"E:/mczuixin/.minecraft/versions/Beebeeblock - The Skyhive3.05/config/"
					+ "productivebeesgenesis-server.toml");
	private static final Path SKYHIVE_KUBEJS = Path.of(
			"E:/mczuixin/.minecraft/versions/Beebeeblock - The Skyhive3.05/kubejs/"
					+ "server_scripts/mod/beebeeblockitems/astrahive/new.js");

	@TempDir
	Path tempDir;

	@Test
	void preservesSkyhiveBeeConversionAcquisitionAndCapacityTuning() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(SKYHIVE_LEGACY),
				"本机未安装 Skyhive，跳过外部配置回归测试");

		CommentedConfig legacy = ServerConfigMigrationFiles.parse(SKYHIVE_LEGACY);
		ServerConfigMigrationService.MigrationPlan plan = ServerConfigMigrationService.createPlan(
				legacy, LegacyPackMigrationAssertions.defaultTargets(tempDir));

		assertTrue(plan.copiedValues() > 0);
		assertEquals(0, plan.invalidValues(), "Skyhive 旧配置不应有值回退到当前默认值");
		assertTrue(plan.unknownPaths().isEmpty(),
				"Skyhive 旧配置存在未识别键: " + plan.unknownPaths());
		LegacyPackMigrationAssertions.assertEveryScalarAndListPreserved(legacy, plan);
		LegacyPackMigrationAssertions.assertCapacityMatrices(legacy,
				LegacyPackMigrationAssertions.content(plan, ModConfig.CAPACITIES_SERVER_FILE_NAME));
	}

	@Test
	void skyhiveRecipeOverridesUseStableIdsAndDoNotDependOnLegacyConfigPath() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(SKYHIVE_KUBEJS),
				"本机未安装 Skyhive 配方脚本，跳过外部配方回归测试");

		String script = Files.readString(SKYHIVE_KUBEJS);
		assertTrue(script.contains("event.remove({ id: 'productivebeesgenesis:mek_apiary' })"));
		assertTrue(script.contains("event.remove({ id: 'productivebeesgenesis:mek_centrifuge' })"));
		assertFalse(script.contains(ModConfig.LEGACY_SERVER_FILE_NAME));
	}
}
