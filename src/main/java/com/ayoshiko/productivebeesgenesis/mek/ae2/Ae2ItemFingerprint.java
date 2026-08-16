package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable, component-aware serialization for configured AE2 item keys. */
public final class Ae2ItemFingerprint {

	private Ae2ItemFingerprint() {
	}

	public static String encode(AEItemKey key, HolderLookup.Provider registries) {
		if (key == null || registries == null) return "";
		return key.toTag(registries).toString();
	}

	@Nullable
	public static AEItemKey decode(String fingerprint, HolderLookup.Provider registries) {
		if (fingerprint == null || fingerprint.isBlank() || registries == null
				|| fingerprint.charAt(0) != '{') {
			return null;
		}
		try {
			return AEItemKey.fromTag(registries, TagParser.parseTag(fingerprint));
		} catch (CommandSyntaxException | RuntimeException ignored) {
			return null;
		}
	}

	/** Compatibility fallback for direct entries written before component-aware fingerprints. */
	public static boolean matchesLegacy(AEItemKey key, String fingerprint) {
		return key != null && fingerprint != null && fingerprint.equals(key.toString());
	}

	/** Resolves new SNBT fingerprints without scanning and old fingerprints with one snapshot pass. */
	public static Map<String, AEItemKey> resolve(List<Ae2InputFilter.DirectEntry> entries,
			KeyCounter cachedInventory, HolderLookup.Provider registries) {
		Map<String, AEItemKey> resolved = null;
		Set<String> legacy = null;
		for (Ae2InputFilter.DirectEntry entry : entries) {
			if (entry.key() != null) continue;
			AEItemKey decoded = decode(entry.fingerprint(), registries);
			if (decoded != null) {
				if (resolved == null) resolved = new HashMap<>();
				resolved.put(entry.fingerprint(), decoded);
			} else {
				if (legacy == null) legacy = new HashSet<>();
				legacy.add(entry.fingerprint());
			}
		}
		if (legacy == null || legacy.isEmpty() || cachedInventory == null) {
			return resolved == null ? Map.of() : resolved;
		}
		for (var stack : cachedInventory) {
			if (!(stack.getKey() instanceof AEItemKey itemKey)) continue;
			String fingerprint = itemKey.toString();
			if (legacy.contains(fingerprint)) {
				if (resolved == null) resolved = new HashMap<>();
				resolved.put(fingerprint, itemKey);
			}
		}
		return resolved == null ? Map.of() : resolved;
	}
}
