package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;

import java.util.HashMap;
import java.util.Map;

/**
 * AE2 input type fairness scheduler, modeled after AE2LT's 100-tick accounting window.
 * <p>
 * AE2LT's {@code DispatchFairnessScheduler} leases the least-served targets first and keeps a
 * rolling 100-tick window. This class is a deliberately smaller adaptation for item-key input
 * pulling: it maintains a single 100-tick bucket per key and orders pull candidates by the amount
 * already served in the current bucket. Marked/comb-block priority is still applied by the caller
 * before this scheduler's tie-breaking comparator.
 * <p>
 * Server tick thread only.
 */
final class Ae2InputFairnessScheduler {

    static final int WINDOW_TICKS = 100;

    private long windowStartTick = Long.MIN_VALUE;
    private final Map<AEItemKey, Long> served = new HashMap<>();

    void roll(long gameTick) {
        if (gameTick - windowStartTick >= WINDOW_TICKS) {
            served.clear();
            windowStartTick = gameTick;
        }
    }

    long served(AEItemKey key) {
        return served.getOrDefault(key, 0L);
    }

    void recordServed(AEItemKey key, long amount) {
        if (key == null || amount <= 0L) return;
        served.merge(key, amount, Long::sum);
    }
}
