package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
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
		for (int pass = 0; pass < 2; pass++) {
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
	void copiedComponentsInOneBeeHashBucketKeepIndependentCachedResults() {
		var cache = new InputValidationCache(100, 64);
		var level = mock(Level.class);
		var inputs = new ArrayList<ItemStack>();
		for (int index = 0; index < 38; index++) {
			var input = new ItemStack(PbCombItemRefs.honeycomb());
			input.set(PbDataComponents.beeType(), ResourceLocation.parse("test:shared_bucket"));
			input.set(DataComponents.CUSTOM_NAME, Component.literal("component-" + index));
			inputs.add(input);
			boolean valid = index % 2 == 0;
			assertEquals(valid, cache.get(level, input, () -> valid));
		}
		for (int pass = 0; pass < 3; pass++) {
			for (int index = 0; index < inputs.size(); index++) {
				var copy = inputs.get(index).copyWithCount(17 + pass);
				assertEquals(index % 2 == 0, cache.getResult(level, copy,
						() -> fail("complete component identity must remain cached")).valid());
			}
		}
	}

	@Test
	void sameBeeTypeWithDifferentComponentsKeepsPositiveAndNegativeResultsSeparate() {
		var level = mock(Level.class);
		for (var item : new Item[]{PbCombItemRefs.honeycomb(), PbCombItemRefs.combBlock()}) {
			var cache = new InputValidationCache();
			var first = new ItemStack(item);
			first.set(PbDataComponents.beeType(), ResourceLocation.parse("test:component_sensitive"));
			first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
			var second = first.copy();
			second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
			var ingredient = DataComponentIngredient.of(false, first);
			assertTrue(ingredient.test(first));
			assertFalse(ingredient.test(second));
			assertTrue(cache.getResult(level, first, () -> new InputValidationCache.ValidationResult(
					ingredient.test(first), null, first.get(PbDataComponents.beeType()), false)).valid());
			assertFalse(cache.getResult(level, second, () -> new InputValidationCache.ValidationResult(
					ingredient.test(second), null, second.get(PbDataComponents.beeType()), false)).valid());
			assertFalse(cache.get(level, second.copyWithCount(17), () -> fail("negative identity must stay cached")));
			first.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
			assertFalse(cache.get(level, first, () -> fail("mutated stack must use its new identity")));
			first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
			assertTrue(cache.get(level, first, () -> fail("positive identity must stay cached")));
		}
	}

	@Test
	void nestedQueriesKeepTheOuterFingerprintAndComponentSnapshot() {
		var level = mock(Level.class);
		for (Item item : new Item[] {Items.IRON_INGOT, PbCombItemRefs.honeycomb()}) {
			var cache = new InputValidationCache();
			var first = new ItemStack(item);
			first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
			var original = first.copy();
			var second = first.copy();
			second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
			var accepted = new InputValidationCache.ValidationResult(true, null, null, false);

			assertSame(accepted, cache.getResult(level, first, () -> {
				assertFalse(cache.get(level, second, () -> false));
				first.set(DataComponents.CUSTOM_NAME, Component.literal("changed during validation"));
				return accepted;
			}));
			assertSame(accepted, cache.getResult(level, original,
					() -> fail("outer key must retain the input before nested validation")));
			assertFalse(cache.getResult(level, second,
					() -> fail("nested negative result must keep its own key")).valid());
			assertFalse(cache.get(level, first, () -> false));
			assertTrue(cache.get(level, original, () -> fail("later queries must not mutate a stored key")));

			cache.clear();
			assertTrue(cache.get(level, original, () -> {
				assertFalse(cache.getResult(level, second, () -> InputValidationCache.ValidationResult.INVALID).valid());
				return true;
			}));
			assertTrue(cache.get(level, original, () -> fail("boolean lookup must snapshot before reentry")));
			assertFalse(cache.get(level, second, () -> fail("nested result must remain separate")));
		}
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
