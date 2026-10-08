package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class RawOrePendingOutputsMinecraftTest {
	@Test
	void nonRawItemsOnlyCheckTagsOnceUntilInvalidation() {
		RawOreSmeltingUpgradeHelper.invalidateCache();
		try {
			var diamond = org.mockito.Mockito.spy(new ItemStack(Items.DIAMOND));
			var level = mock(Level.class);
			var outputs = Map.of(diamond, 4096);
			for (int i = 0; i < 256; i++) assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
			org.mockito.Mockito.verify(diamond, org.mockito.Mockito.times(4)).is(
					org.mockito.ArgumentMatchers.<TagKey<Item>>any());
			RawOreSmeltingUpgradeHelper.invalidateCache();
			assertFalse(RawOreSmeltingUpgradeHelper.convertPendingOutputs(level, outputs));
			org.mockito.Mockito.verify(diamond, org.mockito.Mockito.times(8)).is(
					org.mockito.ArgumentMatchers.<TagKey<Item>>any());
			assertEquals(4096, outputs.get(diamond));
		} finally {
			RawOreSmeltingUpgradeHelper.invalidateCache();
		}
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
	void conversionPreservesRemaindersOtherOutputsAndLargeCounts() throws Exception {
		var originalTags = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
				pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
		try {
			var tags = new HashMap<>(originalTags);
			tags.put(TagKey.create(Registries.ITEM, ResourceLocation.parse("c:raw_materials")),
					List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.RAW_IRON)));
			BuiltInRegistries.ITEM.bindTags(tags);
			seedConversion(3, 6);
			var level = mock(Level.class);
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
		} finally {
			BuiltInRegistries.ITEM.bindTags(originalTags);
			RawOreSmeltingUpgradeHelper.invalidateCache();
		}
	}

	private static long amount(Map<ItemStack, Integer> outputs, Item item) {
		return outputs.entrySet().stream().filter(e -> e.getKey().is(item)).mapToLong(Map.Entry::getValue).sum();
	}

	@SuppressWarnings("unchecked")
	private static void seedConversion(int inputCount, int outputCount) throws Exception {
		RawOreSmeltingUpgradeHelper.invalidateCache();
		var type = Class.forName(RawOreSmeltingUpgradeHelper.class.getName() + "$Conversion");
		var constructor = type.getDeclaredConstructor(int.class, ItemStack.class);
		constructor.setAccessible(true);
		var field = RawOreSmeltingUpgradeHelper.class.getDeclaredField("CONVERSIONS");
		field.setAccessible(true);
		var cache = (Map<Item, Optional<?>>) field.get(null);
		cache.put(Items.RAW_IRON, Optional.of(constructor.newInstance(inputCount, new ItemStack(Items.IRON_INGOT, outputCount))));
	}
}
