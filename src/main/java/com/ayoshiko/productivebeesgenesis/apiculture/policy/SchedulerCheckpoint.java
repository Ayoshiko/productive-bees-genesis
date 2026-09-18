package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 持久化规则配置和滞回／轮转状态；配方索引在加载后重新编译。 */
public record SchedulerCheckpoint(List<ProcessingRule> rules, ProcessingRuleScheduler.Mode mode,
		Map<String, Boolean> watermarks, String cursorRule, int used) {
	public static final SchedulerCheckpoint EMPTY = new SchedulerCheckpoint(List.of(), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0);
	public SchedulerCheckpoint {
		rules = List.copyOf(rules); watermarks = Map.copyOf(watermarks);
		Objects.requireNonNull(mode); Objects.requireNonNull(cursorRule);
		var ids = ConcurrentHashMap.<String>newKeySet();
		for (var rule : rules) if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate rule identity");
		var cursor = rules.stream().filter(rule -> rule.id().equals(cursorRule) && rule.enabled()).findFirst();
		if (used < 0 || cursorRule.isEmpty() && used != 0 || !cursorRule.isEmpty()
				&& (cursor.isEmpty() || used >= cursor.orElseThrow().weight())) throw new IllegalArgumentException("Invalid scheduler cursor");
		for (var id : watermarks.keySet()) {
			if (rules.stream().noneMatch(rule -> rule.id().equals(id) && rule.enabled() && rule.selector() instanceof ProcessingRule.Goal)) {
				throw new IllegalArgumentException("Orphaned watermark");
			}
		}
	}
}
