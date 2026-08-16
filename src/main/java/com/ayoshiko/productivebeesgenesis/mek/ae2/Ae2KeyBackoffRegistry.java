package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Per-key failure backoff shared by AE2 item input and output scheduling. */
final class Ae2KeyBackoffRegistry<K> {

	private static final long PRUNE_INTERVAL_NS = 5_000_000_000L;
	private static final long STALE_AFTER_NS = 30_000_000_000L;
	private static final int PRUNE_THRESHOLD = 64;

	private final Map<K, Ae2PushBackoff> backoffs = new HashMap<>();
	private long lastPruneNanos;

	boolean shouldSkip(K key, long now) {
		Ae2PushBackoff backoff = backoffs.get(key);
		return backoff != null && backoff.shouldSkip(now);
	}

	void recordFailure(K key, long now) {
		if (key == null) return;
		Ae2PushBackoff backoff = backoffs.get(key);
		if (backoff == null) {
			backoff = new Ae2PushBackoff();
			backoffs.put(key, backoff);
		}
		backoff.recordFailure(now);
		pruneIfNeeded(now);
	}

	void recordSuccess(K key) {
		if (key != null) backoffs.remove(key);
	}

	void clear() {
		backoffs.clear();
		lastPruneNanos = 0L;
	}

	private void pruneIfNeeded(long now) {
		if (backoffs.size() < PRUNE_THRESHOLD || now - lastPruneNanos < PRUNE_INTERVAL_NS) return;
		lastPruneNanos = now;
		Iterator<Map.Entry<K, Ae2PushBackoff>> iterator = backoffs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<K, Ae2PushBackoff> entry = iterator.next();
			long end = entry.getValue().getBackoffEndNanos();
			if (end <= 0L || now - end > STALE_AFTER_NS) iterator.remove();
		}
	}
}
