package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/** 单线程到期索引；每个身份至多一项，同 tick 重排放到队尾，不积累逐 tick 欠账。 */
public final class FairDueQueue<K> {
	private record Ticket<K>(K key, long due, long order) { }
	private final Thread owner = Thread.currentThread();
	private final Map<K, Ticket<K>> entries = new ConcurrentHashMap<>();
	private final TreeSet<Ticket<K>> ordered = new TreeSet<>(Comparator.<Ticket<K>>comparingLong(Ticket::due).thenComparingLong(Ticket::order));
	private final LongSupplier clock;
	private long sequence, lastTick = Long.MIN_VALUE;
	public FairDueQueue() { this(System::nanoTime); }
	FairDueQueue(LongSupplier clock) { this.clock = clock; }
	public void offer(K key, long due) {
		checkThread(); if (entries.containsKey(key)) return;
		var ticket = new Ticket<>(key, due, Math.incrementExact(sequence)); sequence = ticket.order();
		entries.put(key, ticket); ordered.add(ticket);
	}
	public void wake(K key, long now) {
		checkThread(); var ticket = entries.get(key);
		if (ticket != null && ticket.due() <= now) return;
		remove(key); offer(key, now);
	}
	public void remove(K key) { checkThread(); var ticket = entries.remove(key); if (ticket != null) ordered.remove(ticket); }
	public K poll(long now) {
		checkThread(); if (ordered.isEmpty() || ordered.first().due() > now) return null;
		var ticket = ordered.pollFirst(); entries.remove(ticket.key()); return ticket.key();
	}
	public long nextTick() { checkThread(); return ordered.isEmpty() ? Long.MAX_VALUE : ordered.first().due(); }
	public int size() { checkThread(); return entries.size(); }
	/** 回调返回下次到期 tick；MAX_VALUE 表示取消。时间预算是不可抢占单步之间的软限制。 */
	public int runDue(long now, int maxSteps, long maxNanos, ToLongFunction<K> step) {
		checkThread(); if (maxSteps < 0 || maxNanos <= 0) throw new IllegalArgumentException("Invalid runtime budget");
		if (now == lastTick) return 0; lastTick = now;
		long start = clock.getAsLong(); int count = 0;
		while (count < maxSteps && (count == 0 || clock.getAsLong() - start < maxNanos)) {
			K key = poll(now); if (key == null) break;
			long next = step.applyAsLong(key); count++;
			if (next != Long.MAX_VALUE) offer(key, Math.max(now, next));
		}
		return count;
	}
	private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Runtime queue belongs to its server thread"); }
}
