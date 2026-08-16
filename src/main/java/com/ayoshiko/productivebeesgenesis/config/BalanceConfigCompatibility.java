package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.fml.config.ModConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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
	 * Old configs have no balance profile. NeoForge creates a fresh -1 backup
	 * immediately before adding missing spec keys, which lets us distinguish an
	 * upgraded world from a new installation after correction has completed.
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

			// Existing values, including user-tuned lower limits, remain untouched.
			// Only configs old enough to lack a limit receive the pre-change default.
			setLegacyDefaultIfMissing(legacy, APIARY_PRODUCTIVITY_LIMIT,
					server.apiaryPbUpgradeProductivityMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT);
			setLegacyDefaultIfMissing(legacy, APIARY_TIME_LIMIT,
					server.apiaryPbUpgradeTimeMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT);
			setLegacyDefaultIfMissing(legacy, CENTRIFUGE_PRODUCTIVITY_LIMIT,
					server.mekCentrifugePbUpgradeProductivityMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT);
			setLegacyDefaultIfMissing(legacy, CENTRIFUGE_TIME_LIMIT,
					server.mekCentrifugePbUpgradeTimeMaxCount,
					BalanceConfig.LEGACY_PB_UPGRADE_LIMIT);
			setLegacyDefaultIfMissing(legacy, CENTRIFUGE_STACK_LIMIT,
					server.mekCentrifugeMaxStackUpgrades,
					BalanceConfig.LEGACY_STACK_UPGRADE_LIMIT);

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
		Path backupPath = firstCorrectionBackup(currentPath);
		if (backupPath == null || !Files.isRegularFile(backupPath)) return null;
		try {
			long correctionDelay = Files.getLastModifiedTime(currentPath).toMillis()
					- Files.getLastModifiedTime(backupPath).toMillis();
			if (correctionDelay < 0 || correctionDelay > CORRECTION_WINDOW.toMillis()) return null;
			CommentedConfig current = parse(currentPath);
			CommentedConfig backup = parse(backupPath);
			return current.contains(PROFILE_KEY) && !backup.contains(PROFILE_KEY)
					? backupPath : null;
		} catch (IOException | RuntimeException exception) {
			return null;
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

	private static CommentedConfig parse(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return new TomlParser().parse(reader);
		}
	}

	private static void setLegacyDefaultIfMissing(
			CommentedConfig legacy,
			String path,
			net.neoforged.neoforge.common.ModConfigSpec.IntValue value,
			int legacyDefault) {
		if (!legacy.contains(path)) value.set(legacyDefault);
	}
}
