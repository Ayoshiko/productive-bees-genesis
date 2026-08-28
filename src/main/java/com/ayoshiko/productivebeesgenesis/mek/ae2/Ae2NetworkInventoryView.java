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

	/**
	 * Reads the already aggregated AE2 counter without probing every storage
	 * provider. This is used for GUI stock display; pull decisions use
	 * {@link #visibleAmount} when a simulated extract is required.
	 */
	public static long cachedAmount(KeyCounter cachedInventory, AEItemKey key, long cap) {
		if (cachedInventory == null || key == null || cap <= 0L) return 0L;
		return Ae2VisibleStockMath.merge(cachedInventory.get(key), 0L, cap);
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
					simulated = liveExtractableAmount(network, key, Long.MAX_VALUE, source);
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

	/**
	 * Queries current extractable stock without using the per-machine or AE2 inventory cache.
	 * Callers enforcing a reserve floor must invoke this immediately before MODULATE.
	 */
	static long liveExtractableAmount(MEStorage network, AEItemKey key, long cap, IActionSource source) {
		if (network == null || key == null || source == null || cap <= 0L) return 0L;
		long extracted = network.extract(key, cap, Actionable.SIMULATE, source);
		return Math.min(cap, Math.max(0L, extracted));
	}

	/**
	 * Applies a committed ME extraction to the same-tick view. JDTE may invoke the
	 * puller multiple times while the world game time is unchanged; keeping this
	 * key's cached amount in sync prevents a later invocation from crossing a
	 * configured reserve floor without invalidating the whole per-tick map.
	 */
	public static void recordExtract(Ae2OutputStateHolder holder, long gameTick,
			MEStorage network, AEItemKey key, long extracted) {
		if (holder == null || network == null || key == null || extracted <= 0L) return;
		Object cached = holder.getInputInventoryViewCache();
		if (!(cached instanceof TickCache cache)
				|| cache.network != network || cache.gameTick != gameTick) return;
		long current = cache.amounts.getLong(key);
		if (current >= 0L) {
			cache.amounts.put(key, Math.max(0L, current - extracted));
		}
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
