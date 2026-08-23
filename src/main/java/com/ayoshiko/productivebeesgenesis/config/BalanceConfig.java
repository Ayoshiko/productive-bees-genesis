package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Map;

/** Central policy resolver for all configurable balance rules. */
public final class BalanceConfig {

	static final BalancePreset DEFAULT_PRESET = BalancePreset.BASIC;
	static final boolean DEFAULT_CUSTOM_PRODUCTIVITY_EXCLUSIVE = true;
	static final boolean DEFAULT_CUSTOM_SPEED_EXCLUSIVE = true;
	static final boolean DEFAULT_CUSTOM_CENTRIFUGE_OUTPUT = false;
	static final boolean DEFAULT_CUSTOM_APIARY_BEE_GENES_AFFECT_WORK = true;
	static final int DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT = 4;
	static final int DEFAULT_CONFIGURED_STACK_UPGRADE_LIMIT = 8;
	static final boolean LEGACY_PRODUCTIVITY_EXCLUSIVE = false;
	static final boolean LEGACY_SPEED_EXCLUSIVE = false;
	static final boolean LEGACY_CENTRIFUGE_OUTPUT = true;
	static final boolean LEGACY_APIARY_BEE_GENES_AFFECT_WORK = false;

	private static final int BASIC_PB_UPGRADE_LIMIT = 4;
	static final int LEGACY_PB_UPGRADE_LIMIT = 8;
	private static final int BASIC_STACK_UPGRADE_LIMIT = 8;
	static final int LEGACY_STACK_UPGRADE_LIMIT = 16;

	private static volatile Rules activeRules = Rules.basic();
	private static volatile boolean initialized;

	private BalanceConfig() {
	}

	/**
	 * Refresh the immutable runtime snapshot after a server config load or reload.
	 *
	 * @param inheritNamedPreset when true, a transition from BASIC or
	 *                           PARADOX_INFINITY to CUSTOM copies all currently
	 *                           effective balance values into the custom fields
	 * @return true when custom backing values were updated and should be saved
	 */
	public static synchronized boolean refresh(boolean inheritNamedPreset) {
		BalancePreset preset = readPreset();
		CustomSettings configured = readCustomSettings();
		CustomSettings persisted = settingsToPersist(
				initialized,
				inheritNamedPreset,
				preset,
				activeRules.preset(),
				configured);
		boolean changed = writeCustomSettings(persisted);
		activeRules = resolve(
				preset,
				persisted.productivityTiersExclusive(),
				persisted.speedTiersExclusive(),
				persisted.centrifugeProductivityAffectsOutput(),
				persisted.apiaryBeeGenesAffectWork());
		initialized = true;
		return changed;
	}

	public static BalancePreset preset() {
		return activeRules.preset();
	}

	public static boolean productivityTiersExclusive() {
		return activeRules.productivityTiersExclusive();
	}

	public static boolean speedTiersExclusive() {
		return activeRules.speedTiersExclusive();
	}

	public static boolean centrifugeProductivityAffectsOutput() {
		return activeRules.centrifugeProductivityAffectsOutput();
	}

	/** Return whether mechanical apiaries obey each bee's behavior and weather-tolerance genes. */
	public static boolean apiaryBeeGenesAffectWork() {
		return activeRules.apiaryBeeGenesAffectWork();
	}

	/**
	 * Resolve a PB productivity/time upgrade cap without changing persisted counts.
	 * Profile limits apply only when a player installs another upgrade.
	 */
	public static int pbUpgradeLimit(int configured) {
		return pbUpgradeLimit(activeRules.preset(), configured);
	}

	static int pbUpgradeLimit(BalancePreset preset, int configured) {
		int value = Math.max(1, configured);
		return switch (preset) {
			case BASIC -> Math.min(value, BASIC_PB_UPGRADE_LIMIT);
			case PARADOX_INFINITY -> Math.max(value, LEGACY_PB_UPGRADE_LIMIT);
			case CUSTOM -> value;
		};
	}

	/** Resolve the centrifuge stack-upgrade cap for the selected profile. */
	public static int centrifugeStackLimit(int configured) {
		return centrifugeStackLimit(activeRules.preset(), configured);
	}

	static int centrifugeStackLimit(BalancePreset preset, int configured) {
		int value = Math.max(BASIC_STACK_UPGRADE_LIMIT, configured);
		return switch (preset) {
			case BASIC -> BASIC_STACK_UPGRADE_LIMIT;
			case PARADOX_INFINITY -> Math.max(value, LEGACY_STACK_UPGRADE_LIMIT);
			case CUSTOM -> value;
		};
	}

	static CustomSettings settingsForPreset(BalancePreset preset, CustomSettings configured) {
		if (preset == null || preset == BalancePreset.CUSTOM) return configured;
		Rules rules = resolve(
				preset,
				configured.productivityTiersExclusive(),
				configured.speedTiersExclusive(),
				configured.centrifugeProductivityAffectsOutput(),
				configured.apiaryBeeGenesAffectWork());
		return new CustomSettings(
				rules.productivityTiersExclusive(),
				rules.speedTiersExclusive(),
				rules.centrifugeProductivityAffectsOutput(),
				rules.apiaryBeeGenesAffectWork(),
				pbUpgradeLimit(preset, configured.apiaryProductivityLimit()),
				pbUpgradeLimit(preset, configured.apiaryTimeLimit()),
				pbUpgradeLimit(preset, configured.centrifugeProductivityLimit()),
				pbUpgradeLimit(preset, configured.centrifugeTimeLimit()),
				centrifugeStackLimit(preset, configured.centrifugeStackLimit()));
	}

	/**
	 * Keep named-profile values in the custom backing fields so an offline
	 * server edit from a named profile to CUSTOM inherits the last effective
	 * settings even though the previous JVM state no longer exists.
	 */
	static CustomSettings settingsToPersist(
			boolean wasInitialized,
			boolean inheritNamedPreset,
			BalancePreset selectedPreset,
			BalancePreset previousPreset,
			CustomSettings configured) {
		BalancePreset selected = selectedPreset == null ? DEFAULT_PRESET : selectedPreset;
		if (selected != BalancePreset.CUSTOM) {
			return settingsForPreset(selected, configured);
		}
		if (wasInitialized && inheritNamedPreset
				&& previousPreset != null && previousPreset != BalancePreset.CUSTOM) {
			return settingsForPreset(previousPreset, configured);
		}
		return configured;
	}

	/**
	 * Return whether installing {@code candidate} would introduce a conflict.
	 * Existing combinations are never normalized or removed after a profile switch.
	 */
	public static boolean canInstall(PbUpgradeType candidate, Map<PbUpgradeType, Integer> installed) {
		return canInstall(candidate, installed, activeRules);
	}

	static boolean canInstall(
			PbUpgradeType candidate, Map<PbUpgradeType, Integer> installed, Rules rules) {
		if (candidate == null || installed == null) return false;
		if (rules.productivityTiersExclusive() && isProductivity(candidate)) {
			for (PbUpgradeType type : PRODUCTIVITY_TYPES) {
				if (type != candidate && installed.getOrDefault(type, 0) > 0) return false;
			}
		}
		if (rules.speedTiersExclusive() && isSpeed(candidate)) {
			for (PbUpgradeType type : SPEED_TYPES) {
				if (type != candidate && installed.getOrDefault(type, 0) > 0) return false;
			}
		}
		return true;
	}

	static Rules resolve(
			BalancePreset preset,
			boolean customProductivityExclusive,
			boolean customSpeedExclusive,
			boolean customCentrifugeOutput,
			boolean customApiaryBeeGenesAffectWork) {
		BalancePreset safePreset = preset == null ? DEFAULT_PRESET : preset;
		return switch (safePreset) {
			case BASIC -> Rules.basic();
			case PARADOX_INFINITY -> Rules.paradoxInfinity();
			case CUSTOM -> new Rules(
					BalancePreset.CUSTOM,
					customProductivityExclusive,
					customSpeedExclusive,
					customCentrifugeOutput,
					customApiaryBeeGenesAffectWork);
		};
	}

	private static BalancePreset readPreset() {
		if (ModConfig.SERVER == null || ModConfig.SERVER.balancePreset == null) {
			return DEFAULT_PRESET;
		}
		try {
			BalancePreset preset = ModConfig.SERVER.balancePreset.get();
			return preset == null ? DEFAULT_PRESET : preset;
		} catch (RuntimeException ignored) {
			return DEFAULT_PRESET;
		}
	}

	private static boolean readBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
		if (value == null) return fallback;
		try {
			return value.get();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static int readInt(ModConfigSpec.IntValue value, int fallback) {
		if (value == null) return fallback;
		try {
			return value.get();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static CustomSettings readCustomSettings() {
		return new CustomSettings(
				readBoolean(ModConfig.SERVER.productivityUpgradeTiersExclusive,
						DEFAULT_CUSTOM_PRODUCTIVITY_EXCLUSIVE),
				readBoolean(ModConfig.SERVER.speedUpgradeTiersExclusive,
						DEFAULT_CUSTOM_SPEED_EXCLUSIVE),
				readBoolean(ModConfig.SERVER.centrifugeProductivityAffectsOutput,
						DEFAULT_CUSTOM_CENTRIFUGE_OUTPUT),
				readBoolean(ModConfig.SERVER.apiaryBeeGenesAffectWork,
						DEFAULT_CUSTOM_APIARY_BEE_GENES_AFFECT_WORK),
				readInt(ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount,
						DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT),
				readInt(ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount,
						DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT),
				readInt(ModConfig.SERVER.mekCentrifugePbUpgradeProductivityMaxCount,
						DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT),
				readInt(ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount,
						DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT),
				readInt(ModConfig.SERVER.mekCentrifugeMaxStackUpgrades,
						DEFAULT_CONFIGURED_STACK_UPGRADE_LIMIT));
	}

	private static boolean writeCustomSettings(CustomSettings settings) {
		boolean changed = false;
		changed |= setBoolean(ModConfig.SERVER.productivityUpgradeTiersExclusive,
				settings.productivityTiersExclusive());
		changed |= setBoolean(ModConfig.SERVER.speedUpgradeTiersExclusive,
				settings.speedTiersExclusive());
		changed |= setBoolean(ModConfig.SERVER.centrifugeProductivityAffectsOutput,
				settings.centrifugeProductivityAffectsOutput());
		changed |= setBoolean(ModConfig.SERVER.apiaryBeeGenesAffectWork,
				settings.apiaryBeeGenesAffectWork());
		changed |= setInt(ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount,
				settings.apiaryProductivityLimit());
		changed |= setInt(ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount,
				settings.apiaryTimeLimit());
		changed |= setInt(ModConfig.SERVER.mekCentrifugePbUpgradeProductivityMaxCount,
				settings.centrifugeProductivityLimit());
		changed |= setInt(ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount,
				settings.centrifugeTimeLimit());
		changed |= setInt(ModConfig.SERVER.mekCentrifugeMaxStackUpgrades,
				settings.centrifugeStackLimit());
		return changed;
	}

	private static boolean setBoolean(ModConfigSpec.BooleanValue value, boolean target) {
		if (value == null || readBoolean(value, target) == target) return false;
		value.set(target);
		return true;
	}

	private static boolean setInt(ModConfigSpec.IntValue value, int target) {
		if (value == null || readInt(value, target) == target) return false;
		value.set(target);
		return true;
	}

	private static final PbUpgradeType[] PRODUCTIVITY_TYPES = {
			PbUpgradeType.PRODUCTIVITY,
			PbUpgradeType.PRODUCTIVITY_2,
			PbUpgradeType.PRODUCTIVITY_3,
			PbUpgradeType.PRODUCTIVITY_4
	};

	private static final PbUpgradeType[] SPEED_TYPES = {
			PbUpgradeType.TIME,
			PbUpgradeType.TIME_2
	};

	private static boolean isProductivity(PbUpgradeType type) {
		return type == PbUpgradeType.PRODUCTIVITY
				|| type == PbUpgradeType.PRODUCTIVITY_2
				|| type == PbUpgradeType.PRODUCTIVITY_3
				|| type == PbUpgradeType.PRODUCTIVITY_4;
	}

	private static boolean isSpeed(PbUpgradeType type) {
		return type == PbUpgradeType.TIME || type == PbUpgradeType.TIME_2;
	}

	record Rules(
			BalancePreset preset,
			boolean productivityTiersExclusive,
			boolean speedTiersExclusive,
			boolean centrifugeProductivityAffectsOutput,
			boolean apiaryBeeGenesAffectWork) {

		static Rules basic() {
			return new Rules(BalancePreset.BASIC, true, true, false, true);
		}

		static Rules paradoxInfinity() {
			return new Rules(
					BalancePreset.PARADOX_INFINITY,
					LEGACY_PRODUCTIVITY_EXCLUSIVE,
					LEGACY_SPEED_EXCLUSIVE,
					LEGACY_CENTRIFUGE_OUTPUT,
					LEGACY_APIARY_BEE_GENES_AFFECT_WORK);
		}

		static Rules customDefaults() {
			return new Rules(
					BalancePreset.CUSTOM,
					DEFAULT_CUSTOM_PRODUCTIVITY_EXCLUSIVE,
					DEFAULT_CUSTOM_SPEED_EXCLUSIVE,
					DEFAULT_CUSTOM_CENTRIFUGE_OUTPUT,
					DEFAULT_CUSTOM_APIARY_BEE_GENES_AFFECT_WORK);
		}
	}

	record CustomSettings(
			boolean productivityTiersExclusive,
			boolean speedTiersExclusive,
			boolean centrifugeProductivityAffectsOutput,
			boolean apiaryBeeGenesAffectWork,
			int apiaryProductivityLimit,
			int apiaryTimeLimit,
			int centrifugeProductivityLimit,
			int centrifugeTimeLimit,
			int centrifugeStackLimit) {
	}
}
