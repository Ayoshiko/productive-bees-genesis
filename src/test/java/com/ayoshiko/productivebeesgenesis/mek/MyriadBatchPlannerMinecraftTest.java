package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class MyriadBatchPlannerMinecraftTest {

	private static final ResourceLocation FIRST = ResourceLocation.parse("test:first");
	private static final ResourceLocation SECOND = ResourceLocation.parse("test:second");

	@AfterEach
	void clearCaches() {
		MyriadBatchPlanner.clearTemplateCache();
	}

	@Test
	void differentBeeTypesSharingOneCombKeepNewSlotOwnership() {
		var slot = BasicInventorySlot.at(null, 0, 0);
		List<IInventorySlot> slots = List.of(slot);
		var allocation = new LinkedHashMap<ResourceLocation, Integer>();
		allocation.put(FIRST, 20);
		allocation.put(SECOND, 30);
		var template = new ItemStack(Items.HONEYCOMB);
		var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, allocation, 0,
				Map.of(FIRST, template, SECOND, template));
		assertTrue(plan.isSuccess());
		assertTrue(plan.getPlans().getFirst().wasEmpty());
		MyriadBatchPlanner.apply(plan, slots);
		assertEquals(50, slot.getCount());
		assertTrue(slot.getStack().is(Items.HONEYCOMB));
		assertEquals(1, template.getCount());
	}

	@Test
	void realTemplateComponentsChangeWithoutStaleItemOnlyCache() {
		for (String name : List.of("before reload", "after reload")) {
			var slot = BasicInventorySlot.at(null, 0, 0);
			List<IInventorySlot> slots = List.of(slot);
			var template = new ItemStack(Items.HONEYCOMB);
			template.set(DataComponents.CUSTOM_NAME, Component.literal(name));
			var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 7), 0,
					Map.of(FIRST, template));
			assertTrue(plan.isSuccess());
			MyriadBatchPlanner.apply(plan, slots);
			assertEquals(7, slot.getCount());
			assertTrue(ItemStack.isSameItemSameComponents(template, slot.getStack()));
		}
	}

	@Test
	void failedCapacityPlanDoesNotMutateInventory() {
		var slot = BasicInventorySlot.at(null, 0, 0);
		slot.setStack(new ItemStack(Items.HONEYCOMB, 60));
		List<IInventorySlot> slots = List.of(slot);
		var template = new ItemStack(Items.HONEYCOMB);
		var snapshot = MyriadBatchPlanner.takeSnapshot(slots, Items.HONEYCOMB, 0);
		assertEquals(4, MyriadBatchPlanner.planOrFindMaxBatch(snapshot, Items.HONEYCOMB, 1,
				List.of(FIRST), 100, Map.of(FIRST, template)));
		assertFalse(MyriadBatchPlanner.plan(snapshot, Items.HONEYCOMB, Map.of(FIRST, 5),
				Map.of(FIRST, template)).isSuccess());
		assertEquals(60, slot.getCount());
	}

	@Test
	void realComponentLimitAndInsertionRulesOverrideBaseItemCapacity() {
		var template = new ItemStack(Items.HONEYCOMB);
		template.set(DataComponents.MAX_STACK_SIZE, 16);
		var slot = BasicInventorySlot.at(null, 0, 0);
		List<IInventorySlot> slots = List.of(slot);
		assertFalse(MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 17), 0,
				Map.of(FIRST, template)).isSuccess());
		var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 16), 0,
				Map.of(FIRST, template));
		assertTrue(plan.isSuccess());
		MyriadBatchPlanner.apply(plan, slots);
		assertEquals(16, slot.getCount());
		var denied = BasicInventorySlot.at(stack -> true, stack -> false, null, 0, 0);
		assertFalse(MyriadBatchPlanner.plan(List.of(denied), Items.HONEYCOMB, Map.of(FIRST, 1), 0,
				Map.of(FIRST, template)).isSuccess());
		assertTrue(denied.isEmpty());
	}

	@Test
	void legalSuperstackCapacityIsPreservedAndMissingTemplateIsRejected() {
		var slot = new BasicInventorySlot(4096, (stack, automation) -> true, (stack, automation) -> true,
				stack -> true, null, 0, 0) {{ obeyStackLimit = false; }};
		List<IInventorySlot> slots = List.of(slot);
		var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 1000), 0,
				Map.of(FIRST, new ItemStack(Items.HONEYCOMB)));
		assertTrue(plan.isSuccess());
		MyriadBatchPlanner.apply(plan, slots);
		assertEquals(1000, slot.getCount());
		assertFalse(MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 1), 0, Map.of()).isSuccess());
		assertEquals(1000, slot.getCount());
	}

	@Test
	void repeatedSameTickPlanSeesAppliedAndExternalSlotChanges() {
		var slot = BasicInventorySlot.at(null, 0, 0);
		List<IInventorySlot> slots = List.of(slot);
		var template = new ItemStack(Items.HONEYCOMB);
		var templates = Map.of(FIRST, template);
		var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 40), 0, templates);
		assertTrue(plan.isSuccess());
		MyriadBatchPlanner.apply(plan, slots);
		assertFalse(MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 30), 0, templates).isSuccess());
		slot.setStack(new ItemStack(Items.DIAMOND, 1));
		assertFalse(MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, Map.of(FIRST, 1), 0, templates).isSuccess());
		assertEquals(1, slot.getCount());
		assertTrue(slot.getStack().is(Items.DIAMOND));
	}
}
