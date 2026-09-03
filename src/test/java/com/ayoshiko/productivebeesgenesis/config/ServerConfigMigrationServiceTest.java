package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigMigrationServiceTest {

	private static final List<String> MATRIX_PREFIXES = List.of(
			"mek_centrifuge.stack_multiplier",
			"mek_centrifuge.input_stack_multiplier",
			"mek_centrifuge.fluid_tank_multiplier",
			"mek_apiary.stack_multiplier");

	@TempDir
	Path tempDir;

	@Test
	void preservesScalarListsEnumsAndAllSixtyEightLegacyTierValues() throws Exception {
		TestTargets testTargets = createTargets();
		CommentedConfig legacy = CommentedConfig.inMemory();
		legacy.set("balanceProfile", BalancePreset.CUSTOM.name());
		legacy.set("enabled", false);
		legacy.set("ratio", 0.375D);
		legacy.set("names", List.of("minecraft:bee", "productivebees:iron"));
		legacy.set("limit", 17);
		legacy.set("energy", 9_876_543_210L);

		int next = 1_000;
		for (String prefix : MATRIX_PREFIXES) {
			for (FactoryTierKey tier : FactoryTierKey.values()) {
				legacy.set(prefix + "." + tier.configKey(), next++);
			}
		}

		ServerConfigMigrationService.MigrationPlan plan =
				ServerConfigMigrationService.createPlan(legacy, testTargets.targets());
		CommentedConfig gameplay = content(plan, ModConfig.GAMEPLAY_SERVER_FILE_NAME);
		CommentedConfig machines = content(plan, ModConfig.MACHINES_SERVER_FILE_NAME);
		CommentedConfig capacities = content(plan, ModConfig.CAPACITIES_SERVER_FILE_NAME);

		assertEquals(74, plan.copiedValues());
		assertEquals(0, plan.invalidValues());
		assertTrue(plan.unknownPaths().isEmpty());
		assertEquals(BalancePreset.CUSTOM.name(), gameplay.get("balanceProfile"));
		assertEquals(false, gameplay.get("enabled"));
		assertEquals(0.375D, gameplay.get("ratio"));
		assertEquals(List.of("minecraft:bee", "productivebees:iron"), gameplay.get("names"));
		assertEquals(Integer.valueOf(17), machines.get("limit"));
		assertEquals(Long.valueOf(9_876_543_210L), machines.get("energy"));

		next = 1_000;
		for (String prefix : MATRIX_PREFIXES) {
			for (FactoryTierKey tier : FactoryTierKey.values()) {
				List<?> group = capacities.get(prefix + "." + tier.configGroup());
				assertEquals(next++, group.get(tier.groupIndex()), prefix + "." + tier.configKey());
			}
		}
	}

	@Test
	void missingAndInvalidValuesUseCurrentDefaultsWithoutDiscardingValidSiblings() throws Exception {
		TestTargets testTargets = createTargets();
		CommentedConfig legacy = CommentedConfig.inMemory();
		legacy.set("balanceProfile", BalancePreset.BASIC.name());
		legacy.set("ratio", 99.0D);
		legacy.set("mek_centrifuge.stack_multiplier.basic", -1);
		legacy.set("mek_centrifuge.stack_multiplier.advanced", 4321);

		ServerConfigMigrationService.MigrationPlan plan =
				ServerConfigMigrationService.createPlan(legacy, testTargets.targets());
		CommentedConfig gameplay = content(plan, ModConfig.GAMEPLAY_SERVER_FILE_NAME);
		CommentedConfig capacities = content(plan, ModConfig.CAPACITIES_SERVER_FILE_NAME);
		List<?> mekanism = capacities.get("mek_centrifuge.stack_multiplier.mekanism");

		assertEquals(2, plan.invalidValues());
		assertEquals(0.5D, gameplay.get("ratio"));
		assertEquals(List.of("minecraft:bee"), gameplay.get("names"));
		assertEquals(FactoryTierKey.BASIC.centrifugeOutputStackDefault(), mekanism.get(0));
		assertEquals(4321, mekanism.get(1));
	}

	@Test
	void preBalanceConfigKeepsLegacyBehaviorAndUserTunedLimits() throws Exception {
		TestTargets testTargets = createTargets();
		CommentedConfig legacy = CommentedConfig.inMemory();
		legacy.set("mek_apiary.pb_upgrade.productivityMaxCount", 12);

		ServerConfigMigrationService.MigrationPlan plan =
				ServerConfigMigrationService.createPlan(legacy, testTargets.targets());
		CommentedConfig gameplay = content(plan, ModConfig.GAMEPLAY_SERVER_FILE_NAME);
		CommentedConfig machines = content(plan, ModConfig.MACHINES_SERVER_FILE_NAME);

		assertEquals(BalancePreset.CUSTOM.name(), gameplay.get("balanceProfile"));
		assertEquals(false, gameplay.get("balance.productivityUpgradeTiersExclusive"));
		assertEquals(false, gameplay.get("balance.speedUpgradeTiersExclusive"));
		assertEquals(true, gameplay.get("balance.centrifugeProductivityAffectsOutput"));
		assertEquals(false, gameplay.get("balance.apiaryBeeGenesAffectWork"));
		assertEquals(Integer.valueOf(12), machines.get("mek_apiary.pb_upgrade.productivityMaxCount"));
		assertEquals(Integer.valueOf(8), machines.get("mek_apiary.pb_upgrade.timeMaxCount"));
		assertEquals(Integer.valueOf(8), machines.get("mek_centrifuge.pb_upgrade.productivityMaxCount"));
		assertEquals(Integer.valueOf(8), machines.get("mek_centrifuge.pb_upgrade.timeMaxCount"));
		assertEquals(Integer.valueOf(16), machines.get("mek_centrifuge.me_upgrade.maxStackUpgrades"));
	}

	@Test
	void partialMigrationPreservesModifiedGameplayFileAndMigratesMachineFile() throws Exception {
		TestTargets allTargets = createTargets();
		CommentedConfig legacy = CommentedConfig.inMemory();
		legacy.set("mek_apiary.pb_upgrade.productivityMaxCount", 12);

		List<ServerConfigMigrationService.MigrationTarget> machineOnly = allTargets.targets().stream()
				.filter(target -> !target.fileName().equals(ModConfig.GAMEPLAY_SERVER_FILE_NAME))
				.toList();
		ServerConfigMigrationService.MigrationPlan plan =
				ServerConfigMigrationService.createPlan(legacy, machineOnly);

		assertEquals(2, plan.targets().size());
		CommentedConfig machines = content(plan, ModConfig.MACHINES_SERVER_FILE_NAME);
		assertEquals(Integer.valueOf(12),
				machines.get("mek_apiary.pb_upgrade.productivityMaxCount"));
	}

	@Test
	void optionalIntegrationKeysAlwaysRemainInTheMachineSpec() {
		Set<String> paths = configPaths(ModConfig.MACHINES_SERVER_SPEC);
		assertTrue(paths.containsAll(List.of(
				"mek_centrifuge.ae2.aeOutputEnabled",
				"mek_centrifuge.ae2.aeFluidOutputEnabled",
				"mek_centrifuge.ae2.aeEnergyInputEnabled",
				"mek_centrifuge.ae2.preferAppliedFluxOverAeEnergy",
				"mek_centrifuge.ae2.aeNativeEnergyInputEnabled",
				"mek_centrifuge.ae2.aeInputEnabled",
				"mek_centrifuge.ae2.aeInputRatePerTick",
				"mek_centrifuge.ae2.aeInputIntervalTicks",
				"mek_centrifuge.ae2.aeInputMinPages",
				"mek_apiary.ae2.aeOutputEnabled",
				"mek_apiary.ae2.aeFluidOutputEnabled",
				"mek_apiary.ae2.aeEnergyInputEnabled",
				"mek_apiary.ae2.preferAppliedFluxOverAeEnergy",
				"mek_apiary.ae2.aeNativeEnergyInputEnabled")));
	}

	@Test
	void successfulCommitReplacesTargetsAndBacksUpLegacy() throws Exception {
		TestTargets testTargets = createTargets();
		ServerConfigMigrationService.MigrationPlan plan = basicPlan(testTargets);
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(legacy, "legacy-content");
		writeOriginalTargets(testTargets.targets());

		ServerConfigMigrationFiles.commitFiles(
				tempDir, legacy, backup, plan,
				ServerConfigMigrationFiles.ReplacementObserver.NONE);

		assertFalse(Files.exists(legacy));
		assertEquals("legacy-content", Files.readString(backup));
		for (ServerConfigMigrationService.MigrationTarget target : testTargets.targets()) {
			CommentedConfig migrated = ServerConfigMigrationFiles.parse(target.path());
			assertTrue(target.spec().isCorrect(migrated));
		}
		assertEquals(false, ServerConfigMigrationFiles.parse(
				tempDir.resolve(ModConfig.GAMEPLAY_SERVER_FILE_NAME)).get("enabled"));
		assertNoMigrationTemps();
	}

	@Test
	void successfulCommitCanCreatePreviouslyAbsentSplitFiles() throws Exception {
		TestTargets testTargets = createTargets();
		ServerConfigMigrationService.MigrationPlan plan = basicPlan(testTargets);
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(legacy, "legacy-content");

		ServerConfigMigrationFiles.commitFiles(
				tempDir, legacy, backup, plan,
				ServerConfigMigrationFiles.ReplacementObserver.NONE);

		assertFalse(Files.exists(legacy));
		assertEquals("legacy-content", Files.readString(backup));
		for (ServerConfigMigrationService.MigrationTarget target : testTargets.targets()) {
			assertTrue(Files.isRegularFile(target.path()));
			assertTrue(target.spec().isCorrect(ServerConfigMigrationFiles.parse(target.path())));
		}
		assertNoMigrationTemps();
	}

	@Test
	void interruptedMigrationRestoresEveryTargetFromRollbackFiles() throws Exception {
		TestTargets testTargets = createTargets();
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(legacy, "legacy-content");
		Files.writeString(tempDir.resolve(".productivebeesgenesis-server-migration.pending"), "pending\n");

		List<String> originals = new ArrayList<>();
		for (int index = 0; index < testTargets.targets().size(); index++) {
			Path destination = testTargets.targets().get(index).path();
			String original = "original-" + index;
			originals.add(original);
			Files.writeString(destination, "partially-replaced-" + index);
			Files.writeString(destination.resolveSibling(
					"." + destination.getFileName() + ".migration.rollback"), original);
			Files.writeString(destination.resolveSibling(
					"." + destination.getFileName() + ".migration.tmp"), "staged-" + index);
		}

		ServerConfigMigrationFiles.recoverInterruptedMigration(
				tempDir,
				testTargets.targets().stream()
						.map(ServerConfigMigrationService.MigrationTarget::path)
						.toList(),
				legacy,
				backup);

		assertTargetContents(testTargets.targets(), originals);
		assertTrue(Files.exists(legacy));
		assertFalse(Files.exists(backup));
		assertNoMigrationTemps();
	}

	@Test
	void failedReplacementRestoresEveryNewFileAndLeavesLegacyInPlace() throws Exception {
		TestTargets testTargets = createTargets();
		ServerConfigMigrationService.MigrationPlan plan = basicPlan(testTargets);
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(legacy, "legacy");
		List<String> originals = writeOriginalTargets(testTargets.targets());

		assertThrows(IOException.class, () -> ServerConfigMigrationFiles.commitFiles(
				tempDir, legacy, backup, plan,
				(index, target) -> {
					if (index == 1) throw new IOException("simulated failure");
				}));

		assertTrue(Files.exists(legacy));
		assertFalse(Files.exists(backup));
		assertTargetContents(testTargets.targets(), originals);
		assertNoMigrationTemps();
	}

	@Test
	void failedFirstCreationRemovesAnySplitFilesCreatedBeforeFailure() throws Exception {
		TestTargets testTargets = createTargets();
		ServerConfigMigrationService.MigrationPlan plan = basicPlan(testTargets);
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(legacy, "legacy");

		assertThrows(IOException.class, () -> ServerConfigMigrationFiles.commitFiles(
				tempDir, legacy, backup, plan,
				(index, target) -> {
					if (index == 1) throw new IOException("simulated failure");
				}));

		assertTrue(Files.exists(legacy));
		assertFalse(Files.exists(backup));
		for (ServerConfigMigrationService.MigrationTarget target : testTargets.targets()) {
			assertFalse(Files.exists(target.path()), target.path().toString());
		}
		assertNoMigrationTemps();
	}

	@Test
	void worldServerConfigLegacyFileTakesPriorityOverBaseConfig() throws Exception {
		TestTargets loadedTargets = createTargets();
		Path worldServerConfig = tempDir.resolve("world").resolve("serverconfig");
		Files.createDirectories(worldServerConfig);
		Files.writeString(tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME), "base-legacy");
		Files.writeString(
				worldServerConfig.resolve(ModConfig.LEGACY_SERVER_FILE_NAME), "world-legacy");

		ServerConfigMigrationService.MigrationScope scope =
				ServerConfigMigrationService.findMigrationScope(
						worldServerConfig, tempDir, loadedTargets.targets());

		assertTrue(scope.isWorldScope());
		assertEquals(worldServerConfig.toAbsolutePath().normalize(),
				scope.directory().toAbsolutePath().normalize());
		assertEquals(worldServerConfig.resolve(ModConfig.LEGACY_SERVER_FILE_NAME),
				scope.legacyPath());
		for (ServerConfigMigrationService.MigrationTarget target : scope.targets()) {
			assertEquals(worldServerConfig.resolve(target.fileName()), target.path());
		}
	}

	@Test
	void baseConfigIsUsedWhenNoWorldLegacyFileExists() throws Exception {
		TestTargets loadedTargets = createTargets();
		Path worldServerConfig = tempDir.resolve("world").resolve("serverconfig");
		Files.createDirectories(worldServerConfig);
		Path baseLegacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Files.writeString(baseLegacy, "base-legacy");

		ServerConfigMigrationService.MigrationScope scope =
				ServerConfigMigrationService.findMigrationScope(
						worldServerConfig, tempDir, loadedTargets.targets());

		assertFalse(scope.isWorldScope());
		assertEquals(tempDir.toAbsolutePath().normalize(),
				scope.directory().toAbsolutePath().normalize());
		assertEquals(baseLegacy, scope.legacyPath());
	}

	@Test
	void existingBackupIsNeverOverwritten() throws Exception {
		TestTargets testTargets = createTargets();
		ServerConfigMigrationService.MigrationPlan plan = basicPlan(testTargets);
		Path legacy = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME);
		Path backup = tempDir.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated.bak");
		Files.writeString(legacy, "legacy");
		Files.writeString(backup, "historical-backup");
		List<String> originals = writeOriginalTargets(testTargets.targets());

		assertThrows(IOException.class, () -> ServerConfigMigrationFiles.commitFiles(
				tempDir, legacy, backup, plan,
				ServerConfigMigrationFiles.ReplacementObserver.NONE));

		assertEquals("historical-backup", Files.readString(backup));
		assertEquals("legacy", Files.readString(legacy));
		assertTargetContents(testTargets.targets(), originals);
	}

	private ServerConfigMigrationService.MigrationPlan basicPlan(TestTargets targets) throws Exception {
		CommentedConfig legacy = CommentedConfig.inMemory();
		legacy.set("balanceProfile", BalancePreset.BASIC.name());
		legacy.set("enabled", false);
		return ServerConfigMigrationService.createPlan(legacy, targets.targets());
	}

	private List<String> writeOriginalTargets(
			List<ServerConfigMigrationService.MigrationTarget> targets) throws IOException {
		List<String> originals = new ArrayList<>();
		for (int index = 0; index < targets.size(); index++) {
			String content = "original-" + index;
			Files.writeString(targets.get(index).path(), content);
			originals.add(content);
		}
		return originals;
	}

	private static void assertTargetContents(
			List<ServerConfigMigrationService.MigrationTarget> targets,
			List<String> expected) throws IOException {
		for (int index = 0; index < targets.size(); index++) {
			assertEquals(expected.get(index), Files.readString(targets.get(index).path()));
		}
	}

	private void assertNoMigrationTemps() throws IOException {
		try (var paths = Files.list(tempDir)) {
			assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains("migration")));
		}
	}

	private TestTargets createTargets() {
		ModConfigSpec.Builder gameplayBuilder = new ModConfigSpec.Builder();
		gameplayBuilder.defineEnum("balanceProfile", BalancePreset.BASIC);
		gameplayBuilder.define("enabled", true);
		gameplayBuilder.defineInRange("ratio", 0.5D, 0.0D, 1.0D);
		gameplayBuilder.defineList(
				"names", List.of("minecraft:bee"), () -> "", value -> value instanceof String);
		gameplayBuilder.push("balance");
		gameplayBuilder.define("productivityUpgradeTiersExclusive", true);
		gameplayBuilder.define("speedUpgradeTiersExclusive", true);
		gameplayBuilder.define("centrifugeProductivityAffectsOutput", false);
		gameplayBuilder.define("apiaryBeeGenesAffectWork", true);
		gameplayBuilder.pop();
		ModConfigSpec gameplaySpec = gameplayBuilder.build();

		ModConfigSpec.Builder machinesBuilder = new ModConfigSpec.Builder();
		machinesBuilder.defineInRange("limit", 4, 1, 64);
		machinesBuilder.defineInRange("energy", 100L, 1L, Long.MAX_VALUE);
		defineLimit(machinesBuilder, "mek_apiary.pb_upgrade.productivityMaxCount", 4, 1, 64);
		defineLimit(machinesBuilder, "mek_apiary.pb_upgrade.timeMaxCount", 4, 1, 64);
		defineLimit(machinesBuilder, "mek_centrifuge.pb_upgrade.productivityMaxCount", 4, 1, 64);
		defineLimit(machinesBuilder, "mek_centrifuge.pb_upgrade.timeMaxCount", 4, 1, 64);
		defineLimit(machinesBuilder, "mek_centrifuge.me_upgrade.maxStackUpgrades", 8, 8, 32);
		ModConfigSpec machinesSpec = machinesBuilder.build();

		ModConfigSpec.Builder capacitiesBuilder = new ModConfigSpec.Builder();
		registerMatrix(capacitiesBuilder, "mek_centrifuge.stack_multiplier",
				FactoryTierKey::centrifugeOutputStackDefault);
		registerMatrix(capacitiesBuilder, "mek_centrifuge.input_stack_multiplier",
				FactoryTierKey::centrifugeInputStackDefault);
		registerMatrix(capacitiesBuilder, "mek_centrifuge.fluid_tank_multiplier",
				FactoryTierKey::centrifugeFluidTankDefault);
		registerMatrix(capacitiesBuilder, "mek_apiary.stack_multiplier",
				FactoryTierKey::apiaryOutputStackDefault);
		ModConfigSpec capacitiesSpec = capacitiesBuilder.build();

		List<ServerConfigMigrationService.MigrationTarget> targets = List.of(
				target(ModConfig.GAMEPLAY_SERVER_FILE_NAME, gameplaySpec),
				target(ModConfig.MACHINES_SERVER_FILE_NAME, machinesSpec),
				target(ModConfig.CAPACITIES_SERVER_FILE_NAME, capacitiesSpec));
		return new TestTargets(targets);
	}

	private ServerConfigMigrationService.MigrationTarget target(
			String fileName, ModConfigSpec spec) {
		CommentedConfig current = CommentedConfig.inMemory();
		spec.correct(current);
		return new ServerConfigMigrationService.MigrationTarget(
				fileName, spec, tempDir.resolve(fileName), current);
	}

	private static void defineLimit(
			ModConfigSpec.Builder builder, String path, int value, int minimum, int maximum) {
		builder.defineInRange(List.of(path.split("\\.")), value, minimum, maximum);
	}

	private static void registerMatrix(
			ModConfigSpec.Builder builder,
			String path,
			ToIntFunction<FactoryTierKey> defaults) {
		String[] parts = path.split("\\.");
		builder.push(List.of(parts));
		FactoryTierConfigValues.register(builder, "test." + path, defaults);
		builder.pop(parts.length);
	}

	private static Set<String> configPaths(ModConfigSpec spec) {
		Set<String> paths = new LinkedHashSet<>();
		collectConfigPaths(spec.getValues(), paths);
		return paths;
	}

	private static void collectConfigPaths(
			com.electronwill.nightconfig.core.UnmodifiableConfig config,
			Set<String> paths) {
		for (com.electronwill.nightconfig.core.UnmodifiableConfig.Entry entry : config.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof ModConfigSpec.ConfigValue<?> configValue) {
				paths.add(String.join(".", configValue.getPath()));
			} else if (value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig child) {
				collectConfigPaths(child, paths);
			}
		}
	}

	private static CommentedConfig content(
			ServerConfigMigrationService.MigrationPlan plan, String fileName) {
		return plan.targets().stream()
				.filter(target -> target.target().fileName().equals(fileName))
				.findFirst()
				.orElseThrow()
				.content();
	}

	private record TestTargets(
			List<ServerConfigMigrationService.MigrationTarget> targets) {
	}
}
