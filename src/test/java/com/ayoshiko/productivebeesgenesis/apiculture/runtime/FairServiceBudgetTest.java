package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FairServiceBudgetTest {
	private static final int[] LIMITS = {32, 256, 4, 256};
	private static final long[] TIMES = {1000, 1000, 1000, 1000};
	@Test void allServicesShareOneStepAcrossRealTicksWithoutStarvation() {
		var budget = new FairServiceBudget(4, () -> 0); var visits = new ArrayList<Integer>();
		for (int tick = 0; tick < 8; tick++) assertEquals(1, budget.run(tick, 1, 100, LIMITS, TIMES, id -> { visits.add(id); return true; }));
		assertEquals(List.of(0, 1, 2, 3, 0, 1, 2, 3), visits);
	}
	@Test void sharedAndLocalLimitsBothApplyAndSameTickCannotResetThem() {
		var budget = new FairServiceBudget(4, () -> 0);
		assertEquals(100, budget.run(1, 100, 100, LIMITS, TIMES, id -> true));
		assertEquals(4, budget.used(2));
		assertEquals(0, budget.run(1, 200, 100, LIMITS, TIMES, id -> fail("Duplicate tick")));
		assertEquals(548, budget.run(2, 1000, 100, LIMITS, TIMES, id -> true));
	}
	@Test void totalTimeIncludesAllServicesAndAnOverrunResumesAtNextService() {
		var clock = new AtomicLong(); var budget = new FairServiceBudget(4, clock::get); var visits = new ArrayList<Integer>();
		assertEquals(2, budget.run(1, 100, 10, LIMITS, TIMES, id -> { visits.add(id); clock.addAndGet(6); return true; }));
		assertEquals(12, budget.elapsedNanos()); assertEquals(6, budget.nanos(0)); assertEquals(6, budget.nanos(1));
		assertEquals(6, budget.longestStepNanos(0));
		budget.run(2, 1, 10, LIMITS, TIMES, id -> { visits.add(id); return true; });
		assertEquals(List.of(0, 1, 2), visits);
		assertEquals(0, budget.longestStepNanos(0));
	}
	@Test void exhaustedLocalTimeDoesNotBlockOtherServices() {
		var clock = new AtomicLong(); var budget = new FairServiceBudget(4, clock::get);
		budget.run(1, 8, 1000, LIMITS, new long[]{5, 1000, 1000, 1000}, id -> { clock.addAndGet(5); return true; });
		assertEquals(1, budget.used(0)); assertEquals(8, budget.attempts());
	}
	@Test void idleServicesAreCheckedOnceAndDoNotSpin() {
		var budget = new FairServiceBudget(4, () -> 0);
		assertEquals(4, budget.run(1, 100, 100, LIMITS, TIMES, id -> false));
		assertEquals(0, budget.run(2, 100, 100, new int[4], TIMES, id -> fail("Disabled service")));
	}
	@Test void exceptionStillConsumesBudgetAndRotates() {
		var clock = new AtomicLong(); var budget = new FairServiceBudget(4, clock::get);
		assertThrows(IllegalStateException.class, () -> budget.run(1, 100, 100, LIMITS, TIMES, id -> { clock.set(7); throw new IllegalStateException("failure"); }));
		assertEquals(7, budget.elapsedNanos()); assertEquals(1, budget.attempts());
		budget.run(2, 1, 100, LIMITS, TIMES, id -> { assertEquals(1, id); return true; });
	}
	@Test void crossThreadAccessFails() throws Exception {
		var budget = new FairServiceBudget(4);
		var task = new java.util.concurrent.FutureTask<>(() -> assertThrows(IllegalStateException.class, () -> budget.run(1, 1, 1, LIMITS, TIMES, id -> true)));
		var thread = new Thread(task); thread.start(); task.get(); thread.join();
	}
}
