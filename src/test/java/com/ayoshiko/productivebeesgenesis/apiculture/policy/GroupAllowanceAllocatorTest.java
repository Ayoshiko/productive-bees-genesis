package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductLedger;
import java.math.BigInteger;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroupAllowanceAllocatorTest {
	static ProductAmount amount(long value) { return ProductAmount.of(value); }
	static ProductKey comb(String bee, String name) {
		var components = new CompoundTag(); components.putString(ProductMatcher.BEE_TYPE, bee);
		components.putString("minecraft:custom_name", name);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:comb"), components);
	}
	static ReserveLimit floor(long value) { return ReserveLimit.floor(amount(value)); }
	static ReservePolicy policy(ReservePolicy.Layer global, ReservePolicy.Layer rule) {
		return new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, global, rule);
	}
	@Test
	void documentedSingleKeyExampleAndLayerZeroCannotRelaxGlobalFloor() {
		var a = comb("test:iron", "a");
		var snapshot = new ProductLedger.Snapshot(17, Map.of(a, amount(1000)), Map.of(a, amount(100)), 1);
		var plan = GroupAllowanceAllocator.allocate(snapshot, policy(new ReservePolicy.Layer(floor(200), Map.of()),
				new ReservePolicy.Layer(floor(300), Map.of())));
		assertEquals(amount(600), plan.allowances().get(a));
		assertEquals(17, plan.ledgerRevision());
		var globalOnly = GroupAllowanceAllocator.allocate(snapshot, policy(new ReservePolicy.Layer(floor(200), Map.of()), ReservePolicy.Layer.NONE));
		assertEquals(amount(700), globalOnly.allowances().get(a));
	}
	@Test
	void groupFloorIsAllocatedOnceAcrossAllVariantsAndRepeatedQueriesArePure() {
		var a = comb("test:iron", "Aa"); var b = comb("test:iron", "BB");
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a.orderingKey(), b.orderingKey());
		var stock = Map.of(a, amount(100), b, amount(100));
		var config = policy(new ReservePolicy.Layer(ReserveLimit.NONE,
				Map.of(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, a), floor(150))), ReservePolicy.Layer.NONE);
		var allocated = GroupAllowanceAllocator.allocateAvailable(stock, config);
		assertEquals(amount(50), allocated.get(a).add(allocated.get(b)));
		assertEquals(allocated, GroupAllowanceAllocator.allocateAvailable(Map.of(b, amount(100), a, amount(100)), config));
		assertEquals(allocated, GroupAllowanceAllocator.allocateAvailable(stock, config));
	}
	@Test
	void exactExceptionIsExcludedOnlyFromItsOwnLayerGroup() {
		var a = comb("test:iron", "a"); var b = comb("test:iron", "b");
		var group = new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, a);
		var exact = new ProductMatcher(ProductMatcher.Mode.EXACT, a);
		var global = new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(group, floor(50), exact, ReserveLimit.NONE));
		var rule = new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(group, floor(150)));
		var allocated = GroupAllowanceAllocator.allocateAvailable(Map.of(a, amount(100), b, amount(100)), policy(global, rule));
		assertEquals(amount(50), allocated.get(a).add(allocated.get(b)));
		var withoutRule = GroupAllowanceAllocator.allocateAvailable(Map.of(a, amount(100), b, amount(100)), policy(global, ReservePolicy.Layer.NONE));
		assertEquals(amount(100), withoutRule.get(a));
		assertEquals(amount(50), withoutRule.get(b));
	}
	@Test
	void beeGroupsDoNotMixBeeTypesOrCombUnitsAndAllIsExplicit() {
		var iron = comb("test:iron", "a"); var gold = comb("test:gold", "a");
		var block = new ProductKey(iron.kind(), ResourceLocation.parse("test:comb_block"), iron.components());
		var group = new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, iron);
		assertFalse(group.matches(gold)); assertFalse(group.matches(block));
		var config = policy(new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(group, ReserveLimit.ALL)), ReservePolicy.Layer.NONE);
		var result = GroupAllowanceAllocator.allocateAvailable(Map.of(iron, amount(100), gold, amount(100), block, amount(100)), config);
		assertEquals(ProductAmount.ZERO, result.get(iron)); assertEquals(amount(100), result.get(gold)); assertEquals(amount(100), result.get(block));
	}
	@Test
	void hugeFloorsAndIntersectingBaseAndBeeGroupsRemainExact() {
		var a = comb("test:iron", "a"); var b = comb("test:iron", "b"); var c = comb("test:gold", "a");
		var huge = ProductAmount.of(BigInteger.ONE.shiftLeft(200));
		var global = new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, a), ReserveLimit.floor(huge)));
		var rule = new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, a), ReserveLimit.floor(huge)));
		var result = GroupAllowanceAllocator.allocateAvailable(Map.of(a, huge, b, huge, c, huge), policy(global, rule));
		assertEquals(huge, result.get(a).add(result.get(b)));
		assertEquals(huge.add(huge), result.get(a).add(result.get(b)).add(result.get(c)));
	}
	@Test
	void policiesHaveIndependentConsumerScopesAndInvalidBalancesFailClosed() {
		var a = comb("test:iron", "a");
		var snapshot = new ProductLedger.Snapshot(1, Map.of(a, amount(100)), Map.of(), 0);
		for (var scope : ReservePolicy.Scope.values()) {
			var config = new ReservePolicy(scope, new ReservePolicy.Layer(floor(scope.ordinal() * 10), Map.of()), ReservePolicy.Layer.NONE);
			var plan = GroupAllowanceAllocator.allocate(snapshot, config);
			assertEquals(scope, plan.scope()); assertEquals(amount(100 - scope.ordinal() * 10), plan.allowances().get(a));
		}
		assertThrows(IllegalArgumentException.class, () -> GroupAllowanceAllocator.allocate(
				new ProductLedger.Snapshot(1, Map.of(a, amount(5)), Map.of(a, amount(6)), 1), policy(ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE)));
	}
}
