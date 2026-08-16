package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.fml.config.ModConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Preserves pre-balance-config behavior when NeoForge corrects an old world config. */
public final class BalanceConfigCompatibility {

	private static final System.Logger LOGGER =
			System.getLogger("ProductiveBeesGenesis/BalanceCompatibility");
	private static final Duration CORRECTION_WINDOW = Duration.ofMinutes(5);
	private static final String PROFILE_KEY = "balanceProfile";
	private static final String APIARY_PRODUCTIVITY_LIMIT =
			"mek_apiary.pb_upgrade.productivityMaxCount";
	private static final String APIARY_TIME_LIMIT =
			"mek_apiary.pb_upgrade.timeMaxCount";
	private static final String CENTRIFUGE_PRODUCTIVITY_LIMIT =
			"mek_centrifuge.pb_upgrade.productivityMaxCount";
	private static final String CENTRIFUGE_TIME_LIMIT =
			"mek_centrifuge.pb_upgrade.timeMaxCount";
	private static final String CENTRIFUGE_STACK_LIMIT =
			"mek_centrifuge.me_upgrade.maxStackUpgrades";

	private BalanceConfigCompatibility() {
	}

	/**
	 * Old configs have no balance profile. NeoForge creates a numbered correction backup
	 * immediately before adding missing spec keys, which lets us distinguish an upgraded
	 * world from a new installation after correction has completed.
	 */
	public static boolean migrateLegacyConfig(ModConfig config) {
		if (config == null) return false;
		Path currentPath = config.getFullPath();
		Path backupPath = recentLegacyBackup(currentPath);
		if (backupPath == null) return false;
		try {
			if (com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.balancePreset.get()
					!= BalancePreset.BASIC) {
				return false;
			}
			CommentedConfig legacy = parse(backupPath);
			var server = com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER;
			server.balancePreset.set(BalancePreset.CUSTOM);
			server.productivityUpgradeTiersExclusive.set(BalanceConfig.LEGACY_PRODUCTIVITY_EXCLUSIVE);
			server.speedUpgradeTiersExclusive.set(BalanceConfig.LEGACY_SPEED_EXCLUSIVE);
			server.centrifugeProductivityAffectsOutput.set(BalanceConfig.LEGACY_CENTRIFUGE_OUTPUT);

			// Restore the exact legacy limits, including user-tuned values. A legacy config
			// from before these keys existed receives the old release default instead.
			restoreLegacyLimit(legacy, APIARY_PRODUCTIVITY_LIMIT,
					server.apiaryPbUpgradeProductivityMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT, 1, 64);
			restoreLegacyLimit(legacy, APIARY_TIME_LIMIT,
					server.apiaryPbUpgradeTimeMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT, 1, 64);
			restoreLegacyLimit(legacy, CENTRIFUGE_PRODUCTIVITY_LIMIT,
					server.mekCentrifugePbUpgradeProductivityMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT, 1, 64);
			restoreLegacyLimit(legacy, CENTRIFUGE_TIME_LIMIT,
					server.mekCentrifugePbUpgradeTimeMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT, 1, 64);
			restoreLegacyLimit(legacy, CENTRIFUGE_STACK_LIMIT,
					server.mekCentrifugeMaxStackUpgrades,
					BalanceConfig.LEGACY_STACK_UPGRADE_LIMIT, 8, 32);

			LOGGER.log(System.Logger.Level.INFO,
					"Preserved legacy balance settings for server config {0}", currentPath);
			return true;
		} catch (IOException | RuntimeException exception) {
			LOGGER.log(System.Logger.Level.WARNING,
					"Unable to preserve legacy balance settings for " + currentPath, exception);
			return false;
		}
	}

	static Path recentLegacyBackup(Path currentPath) {
		if (currentPath == null || !Files.isRegularFile(currentPath)) return null;
		try {
			CommentedConfig current = parse(currentPath);
			if (!current.contains(PROFILE_KEY)) return null;
			List<Path> candidates = correctionBackups(currentPath);
			candidates.sort(Comparator.comparingLong(BalanceConfigCompatibility::lastModifiedMillis)
					.reversed());
			long currentModified = Files.getLastModifiedTime(currentPath).toMillis();
			for (Path candidate : candidates) {
				long correctionDelay = currentModified - Files.getLastModifiedTime(candidate).toMillis();
				if (correctionDelay < 0 || correctionDelay > CORRECTION_WINDOW.toMillis()) continue;
				CommentedConfig backup = parse(candidate);
				if (!backup.contains(PROFILE_KEY)) return candidate;
			}
			return null;
		} catch (IOException | RuntimeException exception) {
			return null;
		}
	}

	private static List<Path> correctionBackups(Path currentPath) throws IOException {
		Path fileNamePath = currentPath.getFileName();
		Path parent = currentPath.getParent();
		if (fileNamePath == null || parent == null) return List.of();
		String fileName = fileNamePath.toString();
		int extensionStart = fileName.lastIndexOf('.');
		String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
		String extension = extensionStart < 0 ? "" : fileName.substring(extensionStart);
		String prefix = baseName + "-";
		String suffix = extension + ".bak";
		List<Path> candidates = new ArrayList<>();
		try (var entries = Files.list(parent)) {
			entries.filter(Files::isRegularFile).forEach(candidate -> {
				String name = candidate.getFileName().toString();
				if (!name.startsWith(prefix) || !name.endsWith(suffix)) return;
				String index = name.substring(prefix.length(), name.length() - suffix.length());
				if (!index.isEmpty() && index.chars().allMatch(Character::isDigit)) candidates.add(candidate);
			});
		}
		return candidates;
	}

	private static long lastModifiedMillis(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException ignored) {
			return Long.MIN_VALUE;
		}
	}

	static Path firstCorrectionBackup(Path currentPath) {
		Path fileNamePath = currentPath == null ? null : currentPath.getFileName();
		Path parent = currentPath == null ? null : currentPath.getParent();
		if (fileNamePath == null || parent == null) return null;
		String fileName = fileNamePath.toString();
		int extensionStart = fileName.lastIndexOf('.');
		String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
		String extension = extensionStart < 0 ? "" : fileName.substring(extensionStart);
		return parent.resolve(baseName + "-1" + extension + ".bak");
	}

	static boolean containsPath(Path configPath, String path) throws IOException {
		return parse(configPath).contains(path);
	}

	static int legacyLimit(
			Path configPath,
			String path,
			int legacyDefault,
			int minimum,
			int maximum) throws IOException {
		return legacyLimitFromConfig(parse(configPath), path, legacyDefault, minimum, maximum);
	}

	private static CommentedConfig parse(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return new TomlParser().parse(reader);
		}
	}

	private static void restoreLegacyLimit(
			CommentedConfig legacy,
			String path,
			net.neoforged.neoforge.common.ModConfigSpec.IntValue value,
			int legacyDefault,
			int minimum,
			int maximum) {
		value.set(legacyLimitFromConfig(legacy, path, legacyDefault, minimum, maximum));
	}

	private static int legacyLimitFromConfig(
			CommentedConfig legacy,
			String path,
			int legacyDefault,
			int minimum,
			int maximum) {
		int restored = legacyDefault;
		if (legacy != null && legacy.contains(path)) {
			Object raw = legacy.get(path);
			if (raw instanceof Number number) {
				long candidate = number.longValue();
				if (candidate >= minimum && candidate <= maximum) restored = (int) candidate;
			}
		}
		return restored;
	}
}
