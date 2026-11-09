package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import java.util.List;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2PendingItemRetryMinecraftTest {

	@Test
	void outputBudgetCannotStarveKnownInputReturn() {
		var holder = new Ae2OutputStateHolder();
		var storage = mock(MEStorage.class);
		var key = AEItemKey.of(Items.RAW_IRON);
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(key, registries());
		holder.getPendingItemBuffer().enqueueReturnToNetwork(fingerprint, 5, 20);
		holder.recordNetworkCost(storage, 20, 10_000_000, 150_000);
		when(storage.insert(eq(key), anyLong(), any(Actionable.class), any(IActionSource.class)))
				.thenAnswer(call -> call.getArgument(1));
		Ae2InputPuller.retryPendingItems(level(), holder, storage, List.of(), 20);
		assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
		assertFalse(holder.getPendingItemBuffer().isBlocked());
		verify(storage).insert(eq(key), eq(5L), eq(Actionable.MODULATE), any());
	}

	@Test
	void exhaustedInputBudgetDoesNotIncreasePendingFailureExponent() {
		var holder = new Ae2OutputStateHolder();
		var storage = mock(MEStorage.class);
		var key = AEItemKey.of(Items.RAW_IRON);
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(key, registries());
		holder.getPendingItemBuffer().enqueueReturnToNetwork(fingerprint, 5, 20);
		holder.getPendingItemBuffer().recordFailure(fingerprint, 20);
		holder.recordNetworkExtractCost(storage, 21, 10_000_000, 300_000);
		Ae2InputPuller.retryPendingItems(level(), holder, storage, List.of(), 21);
		var deferred = holder.getPendingItemBuffer().snapshot(22).getFirst();
		assertEquals(1, deferred.retries());
		assertEquals(5, deferred.returnToNetworkAmount());
		verify(storage, never()).insert(any(), anyLong(), any(), any());
	}

	@Test
	void disconnectedNetworkStillAllowsKnownAssetsIntoLocalSlots() {
		var holder = new Ae2OutputStateHolder();
		var key = AEItemKey.of(Items.RAW_IRON);
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(key, registries());
		holder.getPendingItemBuffer().enqueue(fingerprint, 5, 20);
		var slot = BasicInventorySlot.at(null, 0, 0);
		holder.getPushState().getReturnBackoff().recordFailure(System.nanoTime());
		Ae2InputPuller.retryPendingItems(level(), holder, null, List.of(slot), 20);
		assertEquals(5, slot.getCount());
		assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
		assertFalse(holder.getPendingItemBuffer().isBlocked());
	}

	@Test
	void disconnectedNetworkOnlyEntriesDoNotBlockLaterLocalRecovery() {
		var holder = new Ae2OutputStateHolder();
		var pending = holder.getPendingItemBuffer();
		for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND)) {
			String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(AEItemKey.of(item), registries());
			assertEquals(2, pending.enqueueReturnToNetwork(fingerprint, 2, 20));
		}
		String localFingerprint = Ae2ItemFingerprint.encodeOrLegacy(AEItemKey.of(Items.RAW_IRON), registries());
		assertEquals(5, pending.enqueue(localFingerprint, 5, 20));
		var slot = BasicInventorySlot.at(null, 0, 0);

		Ae2InputPuller.retryPendingItems(level(), holder, null, List.of(slot), 20);

		assertTrue(slot.getStack().is(Items.RAW_IRON));
		assertEquals(5, slot.getCount());
		assertEquals(8, pending.getTotalAmount());
		assertFalse(pending.isBlocked());
		assertEquals(4, pending.snapshot(20).size());
		for (var entry : pending.snapshot(20)) {
			assertEquals(0, entry.amount());
			assertEquals(2, entry.returnToNetworkAmount());
			assertEquals(0, entry.retries());
		}
	}

	@Test
	void automaticReturnPendingRetriesMeWithoutReenteringInputSlots() {
		Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(AEItemKey.of(Items.RAW_IRON), registries());
		assertEquals(5L, holder.getPendingItemBuffer().enqueueReturnToNetwork(fingerprint, 5L, 20L));
		MEStorage storage = mock(MEStorage.class);
		IInventorySlot slot = mock(IInventorySlot.class);
		AEItemKey key = AEItemKey.of(Items.RAW_IRON);
		when(storage.insert(eq(key), anyLong(), eq(Actionable.SIMULATE), any(IActionSource.class)))
				.thenAnswer(call -> call.getArgument(1));
		when(storage.insert(eq(key), anyLong(), eq(Actionable.MODULATE), any(IActionSource.class)))
				.thenAnswer(call -> Math.min(2L, (Long) call.getArgument(1)));

		Ae2InputPuller.retryPendingItems(level(), holder, storage, List.of(slot), 20L);

		assertEquals(3L, holder.getPendingItemBuffer().getTotalAmount());
		assertFalse(holder.getPendingItemBuffer().hasRetryableItems(20L));
		assertTrue(holder.getPendingItemBuffer().hasRetryableItems(21L));
		assertEquals(3L, holder.getPendingItemBuffer().snapshot(21L).getFirst().returnToNetworkAmount());
		assertEquals(1, holder.getPendingItemBuffer().snapshot(21L).getFirst().retries());
		verify(slot, never()).insertItem(any(ItemStack.class), any(Action.class), any(AutomationType.class));
	}

	@Test
	void unknownPendingInsertResultMovesAttemptIntoNonRetryableQuarantine() {
		Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(AEItemKey.of(Items.RAW_IRON), registries());
		holder.getPendingItemBuffer().enqueueReturnToNetwork(fingerprint, 5L, 20L);
		MEStorage storage = mock(MEStorage.class);
		IInventorySlot slot = mock(IInventorySlot.class);
		AEItemKey key = AEItemKey.of(Items.RAW_IRON);
		when(storage.insert(eq(key), anyLong(), eq(Actionable.SIMULATE), any(IActionSource.class)))
				.thenAnswer(call -> call.getArgument(1));
		when(storage.insert(eq(key), anyLong(), eq(Actionable.MODULATE), any(IActionSource.class)))
				.thenThrow(new IllegalStateException("unknown insert result"));

		Ae2InputPuller.retryPendingItems(level(), holder, storage, List.of(slot), 20L);

		assertEquals(5L, holder.getPendingItemBuffer().getTotalAmount());
		assertFalse(holder.getPendingItemBuffer().hasRetryableItems(20L));
		assertFalse(holder.getPendingItemBuffer().hasRetryableItems(200L));
		assertTrue(holder.getPendingItemBuffer().snapshot(200L).isEmpty());
		verify(slot, never()).insertItem(any(ItemStack.class), any(Action.class), any(AutomationType.class));
	}

	@Test
	void repeatedRejectionsPreserveExponentialDelayAcrossTransferAndSave() {
		var holder = new Ae2OutputStateHolder();
		var key = AEItemKey.of(Items.RAW_IRON);
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(key, registries());
		holder.getPendingItemBuffer().enqueueReturnToNetwork(fingerprint, 5, 20);
		var storage = mock(MEStorage.class);
		var level = level();
		Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(), 20);
		Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(), 21);
		assertFalse(holder.getPendingItemBuffer().hasRetryableItems(22));
		Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(), 23);
		var saved = new net.minecraft.nbt.CompoundTag();
		holder.savePendingItems(saved);
		var restored = new Ae2OutputStateHolder();
		restored.loadPendingItems(saved);
		assertFalse(restored.getPendingItemBuffer().hasRetryableItems(26));
		assertEquals(3, restored.getPendingItemBuffer().snapshot(27).getFirst().retries());
		assertEquals(5, restored.getPendingItemBuffer().getTotalAmount());
		verify(storage, org.mockito.Mockito.times(3)).insert(eq(key), eq(5L), eq(Actionable.SIMULATE), any());
	}

	private static Level level() {
		Level level = mock(Level.class);
		when(level.registryAccess()).thenReturn(registries());
		return level;
	}

	private static RegistryAccess registries() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
}
