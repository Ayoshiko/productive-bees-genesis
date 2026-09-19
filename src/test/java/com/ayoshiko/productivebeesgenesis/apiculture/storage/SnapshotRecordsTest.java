package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotRecordsTest {
	private record Collision(int id) { @Override public int hashCode() { return 7; } }
	@Test void randomChangesAndFullHashCollisionsPreserveEveryRetainedGeneration() {
		var store = new SnapshotRecords<Collision, Integer>(Comparator.comparingInt(Collision::id));
		var model = new TreeMap<Collision, Integer>(Comparator.comparingInt(Collision::id));
		var snapshots = new ArrayList<Map<Collision, Integer>>(); var expected = new ArrayList<Map<Collision, Integer>>();
		var random = new Random(0xD09B2A);
		for (int i = 0; i < 40_000; i++) {
			var key = new Collision(random.nextInt(2048));
			if (random.nextInt(3) == 0) { store.remove(key); model.remove(key); }
			else { int value = random.nextInt(); store.put(key, value); model.put(key, value); }
			if (i % 5000 == 0) { snapshots.add(store.snapshot()); expected.add(Map.copyOf(model)); }
			if (i % 257 == 0) {
				assertEquals(model, store.snapshot()); assertEquals(List.copyOf(model.values()), store.valuesSnapshot());
				assertEquals(model.keySet(), store.keysSnapshot());
			}
		}
		store.clear(); assertEquals(0, store.size());
		for (int i = 0; i < snapshots.size(); i++) assertEquals(expected.get(i), snapshots.get(i));
		store.put(new Collision(1), 42); assertEquals(Map.of(new Collision(1), 42), store.snapshot());
	}
	@Test void orderedInsertionDeletionAndCaptureDoNotEnumerateRecords() {
		var comparisons = new AtomicInteger();
		var store = new SnapshotRecords<Integer, Integer>((a, b) -> { comparisons.incrementAndGet(); return a.compareTo(b); });
		for (int i = 0; i < 20_000; i++) store.put(i, i * 2);
		comparisons.set(0); var saved = store.snapshot(); var values = store.valuesSnapshot(); var keys = store.keysSnapshot();
		assertEquals(0, comparisons.get()); assertEquals(20_000, values.size());
		for (int i = 19_999; i >= 0; i--) store.remove(i);
		assertTrue(store.isEmpty()); assertEquals(19_999, saved.get(19_999) / 2); assertEquals(39_998, values.getLast());
		assertEquals(20_000, keys.size());
		assertThrows(UnsupportedOperationException.class, () -> saved.put(1, 2));
		assertThrows(UnsupportedOperationException.class, () -> saved.entrySet().iterator().next().setValue(2));
		assertThrows(UnsupportedOperationException.class, () -> values.set(0, 1));
		assertThrows(UnsupportedOperationException.class, () -> keys.remove(0));
		assertThrows(IndexOutOfBoundsException.class, () -> values.get(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> values.get(values.size()));
	}
	@Test void backgroundReadersOnlyObserveTheFrozenRoot() throws Exception {
		var store = new SnapshotRecords<Integer, Integer>(Comparator.naturalOrder());
		for (int i = 0; i < 10_000; i++) store.put(i, i);
		var frozen = store.valuesSnapshot();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var read = executor.submit(() -> {
				long sum = 0;
				for (int repeat = 0; repeat < 10; repeat++) for (int value : frozen) sum += value;
				return sum;
			});
			for (int i = 0; i < 10_000; i++) { store.remove(i); store.put(i + 10_000, -i); }
			assertEquals(499_950_000L, read.get());
		}
		assertEquals(0, frozen.getFirst()); assertEquals(9999, frozen.getLast());
	}
}
