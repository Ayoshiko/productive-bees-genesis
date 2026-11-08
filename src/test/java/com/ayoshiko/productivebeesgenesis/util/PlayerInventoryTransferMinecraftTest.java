package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PlayerInventoryTransferMinecraftTest {
	private static Player player() {
		Player player = mock(Player.class);
		when(player.getInventory()).thenReturn(new Inventory(player));
		return player;
	}

	private static void fill(Inventory inventory) {
		for (int slot = 0; slot < inventory.items.size(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.STONE, 64));
		}
	}

	@Test
	void partialCapacityDoesNotMutateSourceOrInventory() {
		Player player = player();
		Inventory inventory = player.getInventory();
		fill(inventory);
		inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 63));
		ItemStack returned = new ItemStack(Items.IRON_INGOT, 2);
		assertNull(PlayerInventoryTransfer.prepare(player, List.of(returned)));
		assertEquals(63, inventory.getItem(0).getCount());
		assertEquals(2, returned.getCount());
	}

	@Test
	void componentsAndSlotLimitsArePreservedAndRollbackIgnoresUnrelatedChanges() {
		Player player = player();
		Inventory inventory = player.getInventory();
		fill(inventory);
		ItemStack first = new ItemStack(Items.IRON_INGOT, 63);
		first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
		inventory.setItem(0, first);
		ItemStack second = new ItemStack(Items.IRON_INGOT, 2);
		second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
		assertNull(PlayerInventoryTransfer.prepare(player, List.of(second)));
		inventory.setItem(1, ItemStack.EMPTY);
		var plan = PlayerInventoryTransfer.prepare(player, List.of(second));
		assertNotNull(plan);
		assertTrue(plan.commit());
		assertTrue(ItemStack.matches(second, inventory.getItem(1)));
		assertEquals(63, inventory.getItem(0).getCount());
		inventory.setItem(2, new ItemStack(Items.DIAMOND));
		assertTrue(plan.rollback());
		assertTrue(inventory.getItem(1).isEmpty());
		assertTrue(inventory.getItem(2).is(Items.DIAMOND));
		assertFalse(plan.commit());
	}

	@Test
	void changedSourceRejectsCommitAndChangedDeliveryRejectsRollback() {
		Player player = player();
		var first = PlayerInventoryTransfer.prepare(player, List.of(new ItemStack(Items.DIAMOND)));
		assertNotNull(first);
		player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT));
		assertFalse(first.commit());
		var second = PlayerInventoryTransfer.prepare(player, List.of(new ItemStack(Items.DIAMOND)));
		assertNotNull(second);
		assertTrue(second.commit());
		player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
		assertFalse(second.rollback());
		assertTrue(player.getInventory().getItem(1).is(Items.EMERALD));
	}

	@Test
	void consumesBeforeReturningAndOnlyMergesIntoExistingOffhand() {
		Player player = player();
		Inventory inventory = player.getInventory();
		fill(inventory);
		inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 1));
		inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.GOLD_INGOT, 63));
		var plan = PlayerInventoryTransfer.prepare(player, List.of());
		assertNotNull(plan);
		assertTrue(plan.consume(Items.IRON_INGOT, 1));
		assertTrue(plan.insertAll(List.of(new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.GOLD_INGOT))));
		assertTrue(plan.commit());
		assertTrue(inventory.getItem(0).is(Items.DIAMOND));
		assertEquals(64, inventory.getItem(Inventory.SLOT_OFFHAND).getCount());
	}
}
