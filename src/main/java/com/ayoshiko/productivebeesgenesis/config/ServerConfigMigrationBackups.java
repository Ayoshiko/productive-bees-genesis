package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 旧配置备份槽位管理与“整合包再次更新旧文件”的覆盖许可判定。
 * <p>
 * 早期实现在检测到 {@code .migrated.bak} 时直接拒绝迁移，导致整合包作者在新版本里
 * 继续调校旧单文件时更新完全不生效。此处改为：
 * <ul>
 *   <li>旧文件与最新备份内容一致 → 整合包只是重复分发同一份文件，静默跳过；</li>
 *   <li>内容不同 → 视为作者更新，写入下一个空闲编号备份槽，并把新值叠加到现有拆分配置上；</li>
 *   <li>叠加时逐键比较“上一次迁移产物”与“当前文件”，玩家自己改过的键一律保留。</li>
 * </ul>
 */
final class ServerConfigMigrationBackups {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/ConfigMigrationBackups");
	static final String MIGRATED_BACKUP_SUFFIX = ".migrated.bak";
	private static final int MAX_BACKUP_SLOTS = 64;

	private ServerConfigMigrationBackups() {
	}

	static Path primaryBackup(Path directory) {
		return directory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME + MIGRATED_BACKUP_SUFFIX);
	}

	/** 返回下一个空闲备份槽：{@code .migrated.bak}、{@code .migrated-2.bak}…… */
	static Path nextFreeBackup(Path directory) throws IOException {
		Path primary = primaryBackup(directory);
		if (!Files.exists(primary)) return primary;
		for (int index = 2; index <= MAX_BACKUP_SLOTS; index++) {
			Path candidate = numberedBackup(directory, index);
			if (!Files.exists(candidate)) return candidate;
		}
		throw new IOException("旧配置备份槽位已用尽: " + directory);
	}

	/** 返回编号最大的既有备份（即上一次迁移使用的旧文件），没有备份时返回 null。 */
	static Path newestBackup(Path directory) {
		Path primary = primaryBackup(directory);
		if (!Files.exists(primary)) return null;
		Path newest = primary;
		for (int index = 2; index <= MAX_BACKUP_SLOTS; index++) {
			Path candidate = numberedBackup(directory, index);
			if (!Files.exists(candidate)) break;
			newest = candidate;
		}
		return newest;
	}

	private static Path numberedBackup(Path directory, int index) {
		return directory.resolve(
				ModConfig.LEGACY_SERVER_FILE_NAME + ".migrated-" + index + ".bak");
	}

	/** 逐字节比较两个文件，用于识别整合包重复分发同一份旧配置。 */
	static boolean sameContent(Path first, Path second) throws IOException {
		if (first == null || second == null
				|| !Files.isRegularFile(first) || !Files.isRegularFile(second)) {
			return false;
		}
		if (Files.size(first) != Files.size(second)) return false;
		return Files.mismatch(first, second) == -1L;
	}

	/**
	 * 构造覆盖许可：把上一次迁移的旧文件重新映射一遍，得到“若玩家从未改动则应有的内容”，
	 * 再与当前磁盘内容逐键比较；不一致的键判定为玩家修改并保留。
	 */
	static ServerConfigMigrationPlanner.OverwriteGuard playerEditGuard(
			Path previousLegacy,
			List<ServerConfigMigrationService.MigrationTarget> targets) {
		if (previousLegacy == null || !Files.isRegularFile(previousLegacy)) {
			return ServerConfigMigrationPlanner.OverwriteGuard.ALLOW_ALL;
		}
		try {
			CommentedConfig previous = ServerConfigMigrationFiles.parse(previousLegacy);
			List<ServerConfigMigrationService.MigrationTarget> pristine = targets.stream()
					.map(ServerConfigMigrationService::pristineTarget)
					.toList();
			ServerConfigMigrationService.MigrationPlan plan =
					ServerConfigMigrationPlanner.createPlan(previous, pristine,
							ServerConfigMigrationPlanner.OverwriteGuard.ALLOW_ALL);
			Map<String, CommentedConfig> expected = plan.targets().stream().collect(
					Collectors.toMap(
							target -> target.target().fileName(),
							ServerConfigMigrationService.PlannedTarget::content));
			Map<String, CommentedConfig> actual = targets.stream().collect(
					Collectors.toMap(
							ServerConfigMigrationService.MigrationTarget::fileName,
							ServerConfigMigrationService.MigrationTarget::current));
			return (fileName, path) -> allowsOverwrite(expected, actual, fileName, path);
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("无法基于上一次迁移备份判定玩家改动，改为只补齐仍为默认值的键：{}",
					previousLegacy, exception);
			return defaultOnlyGuard(targets);
		}
	}

	/**
	 * 兜底覆盖许可：无法判定玩家改动时，只允许覆盖仍等于规格默认值的键。
	 * <p>
	 * 这样最坏情况是整合包的新调校没能覆盖玩家已经改过的键（可由玩家手工同步），
	 * 而不是反过来把玩家的改动悄悄冲掉。
	 */
	private static ServerConfigMigrationPlanner.OverwriteGuard defaultOnlyGuard(
			List<ServerConfigMigrationService.MigrationTarget> targets) {
		Map<String, Map<String, Object>> defaults = new HashMap<>();
		Map<String, CommentedConfig> actual = new HashMap<>();
		for (ServerConfigMigrationService.MigrationTarget target : targets) {
			Map<String, Object> perFile = new HashMap<>();
			for (ModConfigSpec.ConfigValue<?> value : ConfigTraversal.configValues(target.spec())) {
				perFile.put(ConfigTraversal.path(value.getPath()), value.getDefault());
			}
			defaults.put(target.fileName(), perFile);
			actual.put(target.fileName(), target.current());
		}
		return (fileName, path) -> {
			Map<String, Object> perFile = defaults.get(fileName);
			CommentedConfig current = actual.get(fileName);
			if (perFile == null || current == null || !perFile.containsKey(path)) return true;
			if (!current.contains(path)) return true;
			return ConfigTraversal.sameValue(perFile.get(path), current.get(path));
		};
	}

	private static boolean allowsOverwrite(
			Map<String, CommentedConfig> expected,
			Map<String, CommentedConfig> actual,
			String fileName,
			String path) {
		CommentedConfig expectedContent = expected.get(fileName);
		CommentedConfig actualContent = actual.get(fileName);
		if (expectedContent == null || actualContent == null) return true;
		if (!expectedContent.contains(path) || !actualContent.contains(path)) return true;
		// 上一次迁移产物与当前磁盘值一致 → 玩家没动过这一键，允许整合包新值覆盖。
		return ConfigTraversal.sameValue(expectedContent.get(path), actualContent.get(path));
	}
}
