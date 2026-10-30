package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cy.jdkdigital.productivebees.init.ModItems;
import java.util.Arrays;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class ApiaryCageInputMinecraftTest {

	@Test
	void fullHiveDoesNotResolveOrConstructRemainingEggs() {
		var fixture = new Fixture(3);
		fixture.fill();
		var egg = new ItemStack(ModItems.CONFIGURABLE_SPAWN_EGG.get(), 64);
		var eggData = new CompoundTag();
		eggData.putString("id", "productivebees:configurable_bee");
		eggData.putString("type", "productivebees:iron");
		egg.set(DataComponents.ENTITY_DATA, CustomData.of(eggData));
		fixture.input.setStack(egg);
		try (var resolver = org.mockito.Mockito.mockStatic(BeeSpawnEggHelper.class)) {
			for (int tick = 0; tick < 256; tick++) fixture.handler.processCageInput();
			resolver.verifyNoInteractions();
		}
		assertEquals(64, fixture.input.getCount());
		assertTrue(fixture.output.isEmpty());
		for (var slot : fixture.slots) assertFalse(slot.isEmpty());
	}

	@Test
	void filledCageRetainsItsAssetsWhileFullAndFillsNewlyFreedSlotImmediately() {
		var fixture = new Fixture(3);
		fixture.fill();
		var beeData = beeData();
		var cage = new ItemStack(ModItems.BEE_CAGE.get(), 2);
		cage.set(DataComponents.CUSTOM_DATA, CustomData.of(beeData));
		fixture.input.setStack(cage);
		fixture.handler.processCageInput();
		assertEquals(2, fixture.input.getCount());
		fixture.slots[1].clear();
		fixture.handler.processCageInput();
		assertEquals(1, fixture.input.getCount());
		assertEquals(beeData, fixture.slots[1].getBeeData());
		assertTrue(fixture.output.isEmpty());
	}

	@Test
	void fullHiveStillAllowsEmptyCageToExtractSelectedBee() {
		var fixture = new Fixture(3);
		fixture.fill();
		when(fixture.tile.getSelectedBeeSlot()).thenReturn(1);
		fixture.input.setStack(new ItemStack(ModItems.BEE_CAGE.get(), 2));
		var before = fixture.slots[1].getBeeData().copy();
		fixture.handler.processCageInput();
		assertTrue(fixture.slots[1].isEmpty());
		assertFalse(fixture.slots[0].isEmpty());
		assertEquals(1, fixture.input.getCount());
		assertEquals(before, fixture.output.getStack().get(DataComponents.CUSTOM_DATA).copyTag());
	}

	@Test
	void blockedSturdyCageOutputDoesNotInitializeOrClearBeeSlot() {
		var fixture = new Fixture(1);
		var cage = new ItemStack(ModItems.STURDY_BEE_CAGE.get());
		cage.set(DataComponents.CUSTOM_DATA, CustomData.of(beeData()));
		fixture.input.setStack(cage);
		fixture.output.setStack(new ItemStack(Items.STONE, 64));
		fixture.slots[0].setTicksInHive(73);
		fixture.handler.processCageInput();
		assertTrue(fixture.slots[0].isEmpty());
		assertEquals(73, fixture.slots[0].getTicksInHive());
		assertEquals(1, fixture.input.getCount());
		assertEquals(64, fixture.output.getCount());
		fixture.output.setStack(ItemStack.EMPTY);
		fixture.handler.processCageInput();
		assertFalse(fixture.slots[0].isEmpty());
		assertTrue(fixture.input.isEmpty());
		assertTrue(fixture.output.getStack().is(ModItems.STURDY_BEE_CAGE.get()));
		assertFalse(fixture.output.getStack().has(DataComponents.CUSTOM_DATA));
	}

	@Test
	void fullCageOutputKeepsSelectedBeeUnchanged() {
		var fixture = new Fixture(1);
		fixture.fill();
		fixture.input.setStack(new ItemStack(ModItems.BEE_CAGE.get()));
		var before = fixture.slots[0].getBeeData();
		fixture.output.setStack(new ItemStack(Items.STONE, 64));
		fixture.handler.processCageInput();
		assertSame(before, fixture.slots[0].getBeeData());
		assertEquals(1, fixture.input.getCount());
	}

	private static CompoundTag beeData() {
		var data = new CompoundTag();
		data.putString("entity", "productivebees:configurable_bee");
		data.putString("type", "productivebees:iron");
		var attachments = new CompoundTag();
		attachments.put("productivebees:attributes_handler", new CompoundTag());
		data.put("neoforge:attachments", attachments);
		return data;
	}

	private static final class Fixture {
		private final TileEntityMekApiary tile = mock(TileEntityMekApiary.class);
		private final ApiarySlotManager manager = mock(ApiarySlotManager.class);
		private final BasicInventorySlot input = BasicInventorySlot.at(null, 0, 0);
		private final BasicInventorySlot output = BasicInventorySlot.at(null, 0, 0);
		private final BeeSlot[] slots;
		private final ApiaryCageHandler handler = new ApiaryCageHandler(manager);

		private Fixture(int count) {
			slots = new BeeSlot[count];
			Arrays.setAll(slots, index -> new BeeSlot());
			when(manager.getCageInSlot()).thenReturn(input);
			when(manager.getCageOutSlot()).thenReturn(output);
			when(manager.getBeeSlots()).thenReturn(slots);
			when(manager.getTile()).thenReturn(tile);
			when(manager.getLevel()).thenReturn(mock(Level.class));
			when(tile.getSelectedBeeSlot()).thenReturn(-1);
		}

		private void fill() {
			for (var slot : slots) slot.setBeeData(beeData());
		}
	}
}
