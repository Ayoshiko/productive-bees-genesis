package com.ayoshiko.productivebeesgenesis.resource;

import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class PbMixedProductionMinecraftTest {
	private static class Inventory extends BlockEntityItemStackHandler {
		int failSlot = -1;
		Inventory() { super(4); }
		@Override public int[] getOutputSlots() { return new int[]{1, 2, 3}; }
		@Override protected void onContentsChanged(int slot) {
			if (slot == failSlot) throw new IllegalStateException("listener after write");
		}
	}
	private static PbResourceCatalog.ItemOutput output(ItemStack item, int min, int max, float chance) {
		return new PbResourceCatalog.ItemOutput(item, min, max, chance);
	}

	@Test void wholeBatchUsesOnlyOutputSlotsAndMergesExactComponents() {
		var inv = new Inventory();
		var named = new ItemStack(Items.IRON_INGOT);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("reserved"));
		inv.setStackInSlot(1, named.copyWithCount(63));
		inv.setStackInSlot(3, new ItemStack(Items.IRON_INGOT, 63));
		var plan = PbLocalOutputPlan.prepare(inv, List.of(new ItemStack(Items.IRON_INGOT, 2)));
		assertNotNull(plan);
		plan.commit(() -> {});
		assertTrue(inv.getStackInSlot(0).isEmpty());
		assertEquals(63, inv.getStackInSlot(1).getCount());
		assertEquals(64, inv.getStackInSlot(3).getCount());
		assertEquals(1, inv.getStackInSlot(2).getCount());
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {}));
	}

	@Test void multipleOutputsCompeteForSameLastSlotWithoutPartialWrite() {
		var inv = new Inventory();
		inv.setStackInSlot(1, new ItemStack(Items.STONE, 64));
		inv.setStackInSlot(2, new ItemStack(Items.STONE, 64));
		assertNull(PbLocalOutputPlan.prepare(inv, List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT))));
		assertTrue(inv.getStackInSlot(3).isEmpty());
		assertTrue(inv.getStackInSlot(0).isEmpty());
	}

	@Test void staleSnapshotIsRejectedBeforeAnyWrite() {
		var inv = new Inventory();
		var plan = PbLocalOutputPlan.prepare(inv, List.of(new ItemStack(Items.IRON_INGOT)));
		inv.setStackInSlot(3, new ItemStack(Items.GOLD_INGOT));
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {}));
		assertTrue(inv.getStackInSlot(1).isEmpty());
	}

	@Test void listenerFailureRetainsObservedWriteAndDisallowsReplay() {
		var inv = new Inventory();
		var plan = PbLocalOutputPlan.prepare(inv, List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
		inv.failSlot = 1;
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {}));
		assertEquals(Items.IRON_INGOT, inv.getStackInSlot(1).getItem());
		assertTrue(inv.getStackInSlot(2).isEmpty());
		inv.failSlot = -1;
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {}));
	}

	@Test void sharedFractionalRoundsAndPerStackGeneMatchResourcePortions() {
		var items = new PbMixedProduction(List.of(output(new ItemStack(Items.IRON_INGOT), 1, 1, 1)), 1.5, 3, false, false);
		var output = new ResourceProductionRecipe.Output(ResourceKey.energy(), 1000, 1);
		for (int seed = 0; seed < 100; seed++) {
			var sample = new ResourceRollPlan(List.of(output), 1.5, 3, seed).sampleWithRolls(1);
			var produced = items.sample(sample.rolls(), seed);
			assertEquals(sample.amounts().get(ResourceKey.energy()) / 1000, produced.getFirst().getCount());
			assertTrue(produced.getFirst().getCount() <= items.maximum().getFirst().getCount());
		}
		var multiple = new PbMixedProduction(List.of(output(new ItemStack(Items.IRON_INGOT), 2, 2, 1)), 2, 3, false, false);
		assertEquals(12, multiple.sample(2, 1).getFirst().getCount());
	}

	@Test void hostInvalidationStopsTheRestOfTheBatch() {
		var inv = new Inventory();
		var plan = PbLocalOutputPlan.prepare(inv, List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {
			if (!inv.getStackInSlot(1).isEmpty()) throw new IllegalStateException("Host removed by listener");
		}));
		assertEquals(Items.IRON_INGOT, inv.getStackInSlot(1).getItem());
		assertTrue(inv.getStackInSlot(2).isEmpty());
	}

	@Test void chanceRangeAndSeedAreStableAndBounded() {
		var items = new PbMixedProduction(List.of(output(new ItemStack(Items.IRON_INGOT), 1, 3, 0.5f)), 16, 2, false, false);
		int total = 0;
		for (long seed = 0; seed < 100; seed++) {
			var sample = items.sample(16, seed);
			int amount = sample.isEmpty() ? 0 : sample.getFirst().getCount();
			var again = items.sample(16, seed);
			assertEquals(amount, again.isEmpty() ? 0 : again.getFirst().getCount());
			assertTrue(amount <= items.maximum().getFirst().getCount());
			total += amount;
		}
		assertTrue(total > 1000);
		assertTrue(new PbMixedProduction(List.of(output(new ItemStack(Items.IRON_INGOT), 1, 1, 0)), 1, 0, false, false).maximum().isEmpty());
	}

	@Test void reducedActiveLayoutRejectsPlanWithoutUsingInactiveSlots() {
		var inv = new net.neoforged.neoforge.items.ItemStackHandler(36);
		int[][] layout = {new int[]{2, 3}};
		inv.setStackInSlot(2, new ItemStack(Items.STONE, 64));
		var plan = PbLocalOutputPlan.prepare(inv, () -> layout[0], List.of(new ItemStack(Items.IRON_INGOT)));
		layout[0] = new int[]{2};
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {}));
		assertTrue(inv.getStackInSlot(3).isEmpty());
		assertNull(PbLocalOutputPlan.prepare(inv, () -> layout[0], List.of(new ItemStack(Items.IRON_INGOT))));
		assertTrue(inv.getStackInSlot(1).isEmpty());
		assertTrue(inv.getStackInSlot(34).isEmpty());
	}

	@Test void listenerLayoutChangeStopsBeforeNextSlot() {
		int[][] layout = {new int[]{2, 3}};
		var inv = new net.neoforged.neoforge.items.ItemStackHandler(36) {
			@Override protected void onContentsChanged(int slot) { layout[0] = new int[]{2}; }
		};
		var plan = PbLocalOutputPlan.prepare(inv, () -> layout[0],
				List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {}));
		assertEquals(1, inv.getStackInSlot(2).getCount());
		assertTrue(inv.getStackInSlot(3).isEmpty());
	}

	@Test void invalidLayoutsAndCustomSlotCapacityAreRespected() {
		var inv = new net.neoforged.neoforge.items.ItemStackHandler(36) {
			@Override public int getSlotLimit(int slot) { return 16; }
		};
		assertThrows(IllegalArgumentException.class, () -> PbLocalOutputPlan.prepare(inv, () -> new int[]{2, 2}, List.of()));
		assertThrows(IllegalArgumentException.class, () -> PbLocalOutputPlan.prepare(inv, () -> new int[]{36}, List.of()));
		assertNull(PbLocalOutputPlan.prepare(inv, () -> new int[]{2}, List.of(new ItemStack(Items.IRON_INGOT, 17))));
		var plan = PbLocalOutputPlan.prepare(inv, () -> new int[]{2, 3}, List.of(new ItemStack(Items.IRON_INGOT, 17)));
		plan.commit(() -> {});
		assertEquals(16, inv.getStackInSlot(2).getCount());
		assertEquals(1, inv.getStackInSlot(3).getCount());
	}
}
