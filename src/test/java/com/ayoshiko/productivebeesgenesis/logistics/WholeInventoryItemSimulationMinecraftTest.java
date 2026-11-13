package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class WholeInventoryItemSimulationMinecraftTest {

	@Test
	void wholeSimulationSkipsSlotQueriesAndReturnsExactPartialCapacity() {
		var target = new SimulatedHandler(2);
		target.setStackInSlot(0, new ItemStack(Items.DIAMOND, 61));
		target.setStackInSlot(1, new ItemStack(Items.EMERALD, 64));
		target.simulation = stack -> ItemPushHelper.accepted(stack,
				ItemHandlerHelper.insertItemStacked(target, stack.copy(), true));
		var request = new ItemStack(Items.DIAMOND, 8192);

		assertEquals(3, ItemPushHelper.simulateInsert(target, request, () -> true));
		assertEquals(1, target.wholeCalls);
		assertEquals(0, target.externalReads);
		assertEquals(0, target.executions);
		assertEquals(8192, request.getCount());
		assertEquals(61, target.getStackInSlot(0).getCount());
	}

	@Test
	void nonstackableRequestUsesWholeSimulationWithoutChangingItsComponents() {
		var target = new SimulatedHandler(1);
		target.simulation = stack -> ItemPushHelper.accepted(stack,
				ItemHandlerHelper.insertItemStacked(target, stack.copy(), true));
		var request = new ItemStack(Items.DIAMOND_PICKAXE);
		request.set(DataComponents.CUSTOM_NAME, Component.literal("named pickaxe"));
		var original = request.copy();

		assertEquals(1, ItemPushHelper.simulateInsert(target, request, () -> true));
		assertEquals(1, target.wholeCalls);
		assertEquals(0, target.externalReads);
		assertEquals(0, target.executions);
		assertTrue(target.getStackInSlot(0).isEmpty());
		assertTrue(ItemStack.matches(original, request));
	}

	@Test
	void invalidationDuringWholeSimulationReturnsNoSourceWithdrawalAmount() {
		var valid = new AtomicBoolean(true);
		var target = new SimulatedHandler(1);
		target.simulation = stack -> {
			valid.set(false);
			return stack.getCount();
		};
		var request = new ItemStack(Items.DIAMOND, 16);

		assertEquals(0, ItemPushHelper.simulateInsert(target, request, valid::get));
		assertEquals(1, target.wholeCalls);
		assertEquals(0, target.externalReads);
		assertEquals(0, target.executions);
		assertEquals(16, request.getCount());
	}

	@Test
	void unavailableDelegateFallsBackToOrdinaryStackingAndKeepsExecutionOnOriginalHandler() {
		var target = new SimulatedHandler(2);
		target.simulation = stack -> WholeInventoryItemSimulation.UNAVAILABLE;
		target.setStackInSlot(0, new ItemStack(Items.DIAMOND, 61));
		target.setStackInSlot(1, new ItemStack(Items.EMERALD, 64));
		var request = new ItemStack(Items.DIAMOND, 8);

		assertEquals(3, ItemPushHelper.simulateInsert(target, request, () -> true));
		assertTrue(target.externalReads > 0);
		assertEquals(0, target.executions);
		var custody = new OutputCustody(() -> {});
		ItemStack remaining = ItemPushHelper.insert(target, request, custody, "original-handler", () -> true);
		assertEquals(1, target.wholeCalls);
		assertEquals(1, target.executions);
		assertEquals(5, remaining.getCount());
		assertEquals(64, target.getStackInSlot(0).getCount());
		assertEquals(8, request.getCount());
		assertFalse(custody.blocked());
	}

	@Test
	void runtimeFailureAndInvalidReceiptDoNotBecomeSuccessfulOrZeroSimulations() {
		var target = new SimulatedHandler(1);
		var failure = new IllegalStateException("simulation failure");
		target.simulation = stack -> {
			throw failure;
		};
		var request = new ItemStack(Items.DIAMOND, 16);
		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> ItemPushHelper.simulateInsert(target, request, () -> true)));
		for (int invalid : new int[]{-2, 17}) {
			target.simulation = stack -> invalid;
			assertThrows(IllegalStateException.class,
					() -> ItemPushHelper.simulateInsert(target, request, () -> true));
		}
		assertEquals(0, target.externalReads);
		assertEquals(0, target.executions);
		assertEquals(16, request.getCount());
	}

	@Test
	void invalidTargetIsNotCalledAndUnavailableResultCannotResumeAnInvalidatedTarget() {
		var target = new SimulatedHandler(1);
		var request = new ItemStack(Items.DIAMOND, 16);
		assertEquals(0, ItemPushHelper.simulateInsert(target, request, () -> false));
		assertEquals(0, target.wholeCalls);
		var valid = new AtomicBoolean(true);
		target.simulation = stack -> {
			valid.set(false);
			return WholeInventoryItemSimulation.UNAVAILABLE;
		};
		assertEquals(0, ItemPushHelper.simulateInsert(target, request, valid::get));
		assertEquals(1, target.wholeCalls);
		assertEquals(0, target.externalReads);
	}

	private static final class SimulatedHandler extends ItemStackHandler implements WholeInventoryItemSimulation {
		private ToIntFunction<ItemStack> simulation = stack -> stack.getCount();
		private boolean insideWholeSimulation;
		private int wholeCalls;
		private int externalReads;
		private int executions;

		private SimulatedHandler(int slots) {
			super(slots);
		}

		@Override
		public int productivebeesgenesis$simulateWholeItemInsert(ItemStack stack) {
			wholeCalls++;
			insideWholeSimulation = true;
			try {
				return simulation.applyAsInt(stack);
			} finally {
				insideWholeSimulation = false;
			}
		}

		@Override
		public int getSlots() {
			if (!insideWholeSimulation) externalReads++;
			return super.getSlots();
		}

		@Override
		public ItemStack getStackInSlot(int slot) {
			if (!insideWholeSimulation) externalReads++;
			return super.getStackInSlot(slot);
		}

		@Override
		public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
			if (!simulate) executions++;
			return super.insertItem(slot, stack, simulate);
		}
	}
}
