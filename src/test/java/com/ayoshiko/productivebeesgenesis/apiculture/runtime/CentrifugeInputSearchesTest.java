package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter.Profile;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.runtime.CentrifugeInputSearches.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class CentrifugeInputSearchesTest {
	private static final Profile BASIC = new Profile(100, 1, 1, 10, 1, 0, false);
	private static final Profile FAST = new Profile(100, 2, 4, 20, 1, 0, false);
	private static ProductKey key(String id) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:" + id), new CompoundTag()); }
	private static RuntimeProcessingRules rules(SchedulerCheckpoint scheduler) {
		var rules = new RuntimeProcessingRules(scheduler); while (!rules.step()) { } return rules;
	}
	private static ProcessingStockIndex.View stock(LedgerCheckpoint ledger) {
		var index = new ProcessingStockIndex(ledger); while (!index.step()) { } return index.view();
	}
	@Test void manyMembersAndLanesScanEachRejectedKeyOnlyOnceAndSleepUntilInventoryChanges() {
		var searches = new CentrifugeInputSearches(); var members = new ArrayList<UUID>();
		for (int i = 0; i < 64; i++) members.add(UUID.randomUUID());
		var amounts = new HashMap<ProductKey, ProductAmount>();
		for (int i = 0; i < 100; i++) amounts.put(key("item_" + i), ProductAmount.of(1));
		var ledger = new LedgerCheckpoint(0, amounts, List.of()); var stock = stock(ledger);
		var scheduler = SchedulerCheckpoint.EMPTY; var rules = rules(scheduler);
		var examined = new HashSet<ProductKey>(); int[] calls = {0};
		for (int i = 0; i < 512; i++) {
			var cursor = searches.cursor(members.get(i % members.size()), BASIC);
			cursor.select(stock, rules, scheduler, key -> { calls[0]++; assertTrue(examined.add(key)); return false; });
		}
		assertEquals(100, calls[0]); assertEquals(1, searches.groups());
		assertEquals(EMPTY, searches.cursor(members.getFirst(), BASIC).select(stock, rules, scheduler, key -> fail("Repeated exhausted scan")));
		var wanted = key("new_input"); var changed = stock(ledger.insertExact(wanted, ProductAmount.of(7)));
		var cursor = searches.cursor(members.getLast(), BASIC);
		for (int i = 0; i < 105 && cursor.input == null; i++) cursor.select(changed, rules, scheduler, wanted::equals);
		assertEquals(wanted, cursor.input); assertEquals(ProductAmount.of(7), changed.amount(wanted));
	}
	@Test void profileChangesAndLastMemberRemovalRevokeOnlyTheAffectedSearch() {
		var searches = new CentrifugeInputSearches(); var a = UUID.randomUUID(); var b = UUID.randomUUID();
		var shared = searches.cursor(a, BASIC); assertSame(shared, searches.cursor(b, BASIC));
		var fast = searches.cursor(a, FAST); assertNotSame(shared, fast); assertEquals(2, searches.groups());
		searches.forget(b); assertEquals(1, searches.groups());
		assertNotSame(shared, searches.cursor(b, BASIC));
		assertSame(fast, searches.cursor(a, FAST));
		searches.clear(); assertEquals(0, searches.groups()); assertNotSame(fast, searches.cursor(a, FAST));
	}
	@Test void topologyAuditsRevokeCandidatesWithoutStarvingInputsBehindLongJobs() {
		var searches = new CentrifugeInputSearches(); var member = UUID.randomUUID();
		var a = key("a"); var b = key("b"); var scheduler = SchedulerCheckpoint.EMPTY; var rules = rules(scheduler);
		var stock = stock(new LedgerCheckpoint(0, Map.of(a, ProductAmount.of(100), b, ProductAmount.of(100)), List.of()));
		var selected = new ArrayList<ProductKey>();
		for (int job = 0; job < 6; job++) {
			var cursor = searches.cursor(member, BASIC);
			assertEquals(READY, cursor.select(stock, rules, scheduler, key -> true)); selected.add(cursor.input);
			cursor.ledger = stock.ledger();
			cursor.reserve = new ReserveAllowanceScan(stock,
					new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE), cursor.input);
			// 正式基础配方持续 300 tick，跨越 200 tick 拓扑审计；期间仍须撤销全部资产凭据。
			searches.invalidate(); searches.invalidate();
			assertNull(cursor.input); assertNull(cursor.reserve); assertNull(cursor.ledger);
			assertNull(cursor.scanLedger); assertNull(cursor.exhausted); assertNull(cursor.scheduler);
		}
		assertEquals(List.of(a, b, a, b, a, b), selected);
		searches.forget(member); assertEquals(0, searches.groups());
	}
	@Test void inventoryMutationDuringScanCannotPublishAnEmptyResultForUnvisitedNewKeys() {
		var searches = new CentrifugeInputSearches(); var cursor = searches.cursor(UUID.randomUUID(), BASIC);
		var scheduler = SchedulerCheckpoint.EMPTY; var rules = rules(scheduler);
		var ledger = new LedgerCheckpoint(0, Map.of(key("z"), ProductAmount.of(1)), List.of());
		assertEquals(SEARCHING, cursor.select(stock(ledger), rules, scheduler, key -> false));
		var wanted = key("a"); var updated = stock(ledger.insertExact(wanted, ProductAmount.of(1)));
		for (int i = 0; i < 8 && cursor.input == null; i++) {
			assertNotEquals(EMPTY, cursor.select(updated, rules, scheduler, wanted::equals));
		}
		assertEquals(wanted, cursor.input);
	}
	@Test void rulesAndReservationChangesInvalidateTheSharedNoInputConclusion() {
		var input = key("comb");
		var reserves = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
		var rule = new ProcessingRule("only", 0, true, 0, 1, 64,
				new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.EXACT, input)), reserves);
		var scheduler = new SchedulerCheckpoint(List.of(rule), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0);
		var cursor = new CentrifugeInputSearches().cursor(UUID.randomUUID(), BASIC);
		var ledger = new LedgerCheckpoint(0, Map.of(input, ProductAmount.of(2)), List.of()); var stock = stock(ledger);
		var rules = rules(scheduler);
		assertEquals(READY, cursor.select(stock, rules, scheduler, key -> true));
		cursor.reserved = true; cursor.nextInput();
		assertEquals(SEARCHING, cursor.select(stock, rules, scheduler, key -> true));
		assertEquals(RESERVED, cursor.select(stock, rules, scheduler, key -> true));
		var changed = new SchedulerCheckpoint(List.of(rule), ProcessingRuleScheduler.Mode.STRICT_PRIORITY, Map.of(), "", 0);
		assertEquals(READY, cursor.select(stock, rules(changed), changed, key -> true));
		assertEquals(input, cursor.input);
	}
}
