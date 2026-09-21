package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 分步建立优先级索引；轮转状态与真实占料一起提交，空转和模拟不消耗权重。 */
public final class RuntimeProcessingRules {
	public static final class Claim {
		private final SchedulerCheckpoint before, after;
		private final ProcessingRule rule;
		private Claim(SchedulerCheckpoint before, SchedulerCheckpoint after, ProcessingRule rule) { this.before = before; this.after = after; this.rule = rule; }
		public boolean matches(SchedulerCheckpoint current) { return before == current; }
		public SchedulerCheckpoint next() { return after; }
		public ProcessingRule rule() { return rule; }
	}
	private record Order(int priority, String id) { }
	private final List<ProcessingRule> configured;
	private final Thread owner = Thread.currentThread();
	private final Iterator<ProcessingRule> loading;
	private final SnapshotRecords<Order, ProcessingRule> sorted = new SnapshotRecords<>(Comparator.comparingInt(Order::priority).reversed().thenComparing(Order::id));
	private final Map<String, Integer> positions = new ConcurrentHashMap<>();
	private List<ProcessingRule> ordered;
	private int indexed;
	private boolean supported = true;
	public RuntimeProcessingRules(SchedulerCheckpoint source) { configured = source.rules(); loading = configured.iterator(); }
	public boolean matches(SchedulerCheckpoint source) { return configured == source.rules(); }
	public boolean step() {
		check();
		if (loading.hasNext()) {
			var rule = loading.next(); if (rule.enabled()) {
				if (rule.selector() instanceof ProcessingRule.Goal) supported = false;
				sorted.put(new Order(rule.priority(), rule.id()), rule);
			}
			return false;
		}
		if (ordered == null) ordered = sorted.valuesSnapshot();
		if (indexed < ordered.size()) { positions.put(ordered.get(indexed).id(), indexed); indexed++; return false; }
		return true;
	}
	public boolean ready() { return ordered != null && indexed == ordered.size(); }
	public boolean supported() { return ready() && supported; }
	public boolean defaults() { return configured.isEmpty(); }
	public int size() { requireReady(); return defaults() ? 1 : ordered.size(); }
	public ProcessingRule rule(int position) { requireReady(); return defaults() ? null : ordered.get(position); }
	public int first(SchedulerCheckpoint current) {
		requireReady(); if (!matches(current)) throw new IllegalArgumentException("Stale runtime rules");
		return current.mode() == ProcessingRuleScheduler.Mode.STRICT_PRIORITY ? 0 : positions.getOrDefault(current.cursorRule(), 0);
	}
	public Claim claim(SchedulerCheckpoint current, ProcessingRule rule) {
		requireReady(); if (!matches(current) || !supported) throw new IllegalArgumentException("Unavailable runtime rule configuration");
		if (defaults()) { if (rule != null) throw new IllegalArgumentException("Unexpected default rule"); return new Claim(current, current, null); }
		var position = rule == null ? null : positions.get(rule.id());
		if (position == null || ordered.get(position) != rule) throw new IllegalArgumentException("Foreign processing rule");
		if (current.mode() == ProcessingRuleScheduler.Mode.STRICT_PRIORITY) return new Claim(current, current, rule);
		int used = current.cursorRule().equals(rule.id()) ? current.used() + 1 : 1;
		String next = rule.id(); if (used >= rule.weight()) { next = ordered.get((position + 1) % ordered.size()).id(); used = 0; }
		return new Claim(current, current.cursor(next, used), rule);
	}
	private void requireReady() { check(); if (!ready()) throw new IllegalStateException("Runtime rule index incomplete"); }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Runtime rule index belongs to its server thread"); }
}
