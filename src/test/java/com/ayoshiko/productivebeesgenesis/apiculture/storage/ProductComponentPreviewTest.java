package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductComponentPreviewTest {
	@Test void commonBeeComponentsDistinguishVariantsWithoutChangingIdentity() {
		var iron = new CompoundTag(); iron.putString("productivebees:bee_type", "productivebees:iron");
		var gold = new CompoundTag(); gold.putString("productivebees:bee_type", "productivebees:gold");
		var first = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:comb"), iron);
		var second = new ProductKey(ProductKey.Kind.ITEM, first.id(), gold);
		assertTrue(first.componentPreview().contains("productivebees:iron"));
		assertNotEquals(first.componentPreview(), second.componentPreview());
		assertEquals(iron, first.components());
		var named = new CompoundTag(); named.putString("default", "x".repeat(10000)); named.putString("minecraft:custom_name", "red");
		assertTrue(ProductComponentPreview.describe(named, 80).startsWith("{minecraft:custom_name=red"));
	}
	@Test void hugeNestedAndUnicodeValuesRemainBounded() {
		var components = new CompoundTag(); components.putString("name", "🐝".repeat(100000));
		String result = ProductComponentPreview.describe(components, 80);
		assertTrue(result.length() <= 80); assertTrue(result.endsWith("…"));
		assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 2)));
		components = new CompoundTag(); components.putByteArray("blob", new byte[1024 * 1024]);
		assertEquals("{blob=[1048576]}", ProductComponentPreview.describe(components, 80));
		var nested = new CompoundTag(); nested.put("child", components); var root = new CompoundTag(); root.put("nested", nested);
		assertEquals("{nested={child={…}}}", ProductComponentPreview.describe(root, 80));
	}
}
