package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把旧的单文件服务端配置映射为三个领域配置的内容计划。
 * <p>
 * 只负责“值怎么搬”，不涉及磁盘事务与迁移资格判定（SRP）：
 * <ul>
 *   <li>标量/列表键按同名路径直接复制，非法值计入 invalid 并保留基线值；</li>
 *   <li>旧的每等级标量矩阵键按 {@link FactoryTierKey#groupIndex()} 填入新的分组数组；</li>
 *   <li>旧文件没有 balanceProfile 时补齐 pre-balance 时代的行为常量。</li>
 * </ul>
 */
final class ServerConfigMigrationPlanner {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/ConfigMigrationPlanner");
	static final List<String> LEGACY_MATRIX_PREFIXES = List.of(
			"mek_centrifuge.stack_multiplier",
			"mek_centrifuge.input_stack_multiplier",
			"mek_centrifuge.fluid_tank_multiplier",
			"mek_apiary.stack_multiplier");

	private ServerConfigMigrationPlanner() {
	}

	/**
	 * 生成迁移计划。基线为每个目标的当前内容，因此新增的配置键会保留当前默认值。
	 *
	 * @param guard 逐键覆盖许可；玩家改过的键不会被旧文件覆盖
	 */
	static ServerConfigMigrationService.MigrationPlan createPlan(
			CommentedConfig legacy,
			List<ServerConfigMigrationService.MigrationTarget> targets,
			OverwriteGuard guard) throws IOException {
		List<ServerConfigMigrationService.PlannedTarget> plannedTargets =
				new ArrayList<>(targets.size());
		Set<String> recognizedLegacyPaths = new HashSet<>();
		Counters counters = new Counters();

		for (ServerConfigMigrationService.MigrationTarget target : targets) {
			CommentedConfig migrated = CommentedConfig.copy(target.current());
			for (ModConfigSpec.ConfigValue<?> value : ConfigTraversal.configValues(target.spec())) {
				String targetPath = ConfigTraversal.path(value.getPath());
				String matrixPrefix = matrixPrefix(targetPath);
				if (matrixPrefix != null) {
					copyLegacyMatrixGroup(legacy, migrated, value, matrixPrefix,
							recognizedLegacyPaths, guardFor(guard, target, targetPath), counters);
					continue;
				}
				if (!legacy.contains(targetPath)) continue;
				recognizedLegacyPaths.add(targetPath);
				if (!guardFor(guard, target, targetPath)) {
					counters.preserved++;
					continue;
				}
				Object raw = legacy.get(targetPath);
				if (value.getSpec().test(raw)) {
					migrated.set(value.getPath(), copyValue(raw));
					counters.copied++;
				} else {
					counters.invalid++;
				}
			}
			plannedTargets.add(new ServerConfigMigrationService.PlannedTarget(target, migrated));
		}

		if (!legacy.contains("balanceProfile")) {
			applyPreBalanceCompatibility(legacy, plannedTargets, guard);
		}
		for (ServerConfigMigrationService.PlannedTarget target : plannedTargets) {
			if (!target.target().spec().isCorrect(target.content())) {
				throw new IOException("迁移后的配置未通过规格校验: " + target.target().fileName());
			}
		}

		Set<String> unknownPaths = ConfigTraversal.leafPaths(legacy);
		unknownPaths.removeAll(recognizedLegacyPaths);
		if (!unknownPaths.isEmpty()) {
			LOGGER.warn("旧配置包含 {} 个已停用或未知键；这些键保留在备份中：{}",
					unknownPaths.size(), unknownPaths);
		}
		return new ServerConfigMigrationService.MigrationPlan(
				List.copyOf(plannedTargets), counters.copied, counters.invalid,
				counters.preserved, Set.copyOf(unknownPaths));
	}

	private static boolean guardFor(
			OverwriteGuard guard,
			ServerConfigMigrationService.MigrationTarget target,
			String path) {
		return guard.allowsOverwrite(target.fileName(), path);
	}

	private static void copyLegacyMatrixGroup(
			CommentedConfig legacy,
			CommentedConfig migrated,
			ModConfigSpec.ConfigValue<?> targetValue,
			String matrixPrefix,
			Set<String> recognizedPaths,
			boolean allowOverwrite,
			Counters counters) {
		String group = targetValue.getPath().getLast();
		List<FactoryTierKey> tiers = FactoryTierKey.groupTiers(group);
		List<Integer> defaults = integerDefaults(targetValue.getDefault(), tiers.size());
		List<Integer> migratedValues = new ArrayList<>(defaults);
		boolean present = false;
		int copied = 0;
		int invalid = 0;
		for (FactoryTierKey tier : tiers) {
			String legacyPath = matrixPrefix + "." + tier.configKey();
			if (!legacy.contains(legacyPath)) continue;
			recognizedPaths.add(legacyPath);
			present = true;
			Object raw = legacy.get(legacyPath);
			Integer value = positiveInteger(raw);
			if (value == null) {
				invalid++;
				continue;
			}
			migratedValues.set(tier.groupIndex(), value);
			copied++;
		}
		if (!allowOverwrite) {
			// 玩家改过这一组容量数组：整组保留当前值，避免出现半旧半新的容量阶梯。
			if (present) counters.preserved++;
			return;
		}
		counters.copied += copied;
		counters.invalid += invalid;
		migrated.set(targetValue.getPath(), migratedValues);
	}

	/**
	 * 旧文件没有 balanceProfile 时（1.0.4 之前），把平衡开关固定为当时的实际行为，
	 * 并按旧上限范围还原玩家调过的升级数量，避免更新后被新默认值悄悄改动数值。
	 */
	private static void applyPreBalanceCompatibility(
			CommentedConfig legacy,
			List<ServerConfigMigrationService.PlannedTarget> targets,
			OverwriteGuard guard) {
		CommentedConfig gameplay = contentOrNull(targets, ModConfig.GAMEPLAY_SERVER_FILE_NAME);
		if (gameplay != null) {
			setGuarded(guard, ModConfig.GAMEPLAY_SERVER_FILE_NAME, gameplay,
					"balanceProfile", BalancePreset.CUSTOM.name());
			setGuarded(guard, ModConfig.GAMEPLAY_SERVER_FILE_NAME, gameplay,
					"balance.productivityUpgradeTiersExclusive",
					BalanceConfig.LEGACY_PRODUCTIVITY_EXCLUSIVE);
			setGuarded(guard, ModConfig.GAMEPLAY_SERVER_FILE_NAME, gameplay,
					"balance.speedUpgradeTiersExclusive", BalanceConfig.LEGACY_SPEED_EXCLUSIVE);
			setGuarded(guard, ModConfig.GAMEPLAY_SERVER_FILE_NAME, gameplay,
					"balance.centrifugeProductivityAffectsOutput",
					BalanceConfig.LEGACY_CENTRIFUGE_OUTPUT);
			setGuarded(guard, ModConfig.GAMEPLAY_SERVER_FILE_NAME, gameplay,
					"balance.apiaryBeeGenesAffectWork",
					BalanceConfig.LEGACY_APIARY_BEE_GENES_AFFECT_WORK);
		}
		CommentedConfig machines = contentOrNull(targets, ModConfig.MACHINES_SERVER_FILE_NAME);
		if (machines == null) return;
		for (String path : List.of(
				"mek_apiary.pb_upgrade.productivityMaxCount",
				"mek_apiary.pb_upgrade.timeMaxCount",
				"mek_centrifuge.pb_upgrade.productivityMaxCount",
				"mek_centrifuge.pb_upgrade.timeMaxCount")) {
			setGuarded(guard, ModConfig.MACHINES_SERVER_FILE_NAME, machines, path,
					legacyInt(legacy, path, BalanceConfig.LEGACY_PB_UPGRADE_LIMIT, 1, 64));
		}
		setGuarded(guard, ModConfig.MACHINES_SERVER_FILE_NAME, machines,
				"mek_centrifuge.me_upgrade.maxStackUpgrades",
				legacyInt(legacy, "mek_centrifuge.me_upgrade.maxStackUpgrades",
						BalanceConfig.LEGACY_STACK_UPGRADE_LIMIT, 8, 32));
	}

	private static void setGuarded(
			OverwriteGuard guard,
			String fileName,
			CommentedConfig content,
			String path,
			Object value) {
		if (!guard.allowsOverwrite(fileName, path)) return;
		content.set(path, value);
	}

	private static CommentedConfig contentOrNull(
			List<ServerConfigMigrationService.PlannedTarget> targets, String fileName) {
		return targets.stream()
				.filter(target -> target.target().fileName().equals(fileName))
				.findFirst()
				.map(ServerConfigMigrationService.PlannedTarget::content)
				.orElse(null);
	}

	private static int legacyInt(
			CommentedConfig legacy, String path, int fallback, int minimum, int maximum) {
		if (!legacy.contains(path) || !(legacy.get(path) instanceof Number number)) return fallback;
		long value = number.longValue();
		return value >= minimum && value <= maximum ? (int) value : fallback;
	}

	static String matrixPrefix(String targetPath) {
		for (String prefix : LEGACY_MATRIX_PREFIXES) {
			if (targetPath.startsWith(prefix + ".")) return prefix;
		}
		return null;
	}

	private static List<Integer> integerDefaults(Object rawDefaults, int expectedSize) {
		if (!(rawDefaults instanceof List<?> values) || values.size() != expectedSize) {
			throw new IllegalStateException("容量矩阵默认值长度错误");
		}
		List<Integer> result = new ArrayList<>(expectedSize);
		for (Object value : values) {
			Integer integer = positiveInteger(value);
			if (integer == null) throw new IllegalStateException("容量矩阵默认值非法");
			result.add(integer);
		}
		return result;
	}

	private static Integer positiveInteger(Object value) {
		if (!(value instanceof Number number)) return null;
		long candidate = number.longValue();
		if (candidate < 1 || candidate > Integer.MAX_VALUE
				|| number.doubleValue() != candidate) {
			return null;
		}
		return (int) candidate;
	}

	private static Object copyValue(Object value) {
		return value instanceof List<?> list ? new ArrayList<>(list) : value;
	}

	private static final class Counters {
		private int copied;
		private int invalid;
		private int preserved;
	}

	/** 逐键覆盖许可：返回 false 表示该键由玩家维护，旧文件的值不得覆盖。 */
	@FunctionalInterface
	interface OverwriteGuard {
		OverwriteGuard ALLOW_ALL = (fileName, path) -> true;

		boolean allowsOverwrite(String fileName, String path);
	}
}
