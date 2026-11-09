package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class InputOutputCompatibilityCacheMinecraftTest {
	@Test
	void alternatingThirtyBeeTypesReuseResultsWithoutFullComponentHashing() {
		Level level = mock(Level.class);
		var cache = new InputOutputCompatibilityCache();
		var calls = new AtomicInteger();
		var inputs = new ArrayList<ItemStack>();
		for (int i = 0; i < 30; i++) {
			var stack = new ItemStack(Items.HONEYCOMB);
			stack.set(PbDataComponents.beeType(), ResourceLocation.parse("test:bee_" + i));
			inputs.add(stack);
		}
		for (int pass = 0; pass < 10; pass++) for (int i = 0; i < inputs.size(); i++) {
			boolean result = i % 2 == 0;
			assertEquals(result, cache.get(level, inputs.get(i).copy(), new ItemStack(Items.IRON_INGOT, pass + 1),
					ItemStack.EMPTY, () -> { calls.incrementAndGet(); return result; }));
		}
		assertEquals(30, calls.get(), "alternating positive and negative candidates should each validate once");
	}

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

	@Test
	void exceptionsAndReentrantQueriesDoNotOverwriteAnotherKey() {
		var cache = new InputOutputCompatibilityCache();
		Level level = mock(Level.class);
		var outer = new ItemStack(Items.IRON_ORE);
		var inner = new ItemStack(Items.GOLD_ORE);
		assertThrows(IllegalStateException.class, () -> cache.get(level, outer, ItemStack.EMPTY, null,
				() -> { throw new IllegalStateException("invalid recipe"); }));
		assertTrue(cache.get(level, outer, ItemStack.EMPTY, null, () -> {
			assertFalse(cache.get(level, inner, ItemStack.EMPTY, null, () -> false));
			return true;
		}));
		assertTrue(cache.get(level, outer, ItemStack.EMPTY, null, () -> fail("outer should be cached")));
		assertFalse(cache.get(level, inner, ItemStack.EMPTY, null, () -> fail("inner should be cached")));
	}

	@Test
	void invalidationDuringValidationDoesNotRestoreAnOldResult() {
		for (boolean reload : new boolean[] {false, true}) {
			var cache = new InputOutputCompatibilityCache();
			Level level = mock(Level.class);
			var input = new ItemStack(Items.IRON_ORE);
			assertTrue(cache.get(level, input, ItemStack.EMPTY, null, () -> {
				if (reload) ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
				else cache.clear();
				assertFalse(cache.get(level, input, ItemStack.EMPTY, null, () -> false));
				return true;
			}));
			assertFalse(cache.get(level, input, ItemStack.EMPTY, null,
					() -> fail("old in-flight validation must not overwrite the new generation")));
		}
	}

	@Test
	void cacheEvictsOldCandidates() {
		var cache = new InputOutputCompatibilityCache();
		Level level = mock(Level.class);
		for (int i = 0; i < 140; i++) {
			var input = new ItemStack(Items.HONEYCOMB);
			input.set(PbDataComponents.beeType(), ResourceLocation.parse("test:bee_" + i));
			assertTrue(cache.get(level, input, ItemStack.EMPTY, null, () -> true));
		}
		var oldest = new ItemStack(Items.HONEYCOMB);
		oldest.set(PbDataComponents.beeType(), ResourceLocation.parse("test:bee_0"));
		assertFalse(cache.get(level, oldest, ItemStack.EMPTY, null, () -> false));
	}
}
