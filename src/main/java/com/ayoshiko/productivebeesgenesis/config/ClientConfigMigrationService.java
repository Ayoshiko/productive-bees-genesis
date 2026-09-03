package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.neoforged.neoforge.common.ModConfigSpec;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 将旧窗口 saveName 迁移到带 window_ 前缀的新配置键。 */
public final class ClientConfigMigrationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/ClientConfigMigration");
	private static final String WINDOW_PREFIX = "window_positions";
	private static final String TEMP_SUFFIX = ".window-migration.tmp";
	private static final Duration CORRECTION_BACKUP_WINDOW = Duration.ofMinutes(5);
	private static final List<WindowMapping> WINDOW_MAPPINGS = List.of(
			new WindowMapping("pb_upgrade", "window_pb_upgrade"),
			new WindowMapping("ae_input", "window_ae_input"),
			new WindowMapping("feeder", "window_feeder"),
			new WindowMapping("multi_fluid_tanks", "window_multi_fluid_tanks"));

	private ClientConfigMigrationService() {
	}

	/**
	 * 在 CLIENT 配置加载后迁移旧窗口位置。
	 * <p>
	 * 新键逐字段仍为默认值时才复制旧值，已存在的新值始终优先。写回使用临时文件和原子替换，
	 * 并保留旧 saveName，确保回退到旧版本时窗口位置仍可读取。
	 */
	public static synchronized void onConfigLoading(net.neoforged.fml.config.ModConfig config) {
		if (config == null || config.getType() != net.neoforged.fml.config.ModConfig.Type.CLIENT
				|| !ProductiveBeesGenesisConfigIds.MOD_ID.equals(config.getModId())
				|| config.getSpec() != ModConfig.CLIENT_SPEC) {
			return;
		}

		Path path;
		try {
			path = config.getFullPath();
		} catch (IllegalStateException exception) {
			LOGGER.debug("客户端配置没有本地路径，跳过窗口位置迁移");
			return;
		}
		if (!Files.isRegularFile(path)) return;

		try {
			CommentedConfig disk = legacySource(path);
			if (disk == null) return;
			CommentedConfig loaded = config.getLoadedConfig().config();
			MigrationResult result = migrateWindowPositions(disk, loaded);
			if (result.copiedValues() == 0) return;

			writeAtomically(path, loaded);
			ModConfig.CLIENT_SPEC.afterReload();
			LOGGER.info("客户端窗口位置迁移完成：复制 {} 个字段，忽略 {} 个非法字段，文件 {}",
					result.copiedValues(), result.invalidValues(), path);
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("客户端窗口位置迁移失败，旧配置文件保留：{}", path, exception);
		}
	}

	/**
	 * NeoForge 可能在纠正未知键后把旧文件保存为 numbered .toml.bak；优先读取当前文件，
	 * 当前文件没有旧窗口键时再读取紧邻本次纠正的备份，避免使用历史备份覆盖用户新设置。
	 */
	private static CommentedConfig legacySource(Path currentPath) throws IOException {
		CommentedConfig current = parse(currentPath);
		if (containsLegacyWindowPositions(current)) return current;
		Path backup = recentCorrectionBackup(currentPath);
		if (backup == null) return null;
		CommentedConfig candidate = parse(backup);
		if (!containsLegacyWindowPositions(candidate)) return null;
		LOGGER.info("从 NeoForge 配置校正备份读取旧窗口位置：{}", backup);
		return candidate;
	}

	private static boolean containsLegacyWindowPositions(CommentedConfig config) {
		for (WindowMapping mapping : WINDOW_MAPPINGS) {
			for (Field field : Field.values()) {
				if (config.contains(path(mapping.legacyName(), field.key()))) return true;
			}
		}
		return false;
	}

	static Path recentCorrectionBackup(Path currentPath) throws IOException {
		Path fileNamePath = currentPath == null ? null : currentPath.getFileName();
		Path parent = currentPath == null ? null : currentPath.getParent();
		if (fileNamePath == null || parent == null || !Files.isRegularFile(currentPath)) return null;
		String fileName = fileNamePath.toString();
		int extensionStart = fileName.lastIndexOf('.');
		String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
		String extension = extensionStart < 0 ? "" : fileName.substring(extensionStart);
		String prefix = baseName + "-";
		String suffix = extension + ".bak";
		long currentModified = Files.getLastModifiedTime(currentPath).toMillis();
		List<Path> candidates = new ArrayList<>();
		try (var entries = Files.list(parent)) {
			entries.filter(Files::isRegularFile).forEach(candidate -> {
				String name = candidate.getFileName().toString();
				if (!name.startsWith(prefix) || !name.endsWith(suffix)) return;
				String index = name.substring(prefix.length(), name.length() - suffix.length());
				if (!index.isEmpty() && index.chars().allMatch(Character::isDigit)) candidates.add(candidate);
			});
		}
		candidates.sort(Comparator.comparingLong(ClientConfigMigrationService::lastModifiedMillis)
				.reversed());
		for (Path candidate : candidates) {
			long delay = currentModified - lastModifiedMillis(candidate);
			if (delay >= 0 && delay <= CORRECTION_BACKUP_WINDOW.toMillis()) return candidate;
		}
		return null;
	}

	private static long lastModifiedMillis(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return Long.MIN_VALUE;
		}
	}

	/**
	 * 纯内存迁移逻辑，供单元测试验证兼容规则。
	 *
	 * @param disk   从磁盘解析的配置，可能只包含旧键
	 * @param loaded 当前 NeoForge 已加载的配置对象
	 */
	static MigrationResult migrateWindowPositions(CommentedConfig disk, CommentedConfig loaded) {
		Objects.requireNonNull(disk, "disk");
		Objects.requireNonNull(loaded, "loaded");
		int copied = 0;
		int invalid = 0;
		for (WindowMapping mapping : WINDOW_MAPPINGS) {
			for (Field field : Field.values()) {
				String oldPath = path(mapping.legacyName(), field.key());
				String newPath = path(mapping.currentName(), field.key());
				if (!disk.contains(oldPath)) continue;
				if (!isDefault(loaded.get(newPath), field)) continue;
				Object value = normalize(disk.get(oldPath), field);
				if (value == null) {
					invalid++;
					continue;
				}
				loaded.set(newPath, value);
				// 保留旧键，允许用户降级到仍使用旧 saveName 的版本。
				loaded.set(oldPath, value);
				copied++;
			}
		}
		return new MigrationResult(copied, invalid);
	}

	private static boolean isDefault(Object value, Field field) {
		if (value == null) return true;
		return switch (field) {
			case X, Y -> value instanceof Number number && number.longValue() == Integer.MAX_VALUE;
			case PINNED -> Boolean.FALSE.equals(value);
		};
	}

	private static Object normalize(Object value, Field field) {
		return switch (field) {
			case X, Y -> normalizeInteger(value);
			case PINNED -> value instanceof Boolean ? value : null;
		};
	}

	private static Integer normalizeInteger(Object value) {
		if (!(value instanceof Number number)) return null;
		long candidate = number.longValue();
		return candidate >= Integer.MIN_VALUE && candidate <= Integer.MAX_VALUE
				&& number.doubleValue() == candidate ? (int) candidate : null;
	}

	private static String path(String windowName, String field) {
		return WINDOW_PREFIX + "." + windowName + "." + field;
	}

	private static CommentedConfig parse(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return new TomlParser().parse(reader);
		}
	}

	private static void writeAtomically(Path path, CommentedConfig config) throws IOException {
		Path temporary = path.resolveSibling("." + path.getFileName() + TEMP_SUFFIX);
		try {
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE)) {
				new TomlWriter().write(config, writer);
			}
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private enum Field {
		X("x"), Y("y"), PINNED("pinned");

		private final String key;

		Field(String key) {
			this.key = key;
		}

		String key() {
			return key;
		}
	}

	private record WindowMapping(String legacyName, String currentName) {
	}

	record MigrationResult(int copiedValues, int invalidValues) {
	}

	/** 避免迁移服务为读取主类常量而触发不必要的模组初始化。 */
	private static final class ProductiveBeesGenesisConfigIds {
		private static final String MOD_ID = "productivebeesgenesis";

		private ProductiveBeesGenesisConfigIds() {
		}
	}
}
