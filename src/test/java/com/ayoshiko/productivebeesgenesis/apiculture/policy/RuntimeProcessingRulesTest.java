package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.policy.GroupAllowanceAllocatorTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeProcessingRulesTest {
	private static ProcessingRule rule(String id, int priority, int weight) {
		return new ProcessingRule(id, 0, true, priority, weight, 64,
				new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, comb("test:iron", "a"))), policy(ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE));
	}
	private static RuntimeProcessingRules ready(SchedulerCheckpoint checkpoint) {
		var index = new RuntimeProcessingRules(checkpoint); int steps = 0; while (!index.step()) assertTrue(++steps < 100); return index;
	}
	@Test void weightedRotationIsSignedAgainstItsExactSchedulerAndOnlyClaimingAdvancesIt() {
		var high = rule("high", 10, 2); var low = rule("low", 0, 1);
		var current = new SchedulerCheckpoint(List.of(low, high), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0);
		var index = new RuntimeProcessingRules(current); assertFalse(index.step()); assertFalse(index.ready()); while (!index.step()) { }
		assertSame(high, index.rule(index.first(current))); assertEquals("", current.cursorRule());
		var claim = index.claim(current, high); assertTrue(claim.matches(current)); var first = claim.next();
		assertEquals("high", first.cursorRule()); assertEquals(1, first.used()); assertFalse(claim.matches(first)); assertSame(current.rules(), first.rules());
		var second = index.claim(first, high).next(); assertEquals("low", second.cursorRule()); assertEquals(0, second.used());
		assertSame(low, index.rule(index.first(second))); assertEquals("high", index.claim(second, low).next().cursorRule());
		assertThrows(IllegalArgumentException.class, () -> index.claim(second, rule("missing", 1, 1)));
	}
	@Test void strictPriorityNeverSpendsAFairCursorAndDefaultsAreOnlyForEmptyConfiguration() {
		var high = rule("high", 10, 2); var low = rule("low", 0, 1);
		var current = new SchedulerCheckpoint(List.of(low, high), ProcessingRuleScheduler.Mode.STRICT_PRIORITY, Map.of(), "low", 0);
		var index = ready(current); assertSame(high, index.rule(index.first(current))); assertSame(current, index.claim(current, low).next());
		var defaults = ready(SchedulerCheckpoint.EMPTY); assertTrue(defaults.defaults()); assertEquals(1, defaults.size());
		assertNull(defaults.rule(0)); assertSame(SchedulerCheckpoint.EMPTY, defaults.claim(SchedulerCheckpoint.EMPTY, null).next());
		var disabled = new ProcessingRule("disabled", 0, false, 0, 1, 1, high.selector(), high.reserves());
		assertEquals(0, ready(new SchedulerCheckpoint(List.of(disabled), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0)).size());
	}
	@Test void enabledGoalsBlockAutomaticAdmissionAndCannotBeSilentlySkipped() {
		var ordinary = rule("ordinary", 1, 1);
		var goal = new ProcessingRule("goal", 0, true, 10, 1, 1,
				new ProcessingRule.Goal(comb("test:iron", "a"), amount(1), amount(10)), ordinary.reserves());
		var current = new SchedulerCheckpoint(List.of(ordinary, goal), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0);
		var index = ready(current); assertFalse(index.supported()); assertThrows(IllegalArgumentException.class, () -> index.claim(current, ordinary));
	}
}
