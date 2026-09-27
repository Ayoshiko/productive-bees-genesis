package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 有界的按键失败退避；由宿主的服务端 tick 线程独占访问。 */
final class Ae2KeyBackoffRegistry<K> {

	private static final long PRUNE_INTERVAL_NS = 5_000_000_000L;
	private static final long STALE_AFTER_NS = 30_000_000_000L;
	private static final int PRUNE_THRESHOLD = 64;
	static final int MAX_ENTRIES = 512;

	private final Map<K, Ae2PushBackoff> backoffs = new ConcurrentHashMap<>();
	private long lastPruneNanos;

	boolean shouldSkip(K key, long now) {
		Ae2PushBackoff backoff = backoffs.get(key);
		return backoff != null && backoff.shouldSkip(now);
	}

	void recordFailure(K key, long now) {
		if (key == null) return;
		pruneIfNeeded(now);
		Ae2PushBackoff backoff = backoffs.get(key);
		if (backoff == null) {
			// 仅淘汰调度记录，不持有物品；容量满时允许旧类型提前重试。
			if (backoffs.size() >= MAX_ENTRIES) {
				Iterator<K> keys = backoffs.keySet().iterator();
				if (keys.hasNext()) backoffs.remove(keys.next());
			}
			backoff = new Ae2PushBackoff();
			backoffs.put(key, backoff);
		}
		backoff.recordFailure(now);
	}

	int size() {
		return backoffs.size();
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
