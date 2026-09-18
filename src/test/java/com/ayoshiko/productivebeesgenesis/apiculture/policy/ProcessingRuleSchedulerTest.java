package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingRuleSchedulerTest {
	private static final ProductKey A = key("test:a"), B = key("test:b"), P = key("test:p");
	private static final WorkKey WORK = new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:recipe", 1, "plain");
	private static final ReservePolicy NONE = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
	private static ProductKey key(String id) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse(id), new CompoundTag()); }
	private static ProductAmount amount(long value) { return ProductAmount.of(value); }
	private static ProductMatcher matcher(ProductKey key) { return new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, key); }
	private static ProcessingRule rule(String id, ProductKey input, int priority, int weight) {
		return new ProcessingRule(id, 1, true, priority, weight, 1, new ProcessingRule.Match(matcher(input)), NONE);
	}
	private static ProcessingRecipe recipe(ProductKey input) { return new ProcessingRecipe(WORK, matcher(input), amount(1), Set.of(P)); }
	private static ProductLedger ledger(ProductKey... keys) {
		var descriptors = java.util.Arrays.stream(keys).map(k -> new AllowedProductDescriptor(k, "test", "test:recipe")).toList();
		return new ProductLedger(new ProductPolicyRegistry(new ProductPolicySnapshot(1, descriptors, List.of())), 32);
	}
	private static CapacityPoolIndex capacity() {
		var work = new WorkCapacity(WORK, 4, 10, 10, 40, 1, 0, Map.of());
		var member = new MemberCapabilitySnapshot(UUID.randomUUID(), 1, "test:machine",
				new MemberCapabilitySnapshot.Origin("test:world", 0, 0, 0), MemberCapabilitySnapshot.Availability.ONLINE, 0, 1, List.of(work));
		return new CapacityPoolIndex(List.of(member));
	}
	@Test
	void weightedFairModeAdvancesLowPriorityWhileStrictModeSelectsHighestReadyRule() {
		var ledger = ledger(A, B, P);
		ledger.insert(A, amount(100), ProductLedger.Action.EXECUTE); ledger.insert(B, amount(100), ProductLedger.Action.EXECUTE);
		var index = new ProcessingRuleIndex(List.of(rule("high", A, 10, 2), rule("low", B, 0, 1)), List.of(recipe(A), recipe(B)), Map.of());
		var capacity = capacity();
		var fair = new ProcessingRuleScheduler(ledger, index, ProcessingRuleScheduler.Mode.FAIR);
		var order = new ArrayList<String>();
		for (int i = 0; i < 6; i++) {
			var selection = fair.select(1, capacity, Map.of()); order.add(selection.rule().id());
			assertTrue(ledger.commit(fair.claim(selection, capacity, Map.of(P, amount(1)))));
		}
		assertEquals(List.of("high", "high", "low", "high", "high", "low"), order);
		var strict = new ProcessingRuleScheduler(ledger, index, ProcessingRuleScheduler.Mode.STRICT_PRIORITY);
		assertEquals("high", strict.select(1, capacity, Map.of()).rule().id());
		ledger.extract(A, amount(100), ProductLedger.Action.EXECUTE);
		assertEquals("low", strict.select(1, capacity, Map.of()).rule().id());
	}
	@Test
	void overlappingSelectionsCannotSpendOneSnapshotTwiceAndClaimsAreIdempotent() {
		var ledger = ledger(A, P); ledger.insert(A, amount(10), ProductLedger.Action.EXECUTE);
		var index = new ProcessingRuleIndex(List.of(rule("one", A, 0, 1), rule("two", A, 0, 1)), List.of(recipe(A)), Map.of());
		var scheduler = new ProcessingRuleScheduler(ledger, index, ProcessingRuleScheduler.Mode.FAIR);
		var capacity = capacity();
		var first = scheduler.select(1, capacity, Map.of()); var second = scheduler.select(1, capacity, Map.of());
		var transaction = scheduler.claim(first, capacity, Map.of(P, amount(1)));
		assertNotNull(transaction);
		assertSame(transaction, scheduler.claim(first, capacity, Map.of(P, amount(1))));
		assertNull(scheduler.claim(second, capacity, Map.of(P, amount(1))));
		assertEquals(1, ledger.snapshot().pendingTransactions());
		var old = scheduler.select(1, capacity, Map.of());
		scheduler.replace(index);
		assertNull(scheduler.claim(old, capacity, Map.of(P, amount(1))));
		var fresh = scheduler.select(1, capacity, Map.of());
		assertNull(scheduler.claim(fresh, capacity(), Map.of(P, amount(1))));
		assertNull(scheduler.claim(fresh, capacity, Map.of(B, amount(1))));
	}
	@Test
	void onlyEligibleVariantsReceiveSharedGroupAllowance() {
		var components = new CompoundTag(); components.putString("minecraft:custom_name", "a");
		var a = new ProductKey(A.kind(), A.id(), components);
		components.putString("minecraft:custom_name", "b"); var b = new ProductKey(A.kind(), A.id(), components);
		var ledger = ledger(a, b, P);
		ledger.insert(a, amount(100), ProductLedger.Action.EXECUTE); ledger.insert(b, amount(100), ProductLedger.Action.EXECUTE);
		var reserves = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING,
				new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(matcher(a), ReserveLimit.floor(amount(150)))), ReservePolicy.Layer.NONE);
		var onlyB = new ProcessingRule("only_b", 1, true, 0, 1, 100,
				new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.EXACT, b)), reserves);
		var scheduler = new ProcessingRuleScheduler(ledger, new ProcessingRuleIndex(List.of(onlyB), List.of(recipe(A)), Map.of()), ProcessingRuleScheduler.Mode.FAIR);
		var capacity = capacity(); var selection = scheduler.select(1, capacity, Map.of());
		assertNotNull(selection); assertEquals(b, selection.input()); assertEquals(50, selection.operations());
		assertTrue(ledger.commit(scheduler.claim(selection, capacity, Map.of(P, amount(50)))));
		assertEquals(amount(100), ledger.available(a)); assertEquals(amount(50), ledger.available(b));
		assertNull(scheduler.select(1, capacity, Map.of()));
	}
	@Test
	void tagAndOutputGoalIndexesHonorRealRecipeCapabilitiesAndInflightStock() {
		var ledger = ledger(A, P); ledger.insert(A, amount(100), ProductLedger.Action.EXECUTE);
		var tagId = ResourceLocation.parse("test:combs");
		var byTag = new ProcessingRule("tag", 1, true, 0, 1, 1, new ProcessingRule.Tag(A.kind(), tagId), NONE);
		var byGoal = new ProcessingRule("goal", 1, true, 10, 1, 1, new ProcessingRule.Goal(P, amount(5), amount(10)), NONE);
		var index = new ProcessingRuleIndex(List.of(byTag, byGoal), List.of(recipe(A)),
				Map.of(new ProcessingRuleIndex.TagId(A.kind(), tagId), Set.of(A.id())));
		var scheduler = new ProcessingRuleScheduler(ledger, index, ProcessingRuleScheduler.Mode.STRICT_PRIORITY);
		var capacity = capacity();
		assertNull(scheduler.select(1, new CapacityPoolIndex(List.of()), Map.of()));
		assertEquals("goal", scheduler.select(1, capacity, Map.of()).rule().id());
		assertEquals("tag", scheduler.select(1, capacity, Map.of(P, amount(10))).rule().id());
		ledger.insert(P, amount(7), ProductLedger.Action.EXECUTE);
		assertEquals("tag", scheduler.select(1, capacity, Map.of()).rule().id());
		ledger.extract(P, amount(3), ProductLedger.Action.EXECUTE);
		assertEquals("goal", scheduler.select(1, capacity, Map.of()).rule().id());
	}
	@Test
	void blockedOrUnsupportedHighPriorityRuleDoesNotStarveUsableLowerRule() {
		var ledger = ledger(A, B, P); ledger.insert(A, amount(100), ProductLedger.Action.EXECUTE); ledger.insert(B, amount(100), ProductLedger.Action.EXECUTE);
		var all = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, new ReservePolicy.Layer(ReserveLimit.ALL, Map.of()), ReservePolicy.Layer.NONE);
		var high = new ProcessingRule("blocked", 1, true, 100, 1, 1, new ProcessingRule.Match(matcher(A)), all);
		var index = new ProcessingRuleIndex(List.of(high, rule("low", B, 0, 1)), List.of(recipe(A), recipe(B)), Map.of());
		var scheduler = new ProcessingRuleScheduler(ledger, index, ProcessingRuleScheduler.Mode.STRICT_PRIORITY);
		assertEquals("low", scheduler.select(1, capacity(), Map.of()).rule().id());
		var otherVersion = new ProcessingRecipe(new WorkKey(WORK.kind(), WORK.id(), 2, WORK.contextKey()), matcher(A), amount(1), Set.of(P));
		scheduler.replace(new ProcessingRuleIndex(List.of(rule("unsupported", A, 100, 1), rule("low", B, 0, 1)), List.of(otherVersion, recipe(B)), Map.of()));
		assertEquals("low", scheduler.select(1, capacity(), Map.of()).rule().id());
	}
	@Test
	void policyCallbackCannotReplaceSchedulerDuringClaim() {
		var ref = new AtomicReference<ProcessingRuleScheduler>(); var attack = new AtomicBoolean(true);
		var index = new ProcessingRuleIndex(List.of(rule("one", A, 0, 1)), List.of(recipe(A)), Map.of());
		var dynamic = new DynamicProductRule("test:output", P.kind(), P.id(), false, key -> {
			if (attack.get()) ref.get().replace(index); return true;
		});
		var registry = new ProductPolicyRegistry(new ProductPolicySnapshot(1, List.of(new AllowedProductDescriptor(A, "test", "test:recipe")), List.of(dynamic)));
		var ledger = new ProductLedger(registry, 4); ledger.insert(A, amount(10), ProductLedger.Action.EXECUTE);
		var scheduler = new ProcessingRuleScheduler(ledger, index, ProcessingRuleScheduler.Mode.FAIR); ref.set(scheduler);
		var capacity = capacity(); var selection = scheduler.select(1, capacity, Map.of());
		assertNull(scheduler.claim(selection, capacity, Map.of(P, amount(1))));
		assertEquals(0, ledger.snapshot().pendingTransactions());
		attack.set(false);
		assertNotNull(scheduler.claim(selection, capacity, Map.of(P, amount(1))));
	}
}
