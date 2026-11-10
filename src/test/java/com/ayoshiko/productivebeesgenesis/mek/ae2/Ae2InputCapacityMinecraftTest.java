package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2InputCapacityMinecraftTest {
	@Test
	void emptyLanesPlanOnlyWhatTheActualCandidateCanInsert() throws Exception {
		for (int multiplier : new int[] {1, 4, 4096}) {
			for (Item item : List.of(Items.IRON_INGOT, Items.ENDER_PEARL, Items.IRON_SWORD)) {
				var slot = BasicInventorySlot.at(null, 0, 0);
				((TieredInputSlot) slot).productivebeesgenesis$setInputStackMultiplier(() -> multiplier);
				assertCapacityMatchesInsertion(slot, new ItemStack(item));
			}
		}
	}

	@Test
	void modifiedStackLimitDoesNotReuseAnotherComponentVariantsCapacity() throws Exception {
		var slot = BasicInventorySlot.at(null, 0, 0);
		((TieredInputSlot) slot).productivebeesgenesis$setInputStackMultiplier(() -> 4);
		var regular = new ItemStack(Items.HONEYCOMB);
		var smaller = regular.copy();
		smaller.set(DataComponents.MAX_STACK_SIZE, 16);
		// 查询顺序也覆盖槽位缓存：相同 Item 的组件变化必须改变真实上限。
		assertEquals(256, slot.getLimit(regular));
		assertEquals(64, slot.getLimit(smaller));
		assertCapacityMatchesInsertion(slot, smaller);
		assertEquals(256, slot.getLimit(regular));
	}

	@Test
	void consumedLanesKeepTheirCompleteComponentIdentityAcrossPulls() {
		var regularSlot = BasicInventorySlot.at(null, 0, 0);
		var modifiedSlot = BasicInventorySlot.at(null, 0, 0);
		var regular = new ItemStack(Items.HONEYCOMB);
		var modified = regular.copy();
		modified.set(DataComponents.MAX_STACK_SIZE, 16);
		regularSlot.setStack(regular);
		modifiedSlot.setStack(modified);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(regularSlot, modifiedSlot), 2);
		regularSlot.setStack(ItemStack.EMPTY);
		modifiedSlot.setStack(ItemStack.EMPTY);
		snapshot.capture(new ArrayList<>(List.of(regularSlot, modifiedSlot)), 2);
		assertEquals(0, snapshot.preferredStart(AEItemKey.of(regular), 1));
		assertEquals(1, snapshot.preferredStart(AEItemKey.of(modified), 0));
		assertEquals(1, snapshot.preferredStart(AEItemKey.of(Items.RAW_IRON), 1));
	}

	@Test
	void laneReplacementAndShrinkDiscardPreferencesWithoutLosingOtherLanesOnGrowth() {
		var first = BasicInventorySlot.at(null, 0, 0);
		var second = BasicInventorySlot.at(null, 0, 0);
		first.setStack(new ItemStack(Items.RAW_IRON));
		second.setStack(new ItemStack(Items.RAW_GOLD));
		var iron = AEItemKey.of(Items.RAW_IRON);
		var gold = AEItemKey.of(Items.RAW_GOLD);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(first, second), 2);
		first.setStack(ItemStack.EMPTY);
		second.setStack(ItemStack.EMPTY);
		var extra = BasicInventorySlot.at(null, 0, 0);
		snapshot.capture(List.of(first, second, extra), 3);
		assertEquals(0, snapshot.preferredStart(iron, 2));
		assertEquals(1, snapshot.preferredStart(gold, 2));
		var replacement = BasicInventorySlot.at(null, 0, 0);
		snapshot.capture(List.of(replacement, second, extra), 3);
		assertEquals(2, snapshot.preferredStart(iron, 2));
		snapshot.capture(List.of(replacement), 1);
		snapshot.capture(List.of(replacement, second, extra), 3);
		assertEquals(2, snapshot.preferredStart(gold, 2));
	}

	@Test
	void acceptedReplacementInputUpdatesThePreferenceOfAnEmptyLane() {
		var slot = BasicInventorySlot.at(null, 0, 0);
		var other = BasicInventorySlot.at(null, 0, 0);
		slot.setStack(new ItemStack(Items.RAW_IRON));
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(slot, other), 2);
		slot.setStack(new ItemStack(Items.RAW_GOLD));
		snapshot.refresh(0);
		slot.setStack(ItemStack.EMPTY);
		snapshot.capture(List.of(slot, other), 2);
		assertEquals(1, snapshot.preferredStart(AEItemKey.of(Items.RAW_IRON), 1));
		assertEquals(0, snapshot.preferredStart(AEItemKey.of(Items.RAW_GOLD), 1));
	}

	@Test
	void indexedCandidatesPreserveComponentsAndRefreshAfterEachAcceptedInput() {
		var first = BasicInventorySlot.at(null, 0, 0);
		var second = BasicInventorySlot.at(null, 0, 0);
		var empty = BasicInventorySlot.at(null, 0, 0);
		var regular = new ItemStack(Items.HONEYCOMB);
		var modified = regular.copy();
		modified.set(DataComponents.MAX_STACK_SIZE, 16);
		first.setStack(regular);
		second.setStack(modified);
		var regularKey = AEItemKey.of(regular);
		var modifiedKey = AEItemKey.of(modified);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(first, second, empty), 3);
		assertTrue(snapshot.isIndexed());
		assertEquals(0b101L, snapshot.candidateLanes(regularKey));
		assertEquals(0b110L, snapshot.candidateLanes(modifiedKey));
		assertEquals(1, snapshot.occupiedLaneCount(regularKey));
		assertEquals(1, snapshot.occupiedLaneCount(modifiedKey));
		empty.setStack(modified.copy());
		snapshot.remember(modifiedKey, 2);
		snapshot.refresh(2);
		assertEquals(0b001L, snapshot.candidateLanes(regularKey));
		assertEquals(0b110L, snapshot.candidateLanes(modifiedKey));
		assertEquals(2, snapshot.occupiedLaneCount(modifiedKey));
		assertFalse(snapshot.hasEmptyLane());
		second.setStack(ItemStack.EMPTY);
		snapshot.refresh(1);
		assertEquals(0b011L, snapshot.candidateLanes(regularKey));
		assertEquals(1, snapshot.occupiedLaneCount(modifiedKey));
		assertTrue(snapshot.hasEmptyLane());
		assertEquals(1, snapshot.preferredStart(modifiedKey, 0));
	}

	@Test
	void lastMaskBitAndLargerFactoriesRetainEveryEligibleLane() {
		List<IInventorySlot> slots = new ArrayList<>();
		for (int lane = 0; lane < 65; lane++) {
			var slot = BasicInventorySlot.at(null, 0, 0);
			slot.setStack(new ItemStack(Items.RAW_GOLD));
			slots.add(slot);
		}
		slots.get(63).setStack(new ItemStack(Items.RAW_IRON));
		var iron = AEItemKey.of(Items.RAW_IRON);
		var gold = AEItemKey.of(Items.RAW_GOLD);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(slots.subList(0, 64), 64);
		assertTrue(snapshot.isIndexed());
		assertEquals(Long.MIN_VALUE, snapshot.candidateLanes(iron));
		assertEquals(63, snapshot.preferredStart(iron, 0));
		assertEquals(0, snapshot.preferredStart(gold, 63));
		assertEquals(63, snapshot.occupiedLaneCount(gold));
		snapshot.capture(slots, 65);
		assertFalse(snapshot.isIndexed());
		assertEquals(63, snapshot.preferredStart(iron, 64));
		assertEquals(64, snapshot.occupiedLaneCount(gold));
		slots.get(64).setStack(ItemStack.EMPTY);
		snapshot.refresh(64);
		assertTrue(snapshot.hasEmptyLane());
		snapshot.capture(slots.subList(0, 64), 64);
		assertTrue(snapshot.isIndexed());
		assertFalse(snapshot.hasEmptyLane());
		assertEquals(Long.MIN_VALUE, snapshot.candidateLanes(iron));
	}

	@Test
	void customSlotsStayEligibleForTheirOwnSimulationRules() {
		var slot = mock(IInventorySlot.class);
		var stored = new ItemStack(Items.HONEYCOMB);
		stored.set(DataComponents.MAX_STACK_SIZE, 16);
		when(slot.getStack()).thenReturn(stored);
		when(slot.getLimit(any())).thenReturn(64);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(slot), 1);
		assertEquals(1L, snapshot.candidateLanes(AEItemKey.of(Items.HONEYCOMB)));
		assertEquals(0, snapshot.occupiedLaneCount(AEItemKey.of(Items.HONEYCOMB)));
	}

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

	private static void assertCapacityMatchesInsertion(BasicInventorySlot slot, ItemStack template) throws Exception {
		var key = AEItemKey.of(template);
		var entry = new Ae2InputExecution.PullEntry(key, Integer.MAX_VALUE);
		entry.beginComponentMatchCache(1);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(slot), 1);
		var lane = Ae2InputExecution.class.getDeclaredMethod("laneCapacity", Ae2InputLaneSnapshot.class,
				int.class, Item.class, Ae2InputExecution.PullEntry.class, ItemStack.class);
		lane.setAccessible(true);
		var fallback = Ae2InputExecution.class.getDeclaredMethod("getSlotRemainingCapacity", IInventorySlot.class,
				int.class, Ae2InputExecution.PullEntry.class, ItemStack.class);
		fallback.setAccessible(true);
		int requested = 1_000_000;
		int actual = requested - slot.insertItem(template.copyWithCount(requested),
				Action.SIMULATE, AutomationType.INTERNAL).getCount();
		var aggregate = Ae2InputPuller.class.getDeclaredMethod("calculateInputCapacity", List.class);
		aggregate.setAccessible(true);
		assertTrue((long) aggregate.invoke(null, List.of(slot)) >= actual,
				"the pre-candidate budget must not cap a valid batch at the empty stack's limit");
		assertEquals(actual, (long) lane.invoke(null, snapshot, 0, template.getItem(), entry, template));
		assertEquals(actual, (long) fallback.invoke(null, slot, 0, entry, template));
		assertTrue(slot.isEmpty());
	}
}
