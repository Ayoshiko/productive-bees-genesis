package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

}
