package com.ayoshiko.productivebeesgenesis.config;

/**
 * Named balance profiles exposed by the server configuration.
 *
 * <p>The enum names remain ASCII/stable for TOML and network compatibility;
 * translations provide the Chinese display names.</p>
 */
public enum BalancePreset {
	/** Conservative defaults intended for general-purpose packs. */
	BASIC,
	/** Legacy/high-output behaviour retained for existing installations. */
	PARADOX_INFINITY,
	/** Use the individual rule values from the balance section. */
	CUSTOM;

	private static final String TRANSLATION_PREFIX =
			"productivebeesgenesis.configuration.balance.profile.";

	public String getTranslationKey() {
		return TRANSLATION_PREFIX + name();
	}

	public String getTooltipKey() {
		return getTranslationKey() + ".tooltip";
	}

}
