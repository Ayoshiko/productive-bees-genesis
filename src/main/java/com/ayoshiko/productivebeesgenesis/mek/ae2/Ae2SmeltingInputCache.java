package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-host bounded cache for Mekanism SMELTING input queries.
 * <p>
 * The cache keeps the complete {@link AEItemKey}, including components, as its key.
 * It does not retain a {@link Level} or recipe object, and recipe reloads are detected
 * through {@link ProductiveBeesGenesis#RECIPE_VERSION}.
 * <p>
 * All methods are synchronized because grid callbacks may invalidate the cache while
 * the server tick is consulting it. A cache miss performs the Mekanism query while
 * holding the small per-host lock; this prevents duplicate expensive lookups for the
 * same key and never serializes different machines.
 */
final class Ae2SmeltingInputCache {
	/** Hard bound preventing a long-lived loaded machine from retaining every network item type. */
	static final int MAX_ENTRIES = 1_024;

	private final LinkedHashMap<AEItemKey, Boolean> entries = new LinkedHashMap<>(64, 0.75f, true);
	private long observedRecipeVersion = Long.MIN_VALUE;

	/**
	 * Checks whether Mekanism has a SMELTING recipe for the supplied AE key.
	 * <p>
	 * The read-only stack returned by AE2 is never modified or retained by this class.
	 * Errors are logged with throttling and treated as a miss so a broken optional
	 * recipe integration cannot make the centrifuge pull an unsafe item.
	 */
	synchronized boolean contains(Level level, AEItemKey key) {
		if (level == null || key == null) return false;
		refreshRecipeVersion();
		Boolean cached = entries.get(key);
		if (cached != null) return cached;

		boolean result;
		try {
			ItemStack input = key.getReadOnlyStack();
			result = MekanismRecipeType.SMELTING.getInputCache().containsInput(level, input);
		} catch (LinkageError | RuntimeException error) {
			LogThrottle.warn("ae2_smelting_input_cache",
					"AE2 SMELTING 配方查询异常，拒绝本次候选 key={}: {}", key, error.toString());
			result = false;
		}
		if (entries.size() >= MAX_ENTRIES) {
			Iterator<AEItemKey> iterator = entries.keySet().iterator();
			if (iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			}
		}
		entries.put(key, result);
		return result;
	}

	/** Clears all cached results, for example after an AE2 grid topology change. */
	synchronized void clear() {
		entries.clear();
		observedRecipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
	}

	/** Returns the current number of cached key results, for diagnostics and tests. */
	synchronized int size() {
		refreshRecipeVersion();
		return entries.size();
	}

	private void refreshRecipeVersion() {
		long currentVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (observedRecipeVersion == currentVersion) return;
		entries.clear();
		observedRecipeVersion = currentVersion;
	}
}
