package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2InputCapacityMinecraftTest {
	@Test
	void unmatchedCandidateCanEnterAsSoonAsItsLaneBecomesEmpty() {
		var level = mock(Level.class);
		when(level.getGameTime()).thenReturn(200L);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		var host = mock(IAe2InputHost.class);
		when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
		when(host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
		var holder = new Ae2OutputStateHolder();
		var buffers = new Ae2PushBuffers();
		var backoff = new Ae2KeyBackoffRegistry<AEItemKey>();
		var fairness = new Ae2InputFairnessScheduler();
		var iron = AEItemKey.of(Items.IRON_INGOT);
		var storage = mock(MEStorage.class);
		long[] available = {10};
		when(storage.extract(eq(iron), anyLong(),
				eq(Actionable.MODULATE), any())).thenAnswer(call -> {
			long extracted = Math.min((long) call.getArgument(1), available[0]);
			available[0] -= extracted;
			return extracted;
		});
		var slot = BasicInventorySlot.at(null, 0, 0);
		slot.setStack(new ItemStack(Items.GOLD_INGOT, 1));
		List<IInventorySlot> slots = List.of(slot);
		var entry = new Ae2InputExecution.PullEntry(iron, 10);
		var candidates = new ArrayList<>(List.of(entry));
		try {
			fairness.roll(200);
			var blocked = Ae2InputExecution.execute(host, holder, buffers, candidates, slots, storage,
					backoff, fairness, 63, 10, 10, IActionSource.empty());
			assertEquals(0, blocked.totalPulled());
			assertEquals(10, available[0]);
			assertEquals(10, entry.remaining);
			assertTrue(slot.getStack().is(Items.GOLD_INGOT));

			slot.setEmpty();
			when(level.getGameTime()).thenReturn(201L);
			fairness.roll(201);
			var accepted = Ae2InputExecution.execute(host, holder, buffers, candidates, slots, storage,
					backoff, fairness, 64, 10, 10, IActionSource.empty());
			assertEquals(10, accepted.totalPulled());
			assertEquals(0, available[0]);
			assertEquals(0, entry.remaining);
			assertTrue(slot.getStack().is(Items.IRON_INGOT));
			assertEquals(10, slot.getCount());
			assertFalse(holder.getPendingItemBuffer().isBlocked());
			assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
		} finally {
			Ae2NetworkWorkCoordinator.clearAll();
		}
	}

	@Test
	void failedPlanningReleasesCachedPermissionBeforeTheNextExecution() {
		var level = mock(Level.class);
		when(level.getGameTime()).thenReturn(300L);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		var host = mock(IAe2InputHost.class);
		when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
		when(host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
		var holder = new Ae2OutputStateHolder();
		var buffers = new Ae2PushBuffers();
		var backoff = new Ae2KeyBackoffRegistry<AEItemKey>();
		var fairness = new Ae2InputFairnessScheduler();
		var iron = AEItemKey.of(Items.IRON_INGOT);
		var storage = mock(MEStorage.class);
		long[] available = {10};
		when(storage.extract(eq(iron), anyLong(), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
			long extracted = Math.min((long) call.getArgument(1), available[0]);
			available[0] -= extracted;
			return extracted;
		});
		boolean[] firstAllowed = {false};
		boolean[] failSecond = {true};
		var first = BasicInventorySlot.at(stack -> true, stack -> firstAllowed[0], null, 0, 0);
		var second = BasicInventorySlot.at(stack -> true, stack -> {
			if (failSecond[0]) throw new IllegalStateException("planning failed before extraction");
			return false;
		}, null, 0, 0);
		List<IInventorySlot> slots = List.of(first, second);
		var entry = new Ae2InputExecution.PullEntry(iron, 10);
		var candidates = new ArrayList<>(List.of(entry));
		try {
			fairness.roll(300);
			assertThrows(IllegalStateException.class, () -> Ae2InputExecution.execute(host, holder,
					buffers, candidates, slots, storage, backoff, fairness, 128, 10, 10, IActionSource.empty()));
			assertEquals(10, available[0]);
			assertTrue(first.isEmpty());
			assertTrue(second.isEmpty());
			assertFalse(holder.getPendingItemBuffer().isBlocked());

			assertEquals(10, entry.remaining);
			firstAllowed[0] = true;
			failSecond[0] = false;
			when(level.getGameTime()).thenReturn(301L);
			fairness.roll(301);
			var result = Ae2InputExecution.execute(host, holder, buffers, candidates, slots, storage,
					backoff, fairness, 128, 10, 10, IActionSource.empty());
			assertEquals(10, result.totalPulled());
			assertEquals(10, first.getCount());
			assertTrue(second.isEmpty());
			assertEquals(0, available[0]);
			assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
		} finally {
			Ae2NetworkWorkCoordinator.clearAll();
		}
	}

	@Test
	void partialCustomSlotsKeepEveryPlannedDestinationAvailable() {
		var level = mock(Level.class);
		when(level.getGameTime()).thenReturn(403L);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		var host = mock(IAe2InputHost.class);
		when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
		when(host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
		var holder = new Ae2OutputStateHolder();
		var buffers = new Ae2PushBuffers();
		var fairness = new Ae2InputFairnessScheduler();
		var key = AEItemKey.of(Items.IRON_INGOT);
		var storage = mock(MEStorage.class);
		when(storage.extract(eq(key), eq(19L), eq(Actionable.MODULATE), any())).thenReturn(19L);
		List<IInventorySlot> slots = new ArrayList<>();
		ItemStack[] stored = new ItemStack[19];
		for (int lane = 0; lane < stored.length; lane++) {
			int index = lane;
			stored[index] = ItemStack.EMPTY;
			var slot = mock(IInventorySlot.class);
			when(slot.getStack()).thenAnswer(call -> stored[index]);
			when(slot.getLimit(any())).thenReturn(64);
			when(slot.insertItem(any(), any(), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
				ItemStack offered = call.getArgument(0);
				if (call.getArgument(1) == Action.EXECUTE) stored[index] = offered.copyWithCount(1);
				return offered.getCount() == 1 ? ItemStack.EMPTY : offered.copyWithCount(offered.getCount() - 1);
			});
			slots.add(slot);
		}
		var entry = new Ae2InputExecution.PullEntry(key, 19);
		try {
			fairness.roll(403);
			var result = Ae2InputExecution.execute(host, holder, buffers, new ArrayList<>(List.of(entry)),
					slots, storage, new Ae2KeyBackoffRegistry<>(), fairness, 1216, 19, 64,
					IActionSource.empty());
			assertEquals(19, result.totalPulled());
			for (ItemStack stack : stored) {
				assertTrue(key.matches(stack));
				assertEquals(1, stack.getCount());
			}
			assertEquals(0, entry.remaining);
			assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
			assertFalse(holder.getPendingItemBuffer().isBlocked());
		} finally {
			Ae2NetworkWorkCoordinator.clearAll();
		}
	}

	@Test
	void earlyStopRetainsTheSecondPassAndIndependentLanesForBothTypes() {
		var level = mock(Level.class);
		when(level.getGameTime()).thenReturn(402L);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		var host = mock(IAe2InputHost.class);
		when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
		when(host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
		var holder = new Ae2OutputStateHolder();
		var buffers = new Ae2PushBuffers();
		var fairness = new Ae2InputFairnessScheduler();
		var iron = AEItemKey.of(Items.IRON_INGOT);
		var gold = AEItemKey.of(Items.GOLD_INGOT);
		var storage = mock(MEStorage.class);
		when(storage.extract(any(), anyLong(), eq(Actionable.MODULATE), any()))
				.thenAnswer(call -> call.getArgument(1));
		var first = BasicInventorySlot.at(null, 0, 0);
		var second = BasicInventorySlot.at(null, 0, 0);
		var ironEntry = new Ae2InputExecution.PullEntry(iron, 12);
		var goldEntry = new Ae2InputExecution.PullEntry(gold, 2);
		try {
			fairness.roll(402);
			var result = Ae2InputExecution.execute(host, holder, buffers,
					new ArrayList<>(List.of(ironEntry, goldEntry)), List.of(first, second), storage,
					new Ae2KeyBackoffRegistry<>(), fairness, 128, 10, 64, IActionSource.empty());
			assertEquals(10, result.totalPulled());
			assertTrue(first.getStack().is(Items.IRON_INGOT));
			assertTrue(second.getStack().is(Items.GOLD_INGOT));
			assertEquals(8, first.getCount());
			assertEquals(2, second.getCount());
			assertEquals(4, ironEntry.remaining);
			assertEquals(0, goldEntry.remaining);
			var calls = inOrder(storage);
			calls.verify(storage).extract(eq(iron), eq(5L), eq(Actionable.MODULATE), any());
			calls.verify(storage).extract(eq(gold), eq(2L), eq(Actionable.MODULATE), any());
			calls.verify(storage).extract(eq(iron), eq(3L), eq(Actionable.MODULATE), any());
			calls.verifyNoMoreInteractions();
			assertFalse(holder.getPendingItemBuffer().isBlocked());
			assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
		} finally {
			Ae2NetworkWorkCoordinator.clearAll();
		}
	}

}
