package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductPolicySnapshotBuilderTest {
	private static ProductKey key(int variant) {
		var tag = new CompoundTag(); tag.putInt("test:variant", variant);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), tag);
	}
	@Test void publishedCatalogRetainsOrderAndCannotBeMutated() {
		var builder = new ProductPolicySnapshot.Builder(); var key = key(0);
		var first = new AllowedProductDescriptor(key, "test", "first"); var second = new AllowedProductDescriptor(key, "test", "second");
		builder.add(first); builder.add(second); var snapshot = builder.build(5);
		assertEquals(List.of(first, second), snapshot.descriptors(key)); assertEquals(2, snapshot.descriptorCount());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.descriptors(key).clear());
		assertThrows(IllegalStateException.class, () -> builder.add(first)); assertThrows(IllegalStateException.class, () -> builder.build(6));
		assertEquals(List.of(first, second), snapshot.withRevision(9).descriptors(key)); assertEquals(5, snapshot.revision());
	}
	@Test void catalogMatchesDefensiveConstructorForManyKeysAndDuplicates() {
		var builder = new ProductPolicySnapshot.Builder(); var descriptors = new ArrayList<AllowedProductDescriptor>();
		for (int i = 0; i < 10_000; i++) { var descriptor = new AllowedProductDescriptor(key(i % 997), "test", "recipe/" + i); descriptors.add(descriptor); builder.add(descriptor); }
		var expected = new ProductPolicySnapshot(3, descriptors, List.of()); var actual = builder.build(3);
		for (int i = 0; i < 998; i++) assertEquals(expected.descriptors(key(i)), actual.descriptors(key(i)));
		assertEquals(expected.descriptorCount(), actual.descriptorCount());
	}
	@Test void invalidRevisionDoesNotConsumeBuilderAndRevisionViewPreservesDynamicRules() {
		var builder = new ProductPolicySnapshot.Builder(); assertThrows(IllegalArgumentException.class, () -> builder.build(-1));
		assertEquals(0, builder.build(0).descriptorCount());
		var key = key(0); var rule = new DynamicProductRule("test:dynamic", key.kind(), key.id(), false, candidate -> true);
		var snapshot = new ProductPolicySnapshot(1, List.of(), List.of(rule));
		assertEquals(List.of(rule), snapshot.withRevision(2).dynamicRules(key)); assertThrows(IllegalArgumentException.class, () -> snapshot.withRevision(-1));
	}
	@Test void constructionRejectsForeignThread() throws InterruptedException {
		var builder = new ProductPolicySnapshot.Builder(); var failure = new AtomicReference<Throwable>();
		var thread = new Thread(() -> { try { builder.build(0); } catch (Throwable error) { failure.set(error); } });
		thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure.get()); assertEquals(0, builder.build(0).descriptorCount());
	}
}
