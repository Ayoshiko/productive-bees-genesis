package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.*;

import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class BeeProduceOutputDispatcherMinecraftTest {
	@Test
	void mixedSpeciesReuseTheIndexAndExternalSlotChangesRebuildIt() {
		var version = new java.util.concurrent.atomic.AtomicLong();
		var dispatcher = new BeeProduceOutputDispatcher(version::get);
		List<IInventorySlot> slots = new ArrayList<>();
		for (int i = 0; i < 31; i++) {
			slots.add(org.mockito.Mockito.spy(com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot.at(
					() -> 1, version::incrementAndGet, 0, 0)));
		}
		List<ItemStack> templates = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			var template = new ItemStack(Items.HONEYCOMB);
			template.set(PbDataComponents.beeType(), ResourceLocation.parse("test:mixed_" + i));
			templates.add(template);
			assertTrue(dispatcher.distribute(slots, List.of(template)).isEmpty());
		}
		long reads = slots.stream().flatMap(slot -> org.mockito.Mockito.mockingDetails(slot).getInvocations().stream())
				.filter(call -> call.getMethod().getName().equals("getStack")).count();
		assertTrue(reads < 120, "30 species should share one slot scan; actual reads=" + reads);
		slots.getFirst().setStack(new ItemStack(Items.DIAMOND, 64));
		assertTrue(dispatcher.distribute(slots, List.of(templates.getFirst().copyWithCount(17))).isEmpty());
		assertTrue(slots.getFirst().getStack().is(Items.DIAMOND));
		assertEquals(64, slots.getFirst().getCount());
		assertEquals(17, slots.getLast().getCount());
		assertTrue(ItemStack.isSameItemSameComponents(templates.getFirst(), slots.getLast().getStack()));
	}

	@Test
	void multiplierChangesInvalidateRetainedCapacity() {
		var version = new java.util.concurrent.atomic.AtomicLong();
		var multiplier = new java.util.concurrent.atomic.AtomicInteger(1);
		var slot = com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot.at(
				multiplier::get, version::incrementAndGet, 0, 0);
		List<IInventorySlot> slots = List.of(slot);
		var dispatcher = new BeeProduceOutputDispatcher(version::get);
		assertTrue(dispatcher.distribute(slots, List.of(new ItemStack(Items.HONEYCOMB, 64))).isEmpty());
		multiplier.set(2);
		com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot.MULTIPLIER_VERSION.incrementAndGet();
		assertTrue(dispatcher.distribute(slots, List.of(new ItemStack(Items.HONEYCOMB, 64))).isEmpty());
		assertEquals(128, slot.getCount());
	}

	@Test
	void thirtyBeeTypesKeepCountsAndComponentsAcrossRepeatedFlushes() {
		verifyRepeatedFlushes(false);
		verifyRepeatedFlushes(true);
	}

	private void verifyRepeatedFlushes(boolean trackVersion) {
		var version = new java.util.concurrent.atomic.AtomicLong();
		var dispatcher = trackVersion ? new BeeProduceOutputDispatcher(version::get)
				: new BeeProduceOutputDispatcher();
		List<IInventorySlot> slots = new ArrayList<>();
		List<ItemStack> templates = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			slots.add(trackVersion ? com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot.at(
					() -> 1, version::incrementAndGet, 0, 0) : BasicInventorySlot.at(null, 0, 0));
			var template = new ItemStack(Items.HONEYCOMB);
			template.set(PbDataComponents.beeType(), ResourceLocation.parse("test:bee_" + i));
			templates.add(template);
		}
		for (int round = 0; round < 64; round++) {
			for (ItemStack template : templates) {
				assertTrue(dispatcher.distribute(slots, List.of(template.copy())).isEmpty());
			}
		}
		for (int i = 0; i < 30; i++) {
			assertEquals(64, slots.get(i).getCount());
			assertTrue(ItemStack.isSameItemSameComponents(templates.get(i), slots.get(i).getStack()));
			assertEquals(1, templates.get(i).getCount());
		}
		var rejected = dispatcher.distribute(slots, List.of(templates.getFirst().copyWithCount(7)));
		assertEquals(7, rejected.getFirst().getCount());
		slots.getFirst().setStack(ItemStack.EMPTY);
		assertTrue(dispatcher.distribute(slots, rejected).isEmpty());
		assertEquals(7, slots.getFirst().getCount());
	}

	@Test
	void sameBeeTypeHashBucketStillSeparatesOtherComponents() {
		var first = new ItemStack(Items.HONEYCOMB, 20);
		first.set(PbDataComponents.beeType(), ResourceLocation.parse("test:same_bee"));
		first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
		var second = first.copyWithCount(30);
		second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
		List<IInventorySlot> slots = List.of(BasicInventorySlot.at(null, 0, 0),
				BasicInventorySlot.at(null, 0, 0));
		var dispatcher = new BeeProduceOutputDispatcher();
		assertTrue(dispatcher.distribute(slots, List.of(first, second)).isEmpty());
		assertTrue(dispatcher.distribute(slots, List.of(first.copyWithCount(1), second.copyWithCount(2))).isEmpty());
		assertEquals(21, slots.get(0).getCount());
		assertEquals(32, slots.get(1).getCount());
		assertTrue(ItemStack.isSameItemSameComponents(first, slots.get(0).getStack()));
		assertTrue(ItemStack.isSameItemSameComponents(second, slots.get(1).getStack()));
	}
}
