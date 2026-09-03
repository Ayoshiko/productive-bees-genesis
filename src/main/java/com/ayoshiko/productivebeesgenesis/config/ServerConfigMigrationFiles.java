package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 配置迁移的文件事务、回滚和崩溃恢复实现。 */
final class ServerConfigMigrationFiles {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/ConfigMigrationFiles");
	private static final String MARKER_FILE_NAME =
			".productivebeesgenesis-server-migration.pending";
	private static final String STAGE_SUFFIX = ".migration.tmp";
	private static final String ROLLBACK_SUFFIX = ".migration.rollback";
	private static final String ABSENT_SUFFIX = ".migration.absent";

	private ServerConfigMigrationFiles() {
	}

	static void commitFiles(
			Path directory,
			Path legacyPath,
			Path backupPath,
			ServerConfigMigrationService.MigrationPlan plan,
			ReplacementObserver observer) throws IOException {
		Path marker = directory.resolve(MARKER_FILE_NAME);
		Map<Path, RollbackState> rollbacks = new LinkedHashMap<>();
		Map<Path, Path> stages = new LinkedHashMap<>();
		try {
			if (Files.exists(backupPath)) {
				throw new IOException("迁移备份已存在，拒绝覆盖: " + backupPath);
			}
			for (ServerConfigMigrationService.PlannedTarget target : plan.targets()) {
				Path destination = target.target().path();
				Path stage = stagePath(destination);
				writeConfig(stage, target.content());
				CommentedConfig staged = parse(stage);
				if (!target.target().spec().isCorrect(staged)) {
					throw new IOException("临时配置校验失败: " + stage);
				}
				stages.put(destination, stage);
			}
			Files.writeString(marker, "pending\n", StandardCharsets.US_ASCII,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			for (Path destination : stages.keySet()) {
				if (Files.isRegularFile(destination)) {
					Path rollback = rollbackPath(destination);
					Files.copy(destination, rollback,
							StandardCopyOption.REPLACE_EXISTING,
							StandardCopyOption.COPY_ATTRIBUTES);
					rollbacks.put(destination, new RollbackState(rollback, false));
				} else if (Files.exists(destination)) {
					throw new IOException("新配置目标不是常规文件: " + destination);
				} else {
					Path absentMarker = absentPath(destination);
					Files.writeString(absentMarker, "absent\n", StandardCharsets.US_ASCII,
							StandardOpenOption.CREATE_NEW);
					rollbacks.put(destination, new RollbackState(absentMarker, true));
				}
			}
			int index = 0;
			for (Map.Entry<Path, Path> entry : stages.entrySet()) {
				observer.beforeReplace(index++, entry.getKey());
				moveReplace(entry.getValue(), entry.getKey());
			}
			moveWithoutReplace(legacyPath, backupPath);
		} catch (IOException exception) {
			if (!Files.exists(backupPath) || Files.exists(legacyPath)) {
				restoreRollbacks(rollbacks);
			}
			cleanup(marker, stages.values(), rollbackPaths(rollbacks));
			throw exception;
		}
		cleanup(marker, stages.values(), rollbackPaths(rollbacks));
	}

	static void recoverInterruptedMigration(
			Path directory,
			List<Path> destinations,
			Path legacyPath,
			Path backupPath) throws IOException {
		Path marker = directory.resolve(MARKER_FILE_NAME);
		if (!Files.exists(marker)) return;
		List<Path> stages = new ArrayList<>();
		Map<Path, RollbackState> rollbacks = new LinkedHashMap<>();
		for (Path destination : destinations) {
			stages.add(stagePath(destination));
			Path rollback = rollbackPath(destination);
			Path absentMarker = absentPath(destination);
			if (Files.exists(rollback)) {
				rollbacks.put(destination, new RollbackState(rollback, false));
			} else if (Files.exists(absentMarker)) {
				rollbacks.put(destination, new RollbackState(absentMarker, true));
			}
		}
		if (!Files.exists(backupPath) || Files.exists(legacyPath)) {
			restoreRollbacks(rollbacks);
			LOGGER.warn("检测到未完成的配置迁移，已恢复迁移前的新配置文件");
		}
		cleanup(marker, stages, rollbackPaths(rollbacks));
	}

	static CommentedConfig parse(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return new TomlParser().parse(reader);
		}
	}

	private static void restoreRollbacks(Map<Path, RollbackState> rollbacks) throws IOException {
		IOException failure = null;
		for (Map.Entry<Path, RollbackState> entry : rollbacks.entrySet()) {
			try {
				if (entry.getValue().wasAbsent()) {
					Files.deleteIfExists(entry.getKey());
				} else {
					Files.copy(entry.getValue().path(), entry.getKey(),
							StandardCopyOption.REPLACE_EXISTING,
							StandardCopyOption.COPY_ATTRIBUTES);
				}
			} catch (IOException exception) {
				if (failure == null) failure = exception;
				else failure.addSuppressed(exception);
			}
		}
		if (failure != null) throw failure;
	}

	private static List<Path> rollbackPaths(Map<Path, RollbackState> rollbacks) {
		return rollbacks.values().stream().map(RollbackState::path).toList();
	}

	private static void writeConfig(Path path, CommentedConfig config) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			new TomlWriter().write(config, writer);
		}
	}

	private static void moveReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void moveWithoutReplace(Path source, Path target) throws IOException {
		if (Files.exists(target)) throw new IOException("目标文件已存在: " + target);
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target);
		}
	}

	private static void cleanup(Path marker, Iterable<Path> stages, Iterable<Path> rollbacks) {
		deleteQuietly(marker);
		for (Path stage : stages) deleteQuietly(stage);
		for (Path rollback : rollbacks) deleteQuietly(rollback);
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException exception) {
			LOGGER.warn("无法删除配置迁移临时文件：{}", path, exception);
		}
	}

	private static Path stagePath(Path target) {
		return target.resolveSibling("." + target.getFileName() + STAGE_SUFFIX);
	}

	private static Path rollbackPath(Path target) {
		return target.resolveSibling("." + target.getFileName() + ROLLBACK_SUFFIX);
	}

	private static Path absentPath(Path target) {
		return target.resolveSibling("." + target.getFileName() + ABSENT_SUFFIX);
	}

	private record RollbackState(Path path, boolean wasAbsent) {
	}

	@FunctionalInterface
	interface ReplacementObserver {
		ReplacementObserver NONE = (index, target) -> { };

		void beforeReplace(int index, Path target) throws IOException;
	}
}
