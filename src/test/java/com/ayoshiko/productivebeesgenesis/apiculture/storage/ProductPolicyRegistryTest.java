package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductPolicyRegistryTest {
	static ProductKey item(String name) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse(name), new CompoundTag()); }
	static ProductPolicySnapshot policy(long revision, ProductKey... templates) {
		return new ProductPolicySnapshot(revision, java.util.Arrays.stream(templates)
				.map(key -> new AllowedProductDescriptor(key, "test", "test:recipe")).toList(), List.of());
	}
	@Test
	void fullHashCollisionDiscoveriesRemainExactAcrossFreezeAdaptersAndReload() {
		var keys = new java.util.ArrayList<ProductKey>();
		for (int i = 0; i < 512; i++) {
			var text = new StringBuilder();
			for (int bit = 0; bit < 9; bit++) text.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
			var components = new CompoundTag(); components.putString("test:collision", text.toString());
			keys.add(new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:dynamic"), components));
			assertEquals(keys.getFirst().hashCode(), keys.getLast().hashCode());
		}
		var first = new DynamicProductRule("test:first", keys.getFirst().kind(), keys.getFirst().id(), true, key -> true);
		var second = new DynamicProductRule("test:second", first.kind(), first.id(), true, key -> true);
		var policy = new ProductPolicySnapshot(1, List.of(), List.of(first, second)); var registry = new ProductPolicyRegistry(policy);
		for (int i = 0; i < 256; i++) assertTrue(registry.recordVerifiedProduction("test:first", keys.get(i), 1));
		var frozen = registry.discoveries();
		for (int i = 0; i < 512; i++) assertTrue(registry.recordVerifiedProduction("test:second", keys.get(i), 1));
		assertEquals(768, registry.discoveries().size()); registry.replace(new ProductPolicySnapshot(2, List.of(), List.of(first, second)));
		assertTrue(registry.discoveries().isEmpty()); assertEquals(256, frozen.size());
		var restored = new ProductPolicyRegistry(policy); restored.restoreDiscoveries(1, frozen);
		for (int i = 0; i < 512; i++) assertEquals(i < 256, restored.evaluate(keys.get(i)).allowed());
		assertTrue(frozen.stream().allMatch(value -> value.adapterId().equals("test:first")));
	}
	@Test
	void externalProductsAllowCosmeticsButRejectNewContentsOrWrongBee() {
		var gold = item("minecraft:gold_ingot");
		var bee = new CompoundTag(); bee.putString("productivebees:bee_type", "productivebees:iron");
		var comb = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("productivebees:configurable_honeycomb"), bee);
		var registry = new ProductPolicyRegistry(policy(1, gold, comb));
		assertTrue(registry.evaluate(gold).allowed());
		var name = gold.components(); name.putString("minecraft:custom_name", "named");
		var renamed = new ProductKey(gold.kind(), gold.id(), name);
		assertTrue(registry.evaluate(renamed).allowed());
		assertNotEquals(gold, renamed);
		assertFalse(registry.evaluate(item("minecraft:command_block")).allowed());
		name.put("minecraft:container", new CompoundTag());
		assertFalse(registry.evaluate(new ProductKey(gold.kind(), gold.id(), name)).allowed());
		name.remove("minecraft:container"); name.putString("test:unknown_component", "payload");
		assertFalse(registry.evaluate(new ProductKey(gold.kind(), gold.id(), name)).allowed());
		bee.putString("productivebees:bee_type", "productivebees:gold");
		assertFalse(registry.evaluate(new ProductKey(comb.kind(), comb.id(), bee)).allowed());
	}
	@Test
	void reloadInvalidatesBothPositiveAndNegativeResultsWithoutChangingOldSnapshot() {
		var gold = item("minecraft:gold_ingot");
		var iron = item("minecraft:iron_ingot");
		var old = policy(1, gold);
		var registry = new ProductPolicyRegistry(old);
		assertTrue(registry.evaluate(gold).allowed());
		assertFalse(registry.evaluate(iron).allowed());
		registry.replace(policy(2, iron));
		assertFalse(registry.evaluate(gold).allowed());
		assertTrue(registry.evaluate(iron).allowed());
		assertEquals(2, registry.evaluate(iron).revision());
		assertTrue(old.descriptors(gold).getFirst().accepts(gold));
		assertThrows(IllegalArgumentException.class, () -> registry.replace(policy(2, gold)));
	}
	@Test
	void discoveryIsExactAndBoundToAdapterAndRevision() {
		var key = item("test:dynamic");
		var rule = new DynamicProductRule("test:loot", key.kind(), key.id(), true, candidate -> candidate.components().isEmpty());
		var registry = new ProductPolicyRegistry(new ProductPolicySnapshot(1, List.of(), List.of(rule)));
		assertEquals(ProductPolicyRegistry.Verdict.UNCONFIRMED_VARIANT, registry.evaluate(key).verdict());
		assertFalse(registry.recordVerifiedProduction("wrong", key, 1));
		assertFalse(registry.recordVerifiedProduction("test:loot", key, 0));
		assertTrue(registry.recordVerifiedProduction("test:loot", key, 1));
		assertTrue(registry.evaluate(key).allowed());
		var components = new CompoundTag(); components.putString("bad", "contents");
		assertFalse(registry.recordVerifiedProduction("test:loot", new ProductKey(key.kind(), key.id(), components), 1));
		registry.replace(new ProductPolicySnapshot(2, List.of(), List.of(rule)));
		assertEquals(ProductPolicyRegistry.Verdict.UNCONFIRMED_VARIANT, registry.evaluate(key).verdict());
	}
	@Test
	void adapterFailureOrReentryCannotMutatePolicyOrPoisonFutureQueries() {
		var key = item("test:dynamic");
		ProductPolicyRegistry[] ref = new ProductPolicyRegistry[1];
		var rule = new DynamicProductRule("test:broken", key.kind(), key.id(), false, candidate -> {
			ref[0].replace(policy(2, key)); return true;
		});
		ref[0] = new ProductPolicyRegistry(new ProductPolicySnapshot(1, List.of(), List.of(rule)));
		assertEquals(ProductPolicyRegistry.Verdict.ADAPTER_FAILURE, ref[0].evaluate(key).verdict());
		assertEquals(1, ref[0].snapshot().revision());
		ref[0].replace(policy(2, key));
		assertTrue(ref[0].evaluate(key).allowed());
	}
}
