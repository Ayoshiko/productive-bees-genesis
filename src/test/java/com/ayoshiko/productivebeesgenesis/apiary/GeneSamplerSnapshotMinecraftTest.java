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

	private static long needed(int count, int room, int limit) {
		return (Math.max(0, count - room) + limit - 1L) / limit;
	}
	private static void assertItems(List<ItemStack> expected, List<ItemStack> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) assertTrue(ItemStack.matches(expected.get(i), actual.get(i)));
	}
}
