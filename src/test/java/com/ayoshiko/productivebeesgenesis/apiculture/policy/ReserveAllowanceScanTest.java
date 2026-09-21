package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.policy.GroupAllowanceAllocatorTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ReserveAllowanceScanTest {
	private static ProcessingStockIndex.View stock(LedgerCheckpoint ledger) {
		var index = new ProcessingStockIndex(ledger); while (!index.step()) { } return index.view();
	}
	private static ReserveAllowanceScan.Permit permit(ProcessingStockIndex.View stock, ReservePolicy policy, ProductKey key) {
		var scan = new ReserveAllowanceScan(stock, policy, key); int steps = 0;
		while (!scan.step()) assertTrue(++steps < 10000); return scan.permit();
	}
	@Test void candidateAllowancesMatchTheExistingJointAllocatorWithLayerExceptions() {
		var a = comb("test:iron", "a"); var b = comb("test:iron", "b"); var c = comb("test:gold", "c");
		var keys = List.of(a, b, c); var random = new Random(160201L);
		for (int trial = 0; trial < 250; trial++) {
			Map<ProductKey, ProductAmount> balances = Map.of(a, amount(1 + random.nextInt(200)), b, amount(1 + random.nextInt(200)), c, amount(1 + random.nextInt(200)));
			var global = layer(keys, random); var local = layer(keys, random); var config = policy(global, local);
			var ledger = new LedgerCheckpoint(0, balances, List.of()); var stock = stock(ledger);
			for (var key : keys) {
				var expected = GroupAllowanceAllocator.allocate(new ProductLedger.Snapshot(0, balances, Map.of(), 0), config, Set.of(key)).allowances().get(key);
				assertEquals(expected, permit(stock, config, key).amount(), "trial " + trial + " " + key.orderingKey());
			}
		}
	}
	private static ReservePolicy.Layer layer(List<ProductKey> keys, Random random) {
		Map<ProductMatcher, ReserveLimit> entries = new ConcurrentHashMap<>();
		for (var key : keys) for (var mode : ProductMatcher.Mode.values()) if (random.nextBoolean())
			entries.put(new ProductMatcher(mode, key), random.nextInt(6) == 0 ? ReserveLimit.ALL : floor(random.nextInt(200)));
		return new ReservePolicy.Layer(floor(random.nextInt(50)), entries);
	}
	@Test void permitIsIncompleteUntilEveryExactExceptionHasBeenChecked() {
		var a = comb("test:iron", "a"); var b = comb("test:iron", "b");
		var ledger = new LedgerCheckpoint(0, Map.of(a, amount(100), b, amount(100)), List.of());
		var config = policy(new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, a), floor(150))), ReservePolicy.Layer.NONE);
		var scan = new ReserveAllowanceScan(stock(ledger), config, a);
		assertFalse(scan.step()); assertThrows(IllegalStateException.class, scan::permit); while (!scan.step()) { }
		assertEquals(amount(50), scan.permit().amount());
	}
	@Test void competingLanesCannotReuseTheSameGroupAllowanceOrAChangedScope() {
		var input = comb("test:iron", "a"); var other = comb("test:iron", "b");
		var ledger = new LedgerCheckpoint(0, Map.of(input, amount(100), other, amount(100)), List.of());
		var registry = new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(new AllowedProductDescriptor(input, "test", "test:comb")), List.of()));
		var plan = new CentrifugeRecipePlan("test:comb", 0, 0, input, 5, 100, 1, 1, 0, List.of(new CentrifugeRecipePlan.Output(input, 1, 1, 1)));
		var config = policy(new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, input), floor(150))), ReservePolicy.Layer.NONE);
		var permit = permit(stock(ledger), config, input); var state = new CentrifugeWorkState(UUID.randomUUID(), 0, 2, 1000, 1000, Map.of());
		var first = CentrifugeWorkTransaction.assignReserved(state, ledger, registry, 0, plan, 100, 0, 1000, config, permit);
		assertNotNull(first); assertEquals(50, first.state().jobs().get(0).operations());
		assertNull(CentrifugeWorkTransaction.assignReserved(first.state(), first.ledger(), registry, 1, plan, 100, 0, 1000, config, permit));
		var fresh = permit(stock(first.ledger()), config, input); assertTrue(fresh.amount().isZero());
		var export = new ReservePolicy(ReservePolicy.Scope.LOCAL_EXPORT, config.global(), config.rule());
		assertNull(CentrifugeWorkTransaction.assignReserved(state, ledger, registry, 0, plan, 100, 0, 1000, export, permit));
		assertFalse(permit.matches(ledger, policy(ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE), input));
	}
}
