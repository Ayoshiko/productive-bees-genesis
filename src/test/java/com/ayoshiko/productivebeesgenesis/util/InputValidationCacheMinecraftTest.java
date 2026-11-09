package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
class InputValidationCacheMinecraftTest {
	@Test
	void thirtyEightCandidatesAcrossNineteenLanesValidateOncePerWindow() {
		var cache = new InputValidationCache();
		var level = mock(Level.class);
		var inputs = new ArrayList<ItemStack>();
		for (int i = 0; i < 38; i++) {
			var input = new ItemStack(PbCombItemRefs.honeycomb());
			input.set(PbDataComponents.beeType(), ResourceLocation.parse("test:bee_" + i));
			inputs.add(input);
		}
		var calls = new AtomicInteger();
		for (int pass = 0; pass < 256; pass++) {
			for (int type = 0; type < inputs.size(); type++) {
				boolean allowed = type % 2 == 0;
				for (int lane = 0; lane < 19; lane++) {
					assertEquals(allowed, cache.get(level, inputs.get(type), () -> {
						calls.incrementAndGet();
						return allowed;
					}));
				}
			}
		}
		assertEquals(38, calls.get());
	}

	@Test
	void evictionPromotesRecentEntriesAndExpiryDoesNotExtendOnHits() {
		var cache = new InputValidationCache(10, 2);
		var tick = new AtomicLong(5);
		var level = mock(Level.class);
		when(level.getGameTime()).thenAnswer(call -> tick.get());
		var a = new ItemStack(Items.IRON_INGOT);
		var b = new ItemStack(Items.GOLD_INGOT);
		var c = new ItemStack(Items.DIAMOND);
		assertTrue(cache.get(level, a, () -> true));
		assertFalse(cache.get(level, b, () -> false));
		assertTrue(cache.get(level, a, () -> fail("resident entry")));
		assertTrue(cache.get(level, c, () -> true));
		assertTrue(cache.get(level, b, () -> true));
		assertFalse(cache.get(level, a, () -> false));
		tick.set(14);
		assertFalse(cache.get(level, a, () -> fail("hit must not refresh TTL")));
		tick.set(15);
		assertTrue(cache.get(level, a, () -> true));
		tick.set(1);
		assertFalse(cache.get(level, a, () -> false));
	}

	@Test
	void componentMutationToggleAndReentrantInvalidationRevalidate() {
		var cache = new InputValidationCache();
		var level = mock(Level.class);
		var input = new ItemStack(Items.IRON_INGOT);
		assertTrue(cache.get(level, input, () -> true));
		input.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
		assertFalse(cache.get(level, input, () -> false));
		cache.setSmeltingAllowed(true);
		assertTrue(cache.get(level, input, () -> {
			cache.clear();
			assertFalse(cache.get(level, input, () -> false));
			return true;
		}));
		assertFalse(cache.get(level, input, () -> fail("old generation must not overwrite new result")));
		cache.clear();
		assertThrows(IllegalStateException.class, () -> cache.get(level, input, () -> {
			throw new IllegalStateException("validator failed");
		}));
		assertTrue(cache.get(level, input, () -> true));
	}
}
