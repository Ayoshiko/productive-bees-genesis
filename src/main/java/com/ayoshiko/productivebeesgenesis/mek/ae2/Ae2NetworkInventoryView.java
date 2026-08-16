package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/** Per-machine, per-game-tick view of stock that AE2 can actually extract. */
public final class Ae2NetworkInventoryView {

	private Ae2NetworkInventoryView() {
	}

	public static long visibleAmount(Ae2OutputStateHolder holder, long gameTick,
			KeyCounter cachedInventory, MEStorage network, AEItemKey key, long cap,
			IActionSource source) {
		if (holder == null || cachedInventory == null || key == null || cap <= 0L) return 0L;

		TickCache cache = getTickCache(holder, gameTick, network);
		long visible = cache.amounts.getLong(key);
		if (visible < 0L) {
			long reported = Math.max(0L, cachedInventory.get(key));
			long simulated = 0L;
			// AE2LT probes configured keys even when the cached counter is positive:
			// special/infinite storage may report a finite placeholder that is lower than
			// the amount the network can actually extract. The per-tick cache above keeps
			// GUI sync and input pulling from repeating this probe in the same game tick.
			if (network != null) {
				try {
					simulated = Math.max(0L,
							network.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source));
				} catch (LinkageError | RuntimeException ignored) {
					// The cached inventory is still a safe fallback for incompatible external storages.
				}
			}
			// Cache the uncapped result: GUI and pulling may request different caps in one tick.
			visible = Ae2VisibleStockMath.merge(reported, simulated, Long.MAX_VALUE);
			cache.amounts.put(key, visible);
		}
		return Ae2VisibleStockMath.merge(visible, 0L, cap);
	}

	private static TickCache getTickCache(Ae2OutputStateHolder holder, long gameTick, MEStorage network) {
		Object cached = holder.getInputInventoryViewCache();
		if (cached instanceof TickCache tickCache && tickCache.network == network) {
			if (tickCache.gameTick != gameTick) {
				tickCache.gameTick = gameTick;
				tickCache.amounts.clear();
			}
			return tickCache;
		}
		TickCache fresh = new TickCache(gameTick, network);
		holder.setInputInventoryViewCache(fresh);
		return fresh;
	}

	private static final class TickCache {
		private long gameTick;
		private final MEStorage network;
		private final Object2LongOpenHashMap<AEItemKey> amounts = new Object2LongOpenHashMap<>();

		private TickCache(long gameTick, MEStorage network) {
			this.gameTick = gameTick;
			this.network = network;
			amounts.defaultReturnValue(-1L);
		}
	}
}
