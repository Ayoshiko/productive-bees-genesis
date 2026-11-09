package com.ayoshiko.productivebeesgenesis.resource;

import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class PbResourceBridgeMinecraftTest {
	private static ResourceProductionRecipe.Output output(String id, long amount, double chance) {
		return new ResourceProductionRecipe.Output(ResourceLocation.parse(id), amount, chance);
	}

	@Test void nativeHiveKeepsItsOriginalProductionAndCannotBindNewResources() {
		var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebees:advanced_oak_beehive"))
				.defaultBlockState();
		var hive = new AdvancedBeehiveBlockEntity(BlockPos.ZERO, state);
		assertFalse(hive instanceof PbResourceBridge);
		assertFalse(ResourceHiveHost.ready(hive));
		assertNull(ResourceHiveHost.existing(hive));
	}

	@Test void resourceAmountUsesGeneOnPortionsInsteadOfRawUnits() {
		var plan = new ResourceRollPlan(List.of(output("mekanism:hydrogen", 100, 1)), 1, 2, 5);
		assertEquals(Map.of(ResourceKey.chemical(ResourceLocation.parse("mekanism:hydrogen")), 300L), plan.sample());
		assertEquals(plan.maximum(), plan.sample());
	}

	@Test void fractionalUpgradeSharesRollCountAcrossOutputsAndFrozenSeedIsStable() {
		var hydrogen = ResourceKey.chemical(ResourceLocation.parse("mekanism:hydrogen"));
		var oxygen = ResourceKey.chemical(ResourceLocation.parse("mekanism:oxygen"));
		int extra = 0;
		for (int seed = 0; seed < 1000; seed++) {
			var plan = new ResourceRollPlan(List.of(
					output("mekanism:hydrogen", 100, 1), output("mekanism:oxygen", 50, 1)), 1.5, 0, seed * 0x9E3779B97F4A7C15L);
			var sampled = plan.sample();
			assertEquals(sampled, plan.sample());
			assertEquals(sampled.get(hydrogen) / 2, sampled.get(oxygen));
			assertTrue(sampled.get(hydrogen) == 100 || sampled.get(hydrogen) == 200);
			if (sampled.get(hydrogen) == 200) extra++;
		}
		assertTrue(extra > 350 && extra < 650);
	}

	@Test void zeroProbabilityAndOverflowDoNotCreateTruncatedAssets() {
		assertTrue(new ResourceRollPlan(List.of(output("mekanism:hydrogen", 100, 0)), 1, 3, 0).sample().isEmpty());
		var maximum = new ResourceRollPlan(List.of(output("mekanism:hydrogen", Long.MAX_VALUE, 1)), 1, 0, 0);
		assertEquals(Long.MAX_VALUE, maximum.sample().values().iterator().next());
		assertThrows(ArithmeticException.class,
				() -> new ResourceRollPlan(List.of(output("mekanism:hydrogen", Long.MAX_VALUE, 1)), 1, 1, 0));
	}

	@Test void deterministicBatchKeepsExactLongAmountsAndRejectsOverflow() {
		var plan = new ResourceRollPlan(List.of(output("mekanism:hydrogen", 100, 1)), 2, 2, 5);
		long cycles = (long) Integer.MAX_VALUE + 7;
		assertEquals(Math.multiplyExact(cycles, 600), plan.sample(cycles).values().iterator().next());
		assertEquals(plan.maximum(cycles), plan.sample(cycles));
		assertTrue(plan.sample(0).isEmpty());
		assertThrows(ArithmeticException.class, () -> plan.sample(Long.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> plan.sample(-1));
	}

	@Test void batchUsesIndependentCyclesAndSharesFractionalUpgradeAcrossOutputs() {
		var hydrogen = ResourceKey.chemical(ResourceLocation.parse("mekanism:hydrogen"));
		var oxygen = ResourceKey.chemical(ResourceLocation.parse("mekanism:oxygen"));
		var fractional = new ResourceRollPlan(List.of(
				output("mekanism:hydrogen", 100, 1), output("mekanism:oxygen", 50, 1)), 1.5, 0, 987);
		var result = fractional.sample(256);
		assertEquals(result, fractional.sample(256));
		assertEquals(result.get(hydrogen) / 2, result.get(oxygen));
		assertTrue(result.get(hydrogen) > 32000 && result.get(hydrogen) < 44000);
		var chance = new ResourceRollPlan(List.of(output("mekanism:hydrogen", 100, 0.5)), 1, 2, 987);
		long produced = chance.sample(256).get(hydrogen);
		assertEquals(0, produced % 300);
		assertTrue(produced > 90 * 300 && produced < 170 * 300);
	}

	@Test void invalidHostReferenceRetainsRawDataInsteadOfBecomingNewEmptyHost() {
		var raw = new CompoundTag();
		raw.putInt("version", 7);
		raw.putString("opaque_reference", "retain");
		var state = new ResourceHiveHost();
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		state.deserializeNBT(provider, raw);
		assertEquals(raw, state.serializeNBT(provider));
		assertNull(state.reference());
		assertEquals("invalid_host_data", state.status());
	}
}
