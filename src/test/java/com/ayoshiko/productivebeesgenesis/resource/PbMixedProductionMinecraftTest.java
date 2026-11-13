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

	@Test void hostInvalidationStopsTheRestOfTheBatch() {
		var inv = new Inventory();
		var plan = PbLocalOutputPlan.prepare(inv, List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
		assertThrows(IllegalStateException.class, () -> plan.commit(() -> {
			if (!inv.getStackInSlot(1).isEmpty()) throw new IllegalStateException("Host removed by listener");
		}));
		assertEquals(Items.IRON_INGOT, inv.getStackInSlot(1).getItem());
		assertTrue(inv.getStackInSlot(2).isEmpty());
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

}
