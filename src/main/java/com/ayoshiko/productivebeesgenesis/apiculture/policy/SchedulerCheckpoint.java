package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 持久化规则配置和滞回／轮转状态；配方索引在加载后重新编译。 */
public final class SchedulerCheckpoint {
	public static final SchedulerCheckpoint EMPTY = new SchedulerCheckpoint(List.of(), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0);
	private final List<ProcessingRule> rules;
	private final ProcessingRuleScheduler.Mode mode;
	private final Map<String, Boolean> watermarks;
	private final String cursorRule;
	private final int used;
	public SchedulerCheckpoint(List<ProcessingRule> rules, ProcessingRuleScheduler.Mode mode,
			Map<String, Boolean> watermarks, String cursorRule, int used) {
		this(List.copyOf(rules), mode, Map.copyOf(watermarks), cursorRule, used, false);
	}
	private SchedulerCheckpoint(List<ProcessingRule> rules, ProcessingRuleScheduler.Mode mode,
			Map<String, Boolean> watermarks, String cursorRule, int used, boolean captured) {
		this.rules = rules; this.mode = Objects.requireNonNull(mode); this.watermarks = watermarks;
		this.cursorRule = Objects.requireNonNull(cursorRule); this.used = used;
		if (captured) return;
		Map<String, ProcessingRule> byId = new ConcurrentHashMap<>();
		for (var rule : rules) if (byId.putIfAbsent(rule.id(), rule) != null) throw new IllegalArgumentException("Duplicate rule identity");
		var cursor = byId.get(cursorRule);
		if (used < 0 || cursorRule.isEmpty() && used != 0 || !cursorRule.isEmpty()
				&& (cursor == null || !cursor.enabled() || used >= cursor.weight())) throw new IllegalArgumentException("Invalid scheduler cursor");
		for (var id : watermarks.keySet()) {
			var rule = byId.get(id);
			if (rule == null || !rule.enabled() || !(rule.selector() instanceof ProcessingRule.Goal)) {
				throw new IllegalArgumentException("Orphaned watermark");
			}
		}
	}
	static SchedulerCheckpoint capture(ProcessingRuleIndex index, ProcessingRuleScheduler.Mode mode,
			SnapshotRecords<String, Boolean> watermarks, int cursor, int used) {
		return new SchedulerCheckpoint(index.configuredRules(), mode, watermarks.snapshot(),
				index.rules().isEmpty() ? "" : index.rules().get(cursor).id(), used, true);
	}
	public static final class RestoreBuilder {
		private final SnapshotRecords<Integer, ProcessingRule> rules = new SnapshotRecords<>(Integer::compare);
		private final SnapshotRecords<String, ProcessingRule> byId = new SnapshotRecords<>(String::compareTo);
		private final SnapshotRecords<String, Boolean> watermarks = new SnapshotRecords<>(String::compareTo);
		private java.util.Iterator<String> checks;
		public void rule(ProcessingRule rule) {
			if (checks != null || byId.get(rule.id()) != null) throw new IllegalArgumentException("Duplicate rule identity");
			byId.put(rule.id(), rule); rules.put(rules.size(), rule);
		}
		public void watermark(String id, boolean value) {
			if (checks != null || watermarks.get(id) != null) throw new IllegalArgumentException("Duplicate watermark");
			watermarks.put(id, value);
		}
		public boolean validateStep() {
			if (checks == null) checks = watermarks.keysSnapshot().iterator();
			if (!checks.hasNext()) return true;
			var rule = byId.get(checks.next());
			if (rule == null || !rule.enabled() || !(rule.selector() instanceof ProcessingRule.Goal)) throw new IllegalArgumentException("Orphaned watermark");
			return false;
		}
		public SchedulerCheckpoint finish(ProcessingRuleScheduler.Mode mode, String cursorRule, int used) {
			if (checks == null || checks.hasNext()) throw new IllegalStateException("Scheduler validation incomplete");
			var cursor = byId.get(cursorRule);
			if (used < 0 || cursorRule.isEmpty() && used != 0 || !cursorRule.isEmpty()
					&& (cursor == null || !cursor.enabled() || used >= cursor.weight())) throw new IllegalArgumentException("Invalid scheduler cursor");
			return new SchedulerCheckpoint(rules.valuesSnapshot(), mode, watermarks.snapshot(), cursorRule, used, true);
		}
	}
	public List<ProcessingRule> rules() { return rules; }
	SchedulerCheckpoint cursor(String id, int count) { return new SchedulerCheckpoint(rules, mode, watermarks, id, count, true); }
	public ProcessingRuleScheduler.Mode mode() { return mode; }
	public Map<String, Boolean> watermarks() { return watermarks; }
	public String cursorRule() { return cursorRule; }
	public int used() { return used; }
	@Override public boolean equals(Object other) {
		return this == other || other instanceof SchedulerCheckpoint checkpoint && used == checkpoint.used && mode == checkpoint.mode
				&& rules.equals(checkpoint.rules) && watermarks.equals(checkpoint.watermarks) && cursorRule.equals(checkpoint.cursorRule);
	}
	@Override public int hashCode() { return Objects.hash(rules, mode, watermarks, cursorRule, used); }
	@Override public String toString() {
		return "SchedulerCheckpoint[rules=" + rules.size() + ", mode=" + mode + ", cursor=" + cursorRule + ", used=" + used + "]";
	}
}
