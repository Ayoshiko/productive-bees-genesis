package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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

}
