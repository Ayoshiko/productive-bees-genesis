package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FairDueQueueTest {
	@Test void hotNetworkCannotStarveOtherNetworksAcrossBudgetBoundaries() {
		var queue = new FairDueQueue<String>(() -> 0); var visited = new ArrayList<String>();
		for (String key : List.of("large", "small", "late")) queue.offer(key, 1);
		for (long tick = 1; tick <= 3; tick++) {
			long now = tick;
			assertEquals(1, queue.runDue(now, 1, 100, key -> { visited.add(key); return now; }));
		}
		assertEquals(List.of("large", "small", "late"), visited); assertEquals(3, queue.size());
	}
	@Test void repeatedWatchDoesNotDuplicateEntriesOrBypassSleep() {
		var queue = new FairDueQueue<String>(); queue.offer("bee", 20);
		for (int i = 0; i < 10000; i++) queue.offer("bee", 0);
		assertEquals(1, queue.size()); assertNull(queue.poll(19)); assertEquals("bee", queue.poll(20));
		assertNull(queue.poll(20)); assertEquals(0, queue.size());
	}
	@Test void duplicateRealTickCannotSpendTheGlobalBudgetAgain() {
		var queue = new FairDueQueue<String>(); queue.offer("core", 1);
		assertEquals(3, queue.runDue(1, 3, Long.MAX_VALUE, key -> 1));
		assertEquals(0, queue.runDue(1, 3, Long.MAX_VALUE, key -> fail("Same tick dispatched twice")));
		assertEquals(1, queue.runDue(2, 3, Long.MAX_VALUE, key -> Long.MAX_VALUE));
		assertEquals(0, queue.size());
	}
	@Test void oneBeeGetsAtMostOneNewTickEvenWhenTheQueueIsOverdue() {
		var queue = new FairDueQueue<String>(); queue.offer("bee", 1);
		assertEquals(1, queue.runDue(1000, 4096, Long.MAX_VALUE, key -> 1001));
		assertEquals(1001, queue.nextTick());
	}
	@Test void timeBudgetStopsBetweenStepsAndRetainsEveryQueuedIdentity() {
		var clock = new AtomicLong(); var queue = new FairDueQueue<String>(clock::get);
		queue.offer("a", 1); queue.offer("b", 1);
		assertEquals(1, queue.runDue(1, 100, 10, key -> { clock.set(10); return 2; }));
		assertEquals(2, queue.size()); assertEquals("b", queue.poll(1)); assertEquals("a", queue.poll(2));
	}
	@Test void unloadedKeysAreRemovedWithoutStaleHeapEntriesAndCanBeRegisteredAgain() {
		var queue = new FairDueQueue<String>(); queue.offer("core", 100);
		queue.remove("core"); assertEquals(Long.MAX_VALUE, queue.nextTick());
		queue.offer("core", 200); queue.wake("core", 5);
		assertEquals("core", queue.poll(5)); assertNull(queue.poll(200));
	}
	@Test void crossThreadMutationIsRejected() throws Exception {
		var queue = new FairDueQueue<String>();
		var task = new java.util.concurrent.FutureTask<>(() -> assertThrows(IllegalStateException.class, () -> queue.offer("core", 1)));
		var thread = new Thread(task); thread.start(); task.get(); thread.join(); assertEquals(0, queue.size());
	}
}
