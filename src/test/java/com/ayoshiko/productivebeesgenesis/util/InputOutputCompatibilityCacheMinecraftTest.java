package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class InputOutputCompatibilityCacheMinecraftTest {
	@Test
	void componentChangesInputCountsAndThirdOutputArePartOfTheKey() {
		Level level = mock(Level.class);
		var cache = new InputOutputCompatibilityCache();
		var input = new ItemStack(Items.HONEYCOMB);
		var output = new ItemStack(Items.IRON_INGOT);
		assertTrue(cache.get(level, input, output, null, null, () -> true));
		input.set(DataComponents.CUSTOM_NAME, Component.literal("changed in place"));
		assertFalse(cache.get(level, input, output, null, null, () -> false));
		input.grow(1);
		assertTrue(cache.get(level, input, output, null, null, () -> true));
		output.set(DataComponents.CUSTOM_NAME, Component.literal("changed output"));
		assertFalse(cache.get(level, input, output, null, null, () -> false));
		assertTrue(cache.get(level, input, output, null, new ItemStack(Items.GOLD_INGOT), () -> true));
	}

	@Test
	void reloadWorldChangeExpiryAndClearRevalidate() {
		var tick = new AtomicLong(10);
		Level level = mock(Level.class);
		when(level.getGameTime()).thenAnswer(call -> tick.get());
		var cache = new InputOutputCompatibilityCache(20);
		var input = new ItemStack(Items.IRON_ORE);
		var output = new ItemStack(Items.IRON_INGOT);
		assertTrue(cache.get(level, input, output, null, () -> true));
		ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
		assertFalse(cache.get(level, input, output, null, () -> false));
		tick.set(30);
		assertTrue(cache.get(level, input, output, null, () -> true));
		tick.set(1);
		assertFalse(cache.get(level, input, output, null, () -> false));
		assertTrue(cache.get(mock(Level.class), input, output, null, () -> true));
		cache.clear();
		assertFalse(cache.get(level, input, output, null, () -> false));
	}

}
