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
	@Test
	void equalCapacitySlotsReuseProbeButStillCheckEachInsertionPolicy() {
		var template = org.mockito.Mockito.spy(new ItemStack(Items.HONEYCOMB));
		var first = BasicInventorySlot.at(null, 0, 0);
		var denied = BasicInventorySlot.at(stack -> true, stack -> false, null, 0, 0);
		var third = BasicInventorySlot.at(null, 0, 0);
		var snapshot = MyriadBatchPlanner.takeSnapshot(List.of(first, denied, third), Items.HONEYCOMB, 0);
		int[] limits = snapshot.limitsFor(template);
		assertEquals(64, limits[0]);
		assertEquals(0, limits[1]);
		assertEquals(64, limits[2]);
		org.mockito.Mockito.verify(template, org.mockito.Mockito.times(1)).copyWithCount(64);
		assertEquals(1, template.getCount());
		assertTrue(first.isEmpty());
		assertTrue(denied.isEmpty());
		assertTrue(third.isEmpty());
	}


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
	void distinctTemplateObjectsPreserveComponentMatchingForExistingAndClaimedSlots() {
		for (int existing : new int[] {0, 10}) {
			for (boolean matching : new boolean[] {true, false}) {
				var first = new ItemStack(Items.HONEYCOMB);
				first.set(DataComponents.CUSTOM_NAME, Component.literal("first"));
				var second = first.copy();
				if (!matching) second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
				var slot = BasicInventorySlot.at(null, 0, 0);
				if (existing > 0) slot.setStack(first.copyWithCount(existing));
				List<IInventorySlot> slots = List.of(slot);
				var allocation = new LinkedHashMap<ResourceLocation, Integer>();
				allocation.put(FIRST, 20);
				allocation.put(SECOND, 30);
				var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, allocation, 0,
						Map.of(FIRST, first, SECOND, second));
				assertEquals(matching, plan.isSuccess());
				assertEquals(existing, slot.getCount());
				if (matching) {
					assertEquals(existing == 0, plan.getPlans().getFirst().wasEmpty());
					MyriadBatchPlanner.apply(plan, slots);
					assertEquals(existing + 50, slot.getCount());
					assertTrue(ItemStack.isSameItemSameComponents(first, slot.getStack()));
				}
			}
		}
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
	void sparseFactorySlotsPreservePhysicalOrderAndSharedTemplateOwnership() {
		var slots = new java.util.ArrayList<IInventorySlot>();
		for (int i = 0; i < 57; i++) {
			var slot = BasicInventorySlot.at(null, 0, 0);
			slot.setStack(new ItemStack(Items.DIAMOND));
			slots.add(slot);
		}
		slots.get(3).setStack(new ItemStack(Items.HONEYCOMB, 61));
		slots.set(7, BasicInventorySlot.at(stack -> true, stack -> false, null, 0, 0));
		slots.get(45).setStack(ItemStack.EMPTY);
		slots.get(52).setStack(ItemStack.EMPTY);
		var allocation = new LinkedHashMap<ResourceLocation, Integer>();
		allocation.put(FIRST, 5);
		allocation.put(SECOND, 70);
		var template = new ItemStack(Items.HONEYCOMB);
		var plan = MyriadBatchPlanner.plan(slots, Items.HONEYCOMB, allocation, 1,
				Map.of(FIRST, template, SECOND, template.copy()));
		assertTrue(plan.isSuccess());
		assertEquals(List.of(3, 45, 52), plan.getPlans().stream().map(MyriadBatchPlanner.SlotPlan::slotIndex).toList());
		assertEquals(List.of(3, 64, 8), plan.getPlans().stream().map(MyriadBatchPlanner.SlotPlan::amount).toList());
		assertEquals(List.of(false, true, true), plan.getPlans().stream().map(MyriadBatchPlanner.SlotPlan::wasEmpty).toList());
		assertEquals(61, slots.get(3).getCount());
		assertTrue(slots.get(45).isEmpty());
		MyriadBatchPlanner.apply(plan, slots);
		assertEquals(64, slots.get(3).getCount());
		assertEquals(64, slots.get(45).getCount());
		assertEquals(8, slots.get(52).getCount());
		assertTrue(slots.get(7).isEmpty());
		for (int i = 0; i < 57; i++) {
			if (i != 3 && i != 7 && i != 45 && i != 52) {
				assertEquals(1, slots.get(i).getCount());
				assertTrue(slots.get(i).getStack().is(Items.DIAMOND));
			}
		}
	}

	@Test
	void capacitySearchScratchDoesNotEscapeIntoReturnedPlan() {
		var slot = BasicInventorySlot.at(null, 0, 0);
		slot.setStack(new ItemStack(Items.HONEYCOMB, 60));
		List<IInventorySlot> slots = List.of(slot);
		var snapshot = MyriadBatchPlanner.takeSnapshot(slots, Items.HONEYCOMB, 0);
		var template = new ItemStack(Items.HONEYCOMB);
		int maxBatch = MyriadBatchPlanner.planOrFindMaxBatch(snapshot, Items.HONEYCOMB, 1,
				List.of(FIRST), 100, Map.of(FIRST, template));
		var plan = MyriadBatchPlanner.plan(snapshot, Items.HONEYCOMB, Map.of(FIRST, maxBatch),
				Map.of(FIRST, template));

		assertEquals(4, maxBatch);
		assertTrue(plan.isSuccess());
		MyriadBatchPlanner.apply(plan, slots);
		assertEquals(64, slot.getCount());
	}

	@Test
	void endpointProbesHandleHugeFullAndBlockedRequestsWithoutMutation() {
		var slot = new BasicInventorySlot(Integer.MAX_VALUE, (stack, automation) -> true,
				(stack, automation) -> true, stack -> true, null, 0, 0) {{ obeyStackLimit = false; }};
		var template = new ItemStack(Items.HONEYCOMB);
		var templates = Map.of(FIRST, template);
		assertEquals(Integer.MAX_VALUE, MyriadBatchPlanner.planOrFindMaxBatch(
				MyriadBatchPlanner.takeSnapshot(List.of(slot), Items.HONEYCOMB, 0), Items.HONEYCOMB,
				1, List.of(FIRST), Integer.MAX_VALUE, templates));
		assertTrue(slot.isEmpty());
		slot.setStack(new ItemStack(Items.DIAMOND, 1));
		assertEquals(0, MyriadBatchPlanner.planOrFindMaxBatch(
				MyriadBatchPlanner.takeSnapshot(List.of(slot), Items.HONEYCOMB, 0), Items.HONEYCOMB,
				9, List.of(FIRST), Integer.MAX_VALUE, templates));
		assertEquals(1, slot.getCount());
	}

	@Test
	void capacitySearchMatchesExhaustivePlansForSharedAndDifferentTemplates() {
		for (boolean shared : List.of(true, false)) {
			var first = new ItemStack(Items.HONEYCOMB);
			var second = shared ? first : new ItemStack(Items.HONEYCOMB);
			if (!shared) second.set(DataComponents.CUSTOM_NAME, Component.literal("second"));
			var templates = Map.of(FIRST, first, SECOND, second);
			for (int existing : new int[] {0, 55, 63, 64}) {
				var left = BasicInventorySlot.at(null, 0, 0);
				var right = BasicInventorySlot.at(null, 0, 0);
				if (existing > 0) left.setStack(first.copyWithCount(existing));
				List<IInventorySlot> slots = List.of(left, right);
				var snapshot = MyriadBatchPlanner.takeSnapshot(slots, Items.HONEYCOMB, 0);
				int expected = 0;
				for (int batch = 1; batch <= 100; batch++) {
					var allocation = com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector.allocateEvenly(
							batch * 3, List.of(FIRST, SECOND));
					var plan = MyriadBatchPlanner.plan(snapshot, Items.HONEYCOMB, allocation, templates);
					if (plan.isSuccess()) expected = batch;
					MyriadBatchPlanner.recyclePlan(plan);
				}
				assertEquals(expected, MyriadBatchPlanner.planOrFindMaxBatch(snapshot, Items.HONEYCOMB,
						3, List.of(FIRST, SECOND), 100, templates));
				assertEquals(existing, left.getCount());
				assertTrue(right.isEmpty());
			}
		}
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
	void preparedEvenAllocationPreservesHashMapOrderAndSmallBatchRemainders() {
		var third = ResourceLocation.parse("test:third");
		var types = List.of(SECOND, third, FIRST);
		var template = new ItemStack(Items.HONEYCOMB);
		var templates = Map.of(FIRST, template, SECOND, template.copy(), third, template.copy());
		var snapshot = MyriadBatchPlanner.takeSnapshot(List.of(BasicInventorySlot.at(null, 0, 0)), Items.HONEYCOMB, 0);
		for (int count = 1; count <= 3; count++) {
			var selected = types.subList(0, count);
			var prepared = MyriadPlanningAllocation.evenly(snapshot, Items.HONEYCOMB, selected, templates);
			for (int total : new int[] {1, 2, 3, 4, 63, 1000, Integer.MAX_VALUE}) {
				prepared.setTotal(total);
				var reference = com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector.allocateEvenly(total, selected);
				int index = 0;
				for (var entry : reference.entrySet()) {
					assertSame(templates.get(entry.getKey()), prepared.template(index));
					assertEquals(entry.getValue(), prepared.amount(index++));
				}
			}
		}
	}

	@Test
	void capacitySearchResolvesTemplatesOncePerPrefixInsteadOfPerProbe() {
		var third = ResourceLocation.parse("test:third");
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		var templates = new java.util.HashMap<ResourceLocation, ItemStack>() {
			@Override public ItemStack get(Object key) { reads.incrementAndGet(); return super.get(key); }
		};
		for (var type : List.of(FIRST, SECOND, third)) templates.put(type, new ItemStack(Items.HONEYCOMB));
		var slot = BasicInventorySlot.at(null, 0, 0);
		var snapshot = MyriadBatchPlanner.takeSnapshot(List.of(slot), Items.HONEYCOMB, 0);
		assertEquals(64, MyriadBatchPlanner.planOrFindMaxBatch(snapshot, Items.HONEYCOMB, 1,
				List.of(FIRST, SECOND, third), Integer.MAX_VALUE, templates));
		assertTrue(reads.get() <= 6, "at most one resolution for each template in each 1/2/3-type prefix");
		assertTrue(slot.isEmpty());
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
