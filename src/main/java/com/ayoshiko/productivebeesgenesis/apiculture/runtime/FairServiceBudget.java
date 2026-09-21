package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/** 子系统共同消费一次真实 tick 的预算；跨 tick 保留游标，耗时单步不能阻断下一轮公平性。 */
public final class FairServiceBudget {
	private final Thread owner = Thread.currentThread();
	private final LongSupplier clock;
	private final int[] used;
	private final long[] nanos;
	private final long[] longestStep;
	private final boolean[] idle;
	private long lastTick = Long.MIN_VALUE, elapsed;
	private int cursor, attempts;
	public FairServiceBudget(int services) { this(services, System::nanoTime); }
	FairServiceBudget(int services, LongSupplier clock) {
		if (services <= 0) throw new IllegalArgumentException("At least one service required");
		this.clock = java.util.Objects.requireNonNull(clock); used = new int[services]; nanos = new long[services];
		longestStep = new long[services]; idle = new boolean[services];
	}
	public int run(long tick, int maxSteps, long maxNanos, int[] limits, long[] timeLimits, IntPredicate step) {
		check();
		if (maxSteps <= 0 || maxNanos <= 0 || limits.length != used.length || timeLimits.length != used.length)
			throw new IllegalArgumentException("Invalid shared budget");
		for (int i = 0; i < used.length; i++) if (limits[i] < 0 || timeLimits[i] <= 0) throw new IllegalArgumentException("Invalid service budget");
		if (tick == lastTick) return 0;
		lastTick = tick; attempts = 0; elapsed = 0;
		Arrays.fill(used, 0); Arrays.fill(nanos, 0); Arrays.fill(longestStep, 0); Arrays.fill(idle, false);
		long start = clock.getAsLong(); int skipped = 0;
		try {
			while (attempts < maxSteps && (attempts == 0 || clock.getAsLong() - start < maxNanos)) {
				int service = cursor; cursor = (cursor + 1) % used.length;
				if (idle[service] || used[service] >= limits[service] || nanos[service] >= timeLimits[service]) {
					if (++skipped == used.length) break;
					continue;
				}
				skipped = 0; attempts++; used[service]++;
				long before = clock.getAsLong();
				try { idle[service] = !step.test(service); }
				finally {
					long duration = clock.getAsLong() - before;
					nanos[service] += duration; longestStep[service] = Math.max(longestStep[service], duration);
				}
			}
		} finally { elapsed = clock.getAsLong() - start; }
		return attempts;
	}
	public int attempts() { check(); return attempts; }
	public int used(int service) { check(); return used[service]; }
	public long nanos(int service) { check(); return nanos[service]; }
	public long longestStepNanos(int service) { check(); return longestStep[service]; }
	public long elapsedNanos() { check(); return elapsed; }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Shared budget belongs to the server thread"); }
}
