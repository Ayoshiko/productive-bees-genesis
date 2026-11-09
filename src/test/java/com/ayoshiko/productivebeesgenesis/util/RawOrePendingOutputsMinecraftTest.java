package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import mekanism.api.recipes.basic.BasicSmeltingRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class RawOrePendingOutputsMinecraftTest {
	private Map<TagKey<Item>, List<Holder<Item>>> originalTags;

	@BeforeEach
	void prepareRawMaterialTags() {
		originalTags = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
				pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
		var tags = new HashMap<>(originalTags);
		tags.put(TagKey.create(Registries.ITEM, ResourceLocation.parse("c:raw_materials")),
				List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.RAW_IRON)));
		tags.put(TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ingots")),
				List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.IRON_INGOT),
						BuiltInRegistries.ITEM.wrapAsHolder(Items.GOLD_INGOT)));
		BuiltInRegistries.ITEM.bindTags(tags);
		MekanismRecipeType.clearCache();
		RawOreSmeltingUpgradeHelper.invalidateCache();
	}

	@AfterEach
	void restoreTagsAndCaches() {
		BuiltInRegistries.ITEM.bindTags(originalTags);
		MekanismRecipeType.clearCache();
		RawOreSmeltingUpgradeHelper.invalidateCache();
	}

	@Test
	void nonRawItemsOnlyCheckTagsOnceUntilInvalidation() {
		var diamond = org.mockito.Mockito.spy(new ItemStack(Items.DIAMOND));
		var level = mock(Level.class);
		var outputs = Map.of(diamond, 4096);
		for (int i = 0; i < 256; i++) {
			assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		}
		org.mockito.Mockito.verify(diamond, org.mockito.Mockito.times(4)).is(
				org.mockito.ArgumentMatchers.<TagKey<Item>>any());
		RawOreSmeltingUpgradeHelper.invalidateCache();
		assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		org.mockito.Mockito.verify(diamond, org.mockito.Mockito.times(8)).is(
				org.mockito.ArgumentMatchers.<TagKey<Item>>any());
		assertEquals(4096, outputs.get(diamond));
	}

	@Test
	void ordinaryPendingOutputsKeepTheirOriginalTemplates() {
		var diamond = new ItemStack(Items.DIAMOND);
		var outputs = Map.of(diamond, 100_000);
		assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(mock(Level.class), outputs));
		assertSame(diamond, outputs.keySet().iterator().next());
		assertEquals(1, diamond.getCount());
	}

	@Test
	void conversionPreservesRemaindersOtherOutputsAndLargeCounts() {
		var level = levelWithRecipes(smelting(Ingredient.of(Items.RAW_IRON), 3, Items.IRON_INGOT, 6));
		var raw = new ItemStack(Items.RAW_IRON);
		var diamond = new ItemStack(Items.DIAMOND);
		var outputs = new HashMap<ItemStack, Integer>();
		outputs.put(raw, Integer.MAX_VALUE);
		outputs.put(diamond, 37);
		assertTrue(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		assertEquals((Integer.MAX_VALUE / 3L) * 6, amount(outputs, Items.IRON_INGOT));
		assertEquals(Integer.MAX_VALUE % 3, amount(outputs, Items.RAW_IRON));
		assertEquals(37, amount(outputs, Items.DIAMOND));
		assertEquals(1, raw.getCount());
		assertEquals(1, diamond.getCount());
		var keys = List.copyOf(outputs.keySet());
		assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		assertTrue(outputs.keySet().containsAll(keys));
		assertEquals(keys.size(), outputs.size());
	}

	@Test
	void insufficientFirstBatchDoesNotPreventLaterCompleteBatch() {
		var level = levelWithRecipes(smelting(Ingredient.of(Items.RAW_IRON), 3, Items.IRON_INGOT, 6));
		var raw = new ItemStack(Items.RAW_IRON);
		var outputs = new HashMap<ItemStack, Integer>();
		outputs.put(raw, 2);
		assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		assertEquals(2, amount(outputs, Items.RAW_IRON));
		outputs.put(raw, 3);
		assertTrue(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		assertEquals(6, amount(outputs, Items.IRON_INGOT));
		assertEquals(0, amount(outputs, Items.RAW_IRON));
	}

	@Test
	void currentComponentsSelectTheirOwnCandidateWithoutReload() {
		var first = new ItemStack(Items.RAW_IRON);
		first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
		var second = new ItemStack(Items.RAW_IRON);
		second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
		var level = levelWithRecipes(
				smelting(DataComponentIngredient.of(false, first), 3, Items.IRON_INGOT, 6),
				smelting(DataComponentIngredient.of(false, second), 1, Items.GOLD_INGOT, 2));
		var outputs = new HashMap<ItemStack, Integer>();
		outputs.put(first, 3);
		assertTrue(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		assertEquals(6, amount(outputs, Items.IRON_INGOT));
		outputs.clear();
		outputs.put(second, 3);
		assertTrue(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
		assertEquals(6, amount(outputs, Items.GOLD_INGOT));
		assertEquals(0, amount(outputs, Items.IRON_INGOT));
	}

	private static long amount(Map<ItemStack, Integer> outputs, Item item) {
		return outputs.entrySet().stream().filter(e -> e.getKey().is(item)).mapToLong(Map.Entry::getValue).sum();
	}

	private static BasicSmeltingRecipe smelting(Ingredient ingredient, int inputCount, Item output, int outputCount) {
		return new BasicSmeltingRecipe(ItemStackIngredient.of(new SizedIngredient(ingredient, inputCount)),
				new ItemStack(output, outputCount));
	}

	private static Level levelWithRecipes(BasicSmeltingRecipe... recipes) {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var manager = new RecipeManager(registries);
		var holders = new ArrayList<RecipeHolder<?>>();
		for (int index = 0; index < recipes.length; index++) {
			holders.add(new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("test", "raw_smelting_" + index),
					recipes[index]));
		}
		manager.replaceRecipes(holders);
		var level = mock(Level.class);
		when(level.getRecipeManager()).thenReturn(manager);
		when(level.registryAccess()).thenReturn(registries);
		return level;
	}
}
