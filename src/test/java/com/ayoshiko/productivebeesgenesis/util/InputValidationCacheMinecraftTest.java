package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
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
	void sameTickRecipeAndWorldChangesReplaceCachedResultsWithoutOverwritingNewGeneration() {
		long originalVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		var level = mock(Level.class);
		var otherLevel = mock(Level.class);
		when(level.getGameTime()).thenReturn(10L);
		when(otherLevel.getGameTime()).thenReturn(10L);
		var cache = new InputValidationCache();
		var removed = new ItemStack(Items.IRON_ORE);
		var added = new ItemStack(Items.GOLD_ORE);
		var valid = new InputValidationCache.ValidationResult(true, null, null, false);
		try {
			assertTrue(cache.get(level, removed, () -> true));
			assertFalse(cache.getResult(level, added, () -> InputValidationCache.ValidationResult.INVALID).valid());
			ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
			assertFalse(cache.get(level, removed, () -> false));
			assertTrue(cache.getResult(level, added, () -> valid).valid());
			assertFalse(cache.getResult(level, removed.copyWithCount(17),
					() -> fail("removed recipe must retain its current negative result")).valid());
			assertTrue(cache.get(level, added.copyWithCount(17),
					() -> fail("added recipe must retain its current positive result")));
			assertTrue(cache.get(otherLevel, removed, () -> true));
			assertFalse(cache.getResult(otherLevel, added, () -> InputValidationCache.ValidationResult.INVALID).valid());
			cache.clear();
			assertTrue(cache.getResult(otherLevel, removed, () -> {
				cache.clear();
				assertFalse(cache.get(otherLevel, removed, () -> false));
				return valid;
			}).valid());
			assertFalse(cache.getResult(otherLevel, removed,
					() -> fail("an older validation callback must not overwrite the cleared generation")).valid());
		} finally {
			ProductiveBeesGenesis.RECIPE_VERSION.set(originalVersion);
		}
	}

}
