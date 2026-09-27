package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter;
import cy.jdkdigital.productivebees.init.ModTags;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class MultiFlowerProductionMinecraftTest {

	@Test
	void allFlowersSkipDisabledBlacklistedAndDuplicateSources() {
		var originalTags = BuiltInRegistries.BLOCK.getTags().collect(Collectors.toMap(
				pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
		try {
			var tags = new java.util.HashMap<>(originalTags);
			tags.put(ModTags.LUMBER, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_LOG),
					BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.BIRCH_LOG),
					BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.SPRUCE_LOG),
					BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.CHEST)));
			tags.put(ModTags.DUPE_BLACKLIST, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.CHEST)));
			BuiltInRegistries.BLOCK.bindTags(tags);
			var slots = List.of(slot(Items.OAK_LOG, 64), slot(Items.OAK_LOG, 3), slot(Items.BIRCH_LOG, 9),
					slot(Items.SPRUCE_LOG, 1), slot(Items.CHEST, 1), slot(Items.DIAMOND, 1));
			slots.get(3).setDisabled(true);
			var outputs = FeederTagSampler.allBlocks(slots, ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
			assertEquals(List.of(Items.OAK_LOG, Items.BIRCH_LOG), outputs.stream().map(ItemStack::getItem).toList());
			assertTrue(outputs.stream().allMatch(stack -> stack.getCount() == 1));
			for (int i = 0; i < 20; i++) {
				var random = FeederTagSampler.randomBlock(slots, ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
				assertTrue(random.is(Items.OAK_LOG) || random.is(Items.BIRCH_LOG));
				assertEquals(1, random.getCount());
			}
			assertEquals(64, slots.getFirst().getCount());
		} finally {
			BuiltInRegistries.BLOCK.bindTags(originalTags);
		}
	}

	@Test
	void directDyeSourcesAreDistinctItemsRatherThanStackCounts() {
		var originalTags = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
				pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
		try {
			var tags = new java.util.HashMap<>(originalTags);
			tags.put(ModTags.Common.DYES, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.RED_DYE),
					BuiltInRegistries.ITEM.wrapAsHolder(Items.BLUE_DYE)));
			BuiltInRegistries.ITEM.bindTags(tags);
			var feeder = new FeederSlotManager(4, 4, 1);
			var slots = feeder.buildFeederSlots(null);
			slots.get(0).setStack(new ItemStack(Items.RED_DYE, 64));
			slots.get(1).setStack(new ItemStack(Items.RED_DYE, 2));
			slots.get(2).setStack(new ItemStack(Items.BLUE_DYE, 4));
			var outputs = MultiFlowerBeeAdapter.allProduceFromFeeder(id("dye_bee"), feeder, null);
			assertEquals(List.of(Items.RED_DYE, Items.BLUE_DYE), outputs.stream().map(ItemStack::getItem).toList());
			assertTrue(outputs.stream().allMatch(stack -> stack.getCount() == 1));
			feeder.getFeederInventorySlots().get(2).setDisabled(true);
			assertEquals(1, MultiFlowerBeeAdapter.allProduceFromFeeder(id("dye_bee"), feeder, null).size());
		} finally {
			BuiltInRegistries.ITEM.bindTags(originalTags);
		}
	}

	@Test
	void batchProducesEachSourceWithEachProductivityTierAndOffKeepsSingleSelection() {
		for (boolean all : new boolean[]{true, false}) {
			var feeder = mock(FeederSlotManager.class);
			when(feeder.getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST))
					.thenReturn(List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.BIRCH_LOG)));
			when(feeder.getRandomBlockFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST))
					.thenReturn(new ItemStack(Items.OAK_LOG));
			var manager = mock(ApiarySlotManager.class);
			var outputs = List.of(BasicInventorySlot.at(null, 0, 0), BasicInventorySlot.at(null, 0, 0));
			when(manager.getOutputSlots()).thenReturn(outputs);
			BeeSlot[] bees = {mock(BeeSlot.class), mock(BeeSlot.class)};
			for (var bee : bees) when(bee.getBeeData()).thenReturn(new CompoundTag());
			when(bees[1].getProductivityLevel()).thenReturn(3);
			int[] counts = {2, 3};
			var indices = new OrderedSlotIndex();
			indices.reset(2);
			indices.add(0);
			indices.add(1);
			var upgrades = new ApiaryBatchUpgradeSnapshot(0, false, false, 1, true,
					false, false, false, false, false);
			try (var config = mockStatic(BalanceConfig.class)) {
				config.when(BalanceConfig::apiaryProduceAllFlowers).thenReturn(all);
				new BeeProduceProcessor(null, null).processBatchProduce(bees, counts, indices, id("lumber_bee"),
						Map.of(), manager, feeder, BlockPos.ZERO, null, null, upgrades, true);
			}
			assertArrayEquals(new int[]{0, 0}, counts);
			assertTrue(outputs.getFirst().getStack().is(Items.OAK_LOG));
			assertTrue(outputs.getFirst().getCount() > 5, "High productivity must affect every source");
			if (all) {
				assertTrue(outputs.get(1).getStack().is(Items.BIRCH_LOG));
				assertEquals(outputs.getFirst().getCount(), outputs.get(1).getCount());
				verify(feeder, times(1)).getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
				verify(feeder, never()).getRandomBlockFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
			} else {
				assertTrue(outputs.get(1).isEmpty());
				verify(feeder, never()).getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
			}
		}
	}

	@Test
	void productionCacheReusesTemplatesAndInvalidatesWithSourcesRecipesAndWorld() {
		var feeder = mock(FeederSlotManager.class);
		var template = new ItemStack(Items.OAK_LOG);
		when(feeder.getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST))
				.thenReturn(List.of(template));
		var cache = new MultiFlowerProductionCache();
		var first = cache.get(id("lumber_bee"), feeder, null);
		for (int i = 0; i < 256; i++) assertSame(first, cache.get(id("lumber_bee"), feeder, null));
		verify(feeder, times(1)).getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
		var sampled = new java.util.ArrayList<ItemStack>();
		BeeProduceBatchSampler.sampleGuaranteedInto(sampled, first.getFirst(), 10, 1);
		assertEquals(1, template.getCount());
		assertEquals(10, sampled.getFirst().getCount());
		when(feeder.getFlowerCacheVersion()).thenReturn(1);
		cache.get(id("lumber_bee"), feeder, null);
		verify(feeder, times(2)).getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
		var version = com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.RECIPE_VERSION;
		long originalVersion = version.get();
		try {
			version.incrementAndGet();
			cache.get(id("lumber_bee"), feeder, null);
			var level = mock(net.minecraft.world.level.Level.class);
			cache.get(id("lumber_bee"), feeder, level);
			verify(feeder, times(4)).getAllBlocksFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST);
		} finally {
			version.set(originalVersion);
		}
	}

	private static FeederInventorySlot slot(net.minecraft.world.item.Item item, int count) {
		var slot = FeederInventorySlot.create(null);
		slot.setStack(new ItemStack(item, count));
		return slot;
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("productivebees", path);
	}
}
