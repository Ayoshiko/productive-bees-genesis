package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductKeyTest {
	@Test
	void nestedComponentsAreDetachedOnInputAndOutput() {
		var nested = new CompoundTag();
		nested.putIntArray("values", new int[] {1, 2, 3});
		var source = new CompoundTag();
		source.put("test:component", nested);
		var key = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), source);
		var same = new ProductKey(ProductKey.Kind.ITEM, key.id(), source.copy());
		nested.getIntArray("values")[0] = 9;
		key.components().getCompound("test:component").getIntArray("values")[0] = 10;
		((CompoundTag) key.component("test:component").orElseThrow()).putString("injected", "value");
		assertEquals(same, key);
		assertEquals(same.hashCode(), key.hashCode());
		assertNotEquals(key, new ProductKey(ProductKey.Kind.ITEM, key.id(), source));
		assertNotEquals(key, new ProductKey(ProductKey.Kind.FLUID, key.id(), same.components()));
	}
	@Test
	void fullEqualityResolvesComponentHashCollisionsAndIgnoresMapOrder() {
		var a = new CompoundTag();
		a.putString("test:name", "Aa");
		var b = new CompoundTag();
		b.putString("test:name", "BB");
		var id = ResourceLocation.parse("test:product");
		var first = new ProductKey(ProductKey.Kind.ITEM, id, a);
		var second = new ProductKey(ProductKey.Kind.ITEM, id, b);
		assertEquals(first.hashCode(), second.hashCode());
		assertNotEquals(first, second);
		var store = new SparseProductAmounts<ProductKey>();
		store.add(first, 12);
		store.add(second, 34);
		assertEquals(2, store.size());
		assertEquals(12, store.visible(first));
		assertEquals(34, store.visible(second));
		var left = new CompoundTag(); left.putInt("a", 1); left.putInt("b", 2);
		var right = new CompoundTag(); right.putInt("b", 2); right.putInt("a", 1);
		assertEquals(new ProductKey(ProductKey.Kind.ITEM, id, left), new ProductKey(ProductKey.Kind.ITEM, id, right));
	}
}
