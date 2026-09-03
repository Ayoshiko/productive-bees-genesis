package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 有界 LRU 映射的淘汰与晋升语义断言。
 * <p>
 * 背景：{@code Ae2FingerprintCache} 原为"满 128 条即整表清空"，
 * 大型 AE2 网络物品种类超过上限时会周期性丢弃全部热条目，命中率塌陷。
 * 本测试锁定改用 LRU 后的关键契约：上限生效、热键常驻、淘汰的是最久未使用而非最早插入。
 */
class BoundedLruMapTest {

	@Test
	void enforcesMaxEntries() {
		Map<Integer, String> cache = BoundedLruMap.accessOrdered(3);
		for (int i = 0; i < 100; i++) {
			cache.put(i, "v" + i);
			assertTrue(cache.size() <= 3, "size must never exceed the cap");
		}
		assertEquals(3, cache.size());
	}

	@Test
	void evictsLeastRecentlyUsedNotOldestInserted() {
		Map<String, String> cache = BoundedLruMap.accessOrdered(3);
		cache.put("a", "1");
		cache.put("b", "2");
		cache.put("c", "3");
		// 访问 a：a 成为最近使用，最久未使用变成 b
		assertEquals("1", cache.get("a"));
		cache.put("d", "4");
		assertNull(cache.get("b"), "b was least recently used and must be evicted");
		assertEquals("1", cache.get("a"), "a was refreshed by the read and must survive");
		assertEquals("3", cache.get("c"));
		assertEquals("4", cache.get("d"));
	}

	@Test
	void hotKeyStaysResidentUnderChurn() {
		Map<Integer, String> cache = BoundedLruMap.accessOrdered(4);
		cache.put(-1, "hot");
		// 持续写入冷键，同时每轮读一次热键
		for (int i = 0; i < 200; i++) {
			cache.put(i, "cold" + i);
			assertEquals("hot", cache.get(-1), "hot key must not be dropped by cold churn");
		}
	}

	@Test
	void putRefreshesRecency() {
		Map<String, String> cache = BoundedLruMap.accessOrdered(2);
		cache.put("a", "1");
		cache.put("b", "2");
		// 重写 a 也算一次访问
		cache.put("a", "1b");
		cache.put("c", "3");
		assertNull(cache.get("b"));
		assertEquals("1b", cache.get("a"));
	}

	@Test
	void rejectsNonPositiveCap() {
		assertThrows(IllegalArgumentException.class, () -> BoundedLruMap.accessOrdered(0));
		assertThrows(IllegalArgumentException.class, () -> BoundedLruMap.accessOrdered(-1));
	}

	@Test
	void synchronizedVariantKeepsBoundUnderConcurrentWriters() throws InterruptedException {
		Map<Integer, String> cache = BoundedLruMap.synchronizedAccessOrdered(16);
		List<Thread> threads = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		for (int t = 0; t < 4; t++) {
			int base = t * 1000;
			Thread thread = new Thread(() -> {
				for (int i = 0; i < 500; i++) {
					cache.put(base + i, "v");
				}
			});
			thread.setUncaughtExceptionHandler((th, error) -> {
				synchronized (failures) {
					failures.add(error);
				}
			});
			threads.add(thread);
			thread.start();
		}
		for (Thread thread : threads) {
			thread.join();
		}
		assertTrue(failures.isEmpty(), () -> "concurrent writers must not fail: " + failures);
		// 同步包装保证结构修改互斥，因此上限始终成立
		assertEquals(16, cache.size());
	}

	@Test
	void synchronizedVariantSupportsLockFreeValueComputation() {
		Map<String, String> cache = BoundedLruMap.synchronizedAccessOrdered(8);
		// 调用方约定：get 未命中 → 锁外计算 → putIfAbsent，重复计算结果幂等
		assertNull(cache.get("k"));
		String computed = "expensive";
		assertNull(cache.putIfAbsent("k", computed));
		assertEquals(computed, cache.putIfAbsent("k", "duplicate"),
				"putIfAbsent must return the resident value so callers reuse one instance");
		assertFalse(cache.isEmpty());
	}
}
