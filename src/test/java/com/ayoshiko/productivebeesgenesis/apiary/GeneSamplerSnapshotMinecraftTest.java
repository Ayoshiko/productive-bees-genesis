package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import cy.jdkdigital.productivebees.common.item.Gene;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class GeneSamplerSnapshotMinecraftTest {
	@org.junit.jupiter.api.BeforeAll static void configs() {
		net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}
	@org.junit.jupiter.api.AfterAll static void unload() {
		net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}
	private static final ResourceLocation BEE = ResourceLocation.parse("example:energy");

	@Test
	void emptyProductionStillSamplesAdultVeryHighGenesWithoutHoneyOrMutatingPaidCycles() {
		ResourceLocation type = ResourceLocation.parse("productivebees:kamikaz");
		CompoundTag attributes = new CompoundTag();
		attributes.putString("bee_productivity", "productivity.very_high");
		CompoundTag attachments = new CompoundTag();
		attachments.put("productivebees:attributes_handler", attributes);
		CompoundTag data = new CompoundTag();
		data.put("neoforge:attachments", attachments);
		data.putString("id", "productivebees:configurable_bee");
		data.putString("type", type.toString());
		BeeSlot slot = new BeeSlot();
		slot.setBeeData(data);
		var level = mock(Level.class);
		when(level.getGameTime()).thenReturn(100L);
		when(level.getRandom()).thenReturn(RandomSource.create(7941));
		var upgrades = new ApiaryBatchUpgradeSnapshot(10000, false, true, 1, false, false, false, false, false, false);
		var indices = new OrderedSlotIndex();
		indices.reset(1);
		indices.add(0);
		int[] pending = {256};
		var generator = new BeeProductionGenerator(this, new MultiFlowerProductionCache());
		var generated = generator.prepare(new BeeSlot[]{slot}, pending, indices, type, java.util.Map.of(), null,
				net.minecraft.core.BlockPos.ZERO, level, upgrades, false, true, -1, null, null, 0, true, false);
		assertArrayEquals(new int[]{256}, pending);
		assertTrue(generated.fluid().isEmpty());
		assertEquals(0, generated.fluidAmount());
		assertFalse(generated.items().isEmpty());
		assertTrue(generated.items().stream().allMatch(stack -> stack.getItem() instanceof Gene));
		var expected = Gene.getStack(GeneAttribute.PRODUCTIVITY, "productivity.very_high", 1, 100);
		assertTrue(generated.items().stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(expected, stack)));
		data.putInt("Age", -24000);
		var baby = generator.prepare(new BeeSlot[]{slot}, pending, indices, type, java.util.Map.of(), null,
				net.minecraft.core.BlockPos.ZERO, level, upgrades, false, true, -1, null, null, 0, true, false);
		assertTrue(baby.items().isEmpty());
		assertTrue(baby.fluid().isEmpty());
	}

	@Test void resourceSnapshotUsesExactlyTheExistingSmallAndAggregatedSampler() {
		for (int cycles : new int[]{1, 127, 128, 129, 256, 4096}) {
			for (boolean type : new boolean[]{false, true}) for (boolean purity : new boolean[]{false, true}) {
				long seed = 7941;
				var level = mock(Level.class);
				when(level.getGameTime()).thenReturn(100L);
				when(level.getRandom()).thenReturn(RandomSource.create(seed));
				var batch = new GeneSampler.SampleBatch();
				batch.add(GeneSampleProfile.fromBeeData(null), cycles);
				var original = new ArrayList<ItemStack>();
				new GeneSampler().generateGeneSamplesInto(original, batch, BEE, 4, level, type, purity);
				var snapshot = GeneSampler.snapshot(null, BEE, 4, type, purity);
				assertItems(original, snapshot.sample(cycles, seed));
				assertItems(original, snapshot.sample(cycles, seed));
			}
		}
	}

	@Test void snapshotKeepsAttributesAndPluginIdentityAndExcludesBabies() {
		var data = new CompoundTag();
		var snapshot = GeneSampler.snapshot(data, BEE, 10000, true, true);
		data.putInt("Age", -24000);
		assertFalse(GeneSampler.snapshot(data, BEE, 10000, true, true).active());
		assertTrue(GeneSampler.snapshot(null, BEE, 10000, false, false).possible().size() == 24);
		assertItems(List.of(Gene.getStack(GeneAttribute.TYPE, BEE.toString(), 256, 100)), snapshot.sample(256, 72));
		var candidates = snapshot.possible(); candidates.getFirst().setCount(100);
		assertEquals(1, snapshot.possible().getFirst().getCount());
		assertFalse(GeneSampler.snapshot(null, BEE, 0, true, true).active());
	}

	@Test void capacityFormulaMatchesEverySmallDistribution() {
		for (int limit = 1; limit <= 4; limit++) for (int a = 0; a <= 3; a++)
			for (int b = 0; b <= 3; b++) for (int c = 0; c <= 3; c++)
				for (int total = 0; total <= 12; total++) {
					long expected = 0;
					for (int x = 0; x <= total; x++) for (int y = 0; y <= total - x; y++) {
						int z = total - x - y;
						long slots = needed(x, a, limit) + needed(y, b, limit) + needed(z, c, limit);
						expected = Math.max(expected, slots);
					}
					assertEquals(expected, ApiaryLocalOutputPlan.maximumExtraSlots(total, new long[]{a,b,c}, limit));
				}
		assertEquals(1, ApiaryLocalOutputPlan.maximumExtraSlots(1, new long[24], 64));
		assertEquals(24, ApiaryLocalOutputPlan.maximumExtraSlots(256, new long[24], Integer.MAX_VALUE));
	}

	@Test
	void positiveSubGridChanceStillSamplesAndKeepsTheDoubleThreshold() {
		double configured = ProductiveBeesConfig.UPGRADES.samplerChance.get();
		try {
			ProductiveBeesConfig.UPGRADES.samplerChance.set(1.0E-10D);
			var tiny = GeneSampler.snapshot(null, BEE, 1, true, true);
			ProductiveBeesConfig.UPGRADES.samplerChance.set(Math.nextDown(Math.scalb(1.0D, -24)));
			var belowGrid = GeneSampler.snapshot(null, BEE, 1, true, true);
			long total = 0;
			int withHits = 0;
			for (int seed = 0; seed < 256; seed++) {
				var produced = tiny.sample(1L << 24, 7941L + seed);
				assertItems(produced, belowGrid.sample(1L << 24, 7941L + seed));
				long count = produced.stream().mapToLong(ItemStack::getCount).sum();
				total += count;
				if (count > 0) withHits++;
			}
			assertTrue(total > 190 && total < 325, "A positive sub-grid chance must retain PB's minimum hit probability");
			assertTrue(withHits > 130 && withHits < 200, "Rare sampling must keep both misses and successes");
			var level = mock(Level.class);
			when(level.getGameTime()).thenReturn(101L);
			var random = mock(RandomSource.class);
			when(random.nextFloat()).thenReturn(Math.scalb(1.0F, -24));
			when(level.getRandom()).thenReturn(random);
			var sources = new GeneSampler.SampleBatch();
			sources.add(GeneSampleProfile.fromBeeData(null), 1);
			var output = new ArrayList<ItemStack>();
			new GeneSampler().generateGeneSamplesInto(output, sources, BEE, 1, level, true, true);
			assertTrue(output.isEmpty(), "Rounding the configuration to float would accept the next grid point");
		} finally {
			ProductiveBeesConfig.UPGRADES.samplerChance.set(configured);
		}
	}

	@Test
	void aggregatedAttributesAndPuritiesRemainRandomAndConserveEveryHit() {
		double configured = ProductiveBeesConfig.UPGRADES.samplerChance.get();
		try {
			ProductiveBeesConfig.UPGRADES.samplerChance.set(1.0D);
			for (boolean attributes : new boolean[]{true, false}) {
				var snapshot = GeneSampler.snapshot(null, BEE, 1, !attributes, attributes);
				var expected = Gene.getStack(GeneAttribute.TYPE, BEE.toString(), 1, attributes ? 100 : 1);
				long sum = 0;
				long squares = 0;
				for (int seed = 0; seed < 256; seed++) {
					var output = snapshot.sample(129, 14271L + seed);
					assertEquals(129, output.stream().mapToLong(ItemStack::getCount).sum());
					long selected = output.stream().filter(stack -> ItemStack.isSameItemSameComponents(expected, stack))
							.mapToLong(ItemStack::getCount).sum();
					sum += selected;
					squares += selected * selected;
				}
				double mean = sum / 256.0D;
				double variance = squares / 256.0D - mean * mean;
				assertEquals(attributes ? 21.5D : 32.25D, mean, 2.0D);
				assertTrue(variance > 8.0D && variance < 40.0D,
						"PB selects each attribute and purity independently; equal division removes this variance");
			}
		} finally {
			ProductiveBeesConfig.UPGRADES.samplerChance.set(configured);
		}
	}

	private static long needed(int count, int room, int limit) {
		return (Math.max(0, count - room) + limit - 1L) / limit;
	}
	private static void assertItems(List<ItemStack> expected, List<ItemStack> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) assertTrue(ItemStack.matches(expected.get(i), actual.get(i)));
	}
}
