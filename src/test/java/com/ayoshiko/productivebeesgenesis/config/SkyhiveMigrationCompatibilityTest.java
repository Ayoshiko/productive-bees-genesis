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

	/** 整合包作者用 beebee_page_ex 把 iamall 转化成万象蜜蜂，这些玩法键必须逐一进入玩法文件。 */
	@Test
	void preservesSkyhiveMyriadAcquisitionKeysInGameplayFile() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(SKYHIVE_LEGACY),
				"本机未安装 Skyhive，跳过外部配置回归测试");

		CommentedConfig legacy = ServerConfigMigrationFiles.parse(SKYHIVE_LEGACY);
		ServerConfigMigrationService.MigrationPlan plan = ServerConfigMigrationService.createPlan(
				legacy, LegacyPackMigrationAssertions.defaultTargets(tempDir));
		CommentedConfig gameplay = LegacyPackMigrationAssertions.content(
				plan, ModConfig.GAMEPLAY_SERVER_FILE_NAME);

		assertEquals("productivebees:iamall", gameplay.get("bee_conversion.source"));
		assertEquals("productivebees:myriadcreations", gameplay.get("bee_conversion.result"));
		assertEquals("beebeeblock:beebee_page_ex", gameplay.get("bee_conversion.item"));
		assertEquals("beebeeblock:beebee_page_ex", gameplay.get("bee_attributes.flowerItem"));
		assertEquals(Boolean.FALSE, gameplay.get("bee_attributes.createComb"));
		Object profile = gameplay.get("balanceProfile");
		assertEquals(BalancePreset.BASIC.name(), String.valueOf((Object) profile));
		assertEquals(Boolean.TRUE, gameplay.get("balance.apiaryBeeGenesAffectWork"));
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
