package com.ayoshiko.productivebeesgenesis.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable product-mod classification for one bee type. */
public record BeeProductModProfile(String primaryModId, List<String> allModIds) {

	public BeeProductModProfile {
		Objects.requireNonNull(primaryModId, "primaryModId");
		allModIds = List.copyOf(allModIds);
		if (primaryModId.isBlank() || !allModIds.contains(primaryModId)) {
			throw new IllegalArgumentException("Primary product mod must be present in allModIds");
		}
	}

	/**
	 * Builds a stable profile in recipe order. The first item namespace is the
	 * grouping key, while every item and fluid namespace remains searchable.
	 */
	public static BeeProductModProfile create(
			List<String> itemModIds, String fluidModId, String fallbackModId) {
		LinkedHashSet<String> all = new LinkedHashSet<>();
		if (itemModIds != null) {
			for (String modId : itemModIds) {
				addNormalized(all, modId);
			}
		}
		addNormalized(all, fluidModId);
		if (all.isEmpty()) {
			addNormalized(all, fallbackModId);
		}
		if (all.isEmpty()) {
			throw new IllegalArgumentException("fallbackModId must not be blank");
		}

		List<String> ordered = List.copyOf(all);
		return new BeeProductModProfile(ordered.get(0), ordered);
	}

	public static BeeProductModProfile fallback(String fallbackModId) {
		return create(List.of(), null, fallbackModId);
	}

	private static void addNormalized(LinkedHashSet<String> target, String modId) {
		if (modId == null) {
			return;
		}
		String normalized = modId.trim();
		if (!normalized.isEmpty()) {
			target.add(normalized);
		}
	}
}
