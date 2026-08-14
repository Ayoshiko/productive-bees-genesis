package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-AEItemKey input-pull failure backoff registry.
 * <p>
 * The old per-tile return backoff is retained for leftover-return failures, but
 * a single missing/failing honeycomb type should no longer stall all other pull
 * types. This registry mirrors AE2LT's per-keytype cooldown/timing-wheel idea at
 * the item-key granularity: only the failed key is parked for a short interval.
 * <p>
 * Server tick thread only; no synchronization.
 */
final class Ae2InputKeyBackoffRegistry {

    private static final long PRUNE_INTERVAL_NS = 5_000_000_000L;
    private static final long STALE_AFTER_NS = 30_000_000_000L;
    private static final int PRUNE_THRESHOLD = 64;

    private final Map<AEItemKey, Ae2PushBackoff> backoffs = new HashMap<>();
    private long lastPruneNanos;

    boolean shouldSkip(AEItemKey key, long now) {
        Ae2PushBackoff backoff = backoffs.get(key);
        return backoff != null && backoff.shouldSkip(now);
    }

    void recordFailure(AEItemKey key, long now) {
        backoffs.computeIfAbsent(key, ignored -> new Ae2PushBackoff()).recordFailure(now);
        pruneIfNeeded(now);
    }

    void recordSuccess(AEItemKey key) {
        backoffs.remove(key);
    }

    private void pruneIfNeeded(long now) {
        if (backoffs.size() < PRUNE_THRESHOLD || now - lastPruneNanos < PRUNE_INTERVAL_NS) {
            return;
        }
        lastPruneNanos = now;
        Iterator<Map.Entry<AEItemKey, Ae2PushBackoff>> iterator = backoffs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AEItemKey, Ae2PushBackoff> entry = iterator.next();
            long end = entry.getValue().getBackoffEndNanos();
            if (end <= 0L || now - end > STALE_AFTER_NS) {
                iterator.remove();
            }
        }
    }
}
