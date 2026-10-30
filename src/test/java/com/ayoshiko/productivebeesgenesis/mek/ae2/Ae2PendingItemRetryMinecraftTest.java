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

	private static Level level() {
		Level level = mock(Level.class);
		when(level.registryAccess()).thenReturn(registries());
		return level;
	}

	private static RegistryAccess registries() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
}
