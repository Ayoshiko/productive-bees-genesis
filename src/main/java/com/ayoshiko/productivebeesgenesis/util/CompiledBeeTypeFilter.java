package com.ayoshiko.productivebeesgenesis.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, normalized bee-type filter used by the myriad creations cache.
 * This class deliberately has no Minecraft or NeoForge dependencies so the
 * configuration semantics can be verified with plain JVM tests.
 */
public final class CompiledBeeTypeFilter {

	private enum Mode {
		DISABLED,
		BLACKLIST,
		WHITELIST
	}

	private final Mode mode;
	private final Set<String> beeTypeIds;

	private CompiledBeeTypeFilter(Mode mode, Set<String> beeTypeIds) {
		this.mode = mode;
		this.beeTypeIds = beeTypeIds;
	}

	/** Compiles a config mode and list into one reusable predicate. */
	public static CompiledBeeTypeFilter compile(String modeName, Collection<? extends String> entries) {
		Mode mode = modeName == null
				? Mode.DISABLED
				: Mode.valueOf(modeName.trim().toUpperCase(Locale.ROOT));
		if (mode == Mode.DISABLED || entries == null || entries.isEmpty()) {
			return new CompiledBeeTypeFilter(mode, Set.of());
		}

		Set<String> normalized = new HashSet<>(entries.size() * 2);
		for (String entry : entries) {
			if (entry == null) {
				continue;
			}
			String trimmed = entry.trim();
			if (!trimmed.isEmpty()) {
				normalized.add(trimmed);
			}
		}
		return new CompiledBeeTypeFilter(mode, Set.copyOf(normalized));
	}

	/** Returns whether the given registered bee type is convertible. */
	public boolean allows(String beeTypeId) {
		if (mode == Mode.DISABLED) {
			return true;
		}
		boolean listed = beeTypeIds.contains(beeTypeId);
		return mode == Mode.BLACKLIST ? !listed : listed;
	}

	public String modeName() {
		return mode.name();
	}

	public int entryCount() {
		return beeTypeIds.size();
	}

	public boolean isEmptyWhitelist() {
		return mode == Mode.WHITELIST && beeTypeIds.isEmpty();
	}
}
