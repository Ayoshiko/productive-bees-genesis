package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import appeng.api.stacks.AEItemKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class AeItemKeyCacheMinecraftTest {
	@Test
	void countChangesAndEquivalentReplacementReuseTheSameKey() {
		var cache = new AeItemKeyCache(3);
		var stack = new ItemStack(Items.IRON_INGOT, 12);
		var first = cache.get(0, stack);
		stack.setCount(40);
		assertSame(first, cache.get(0, stack));
		assertSame(first, cache.get(0, stack.copyWithCount(3)));
	}

	@Test
	void inPlaceComponentChangesCannotReuseAStaleKey() {
		var cache = new AeItemKeyCache(3);
		var stack = new ItemStack(Items.IRON_INGOT, 12);
		var first = cache.get(0, stack);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("named output"));
		var named = cache.get(0, stack);
		assertNotSame(first, named);
		assertEquals(AEItemKey.of(stack), named);
		stack.remove(DataComponents.CUSTOM_NAME);
		assertEquals(first, cache.get(0, stack));
	}

	@Test
	void emptySlotAndClearReleaseCachedIdentity() {
		var cache = new AeItemKeyCache(3);
		var stack = new ItemStack(Items.IRON_INGOT);
		var first = cache.get(0, stack);
		assertNull(cache.get(0, ItemStack.EMPTY));
		assertNotSame(first, cache.get(0, stack));
		var second = cache.get(0, stack);
		cache.clear();
		assertNotSame(second, cache.get(0, stack));
	}
}
