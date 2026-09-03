package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整合包旧配置迁移回归测试的共享断言。
 * <p>
 * Skyhive 与 New Age 两个整合包的断言完全一致（键唯一归属、值不变、容量矩阵顺序合法），
 * 抽出到此避免在每个整合包测试里重复实现。
 */
final class LegacyPackMigrationAssertions {

	static final List<String> MATRIX_PREFIXES = List.of(
			"mek_centrifuge.stack_multiplier",
			"mek_centrifuge.input_stack_multiplier",
			"mek_centrifuge.fluid_tank_multiplier",
			"mek_apiary.stack_multiplier");

	private LegacyPackMigrationAssertions() {
	}

	/** 以规格默认值构造三个迁移目标，模拟玩家首次更新模组时的干净拆分配置。 */
	static List<ServerConfigMigrationService.MigrationTarget> defaultTargets(Path directory) {
		return List.of(
				target(directory, ModConfig.GAMEPLAY_SERVER_FILE_NAME, ModConfig.GAMEPLAY_SERVER_SPEC),
				target(directory, ModConfig.MACHINES_SERVER_FILE_NAME, ModConfig.MACHINES_SERVER_SPEC),
				target(directory, ModConfig.CAPACITIES_SERVER_FILE_NAME,
						ModConfig.CAPACITIES_SERVER_SPEC));
	}

	static ServerConfigMigrationService.MigrationTarget target(
			Path directory, String fileName, ModConfigSpec spec) {
		CommentedConfig current = CommentedConfig.inMemory();
		spec.correct(current);
		return new ServerConfigMigrationService.MigrationTarget(
				fileName, spec, directory.resolve(fileName), current);
	}

	static CommentedConfig content(
			ServerConfigMigrationService.MigrationPlan plan, String fileName) {
		return plan.targets().stream()
				.filter(target -> target.target().fileName().equals(fileName))
				.findFirst()
				.orElseThrow()
				.content();
	}

	/** 断言旧配置的每个标量/列表键都恰好落到一个新文件里，且值未被改动。 */
	static void assertEveryScalarAndListPreserved(
			CommentedConfig legacy,
			ServerConfigMigrationService.MigrationPlan plan) {
		for (String path : leafPaths(legacy)) {
			if (isLegacyMatrixPath(path)) continue;
			List<CommentedConfig> matchingTargets = plan.targets().stream()
					.map(ServerConfigMigrationService.PlannedTarget::content)
					.filter(config -> config.contains(path))
					.toList();
			assertEquals(1, matchingTargets.size(), "旧配置键必须归属且仅归属一个新配置文件: " + path);
			Object expected = legacy.get(path);
			Object actual = matchingTargets.getFirst().get(path);
			assertEquals(expected, actual, "旧配置值发生变化: " + path);
		}
	}

	/**
	 * 断言容量矩阵迁移正确：数组顺序满足并行缩放校验，旧键值逐一保留，
	 * 整合包未写过的等级（如 evolved_mekanism 五档）回落当前默认值。
	 */
	static void assertCapacityMatrices(CommentedConfig legacy, CommentedConfig capacities) {
		for (String prefix : MATRIX_PREFIXES) {
			for (String group : FactoryTierKey.configGroups()) {
				List<?> migratedGroup = capacities.get(prefix + "." + group);
				assertTrue(FactoryTierOrderingValidator.validateGroup(group, migratedGroup).isEmpty(),
						"容量矩阵顺序不满足并行缩放: " + prefix + "." + group);
			}
			for (FactoryTierKey tier : FactoryTierKey.values()) {
				Object oldValue = legacy.get(prefix + "." + tier.configKey());
				List<?> migrated = capacities.get(prefix + "." + tier.configGroup());
				Object expected = oldValue == null ? defaultValue(prefix, tier) : oldValue;
				assertEquals(expected, migrated.get(tier.groupIndex()),
						prefix + "." + tier.configKey());
			}
		}
	}

	static boolean isLegacyMatrixPath(String path) {
		return MATRIX_PREFIXES.stream().anyMatch(prefix -> path.startsWith(prefix + "."));
	}

	static Set<String> leafPaths(Config config) {
		Set<String> result = new LinkedHashSet<>();
		collectLeafPaths(config, new ArrayList<>(), result);
		return result;
	}

	private static void collectLeafPaths(Config config, List<String> parent, Set<String> output) {
		for (Config.Entry entry : config.entrySet()) {
			List<String> path = new ArrayList<>(parent);
			path.add(entry.getKey());
			if (entry.getValue() instanceof Config child) collectLeafPaths(child, path, output);
			else output.add(String.join(".", path));
		}
	}

	private static int defaultValue(String prefix, FactoryTierKey tier) {
		return switch (prefix) {
			case "mek_centrifuge.stack_multiplier" -> tier.centrifugeOutputStackDefault();
			case "mek_centrifuge.input_stack_multiplier" -> tier.centrifugeInputStackDefault();
			case "mek_centrifuge.fluid_tank_multiplier" -> tier.centrifugeFluidTankDefault();
			case "mek_apiary.stack_multiplier" -> tier.apiaryOutputStackDefault();
			default -> throw new IllegalArgumentException(prefix);
		};
	}
}
