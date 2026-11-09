package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.BlockPos;
import appeng.api.storage.MEStorage;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2InputTransferMinecraftTest {
    @Test
    void actualExtractionMutationAndInvalidReturnsStayIsolatedBeforeSlotDelivery() {
        for (long result : new long[] {-1, 101, Long.MIN_VALUE}) {
            var holder = new Ae2OutputStateHolder();
            var key = AEItemKey.of(Items.IRON_INGOT);
            var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var level = mock(Level.class);
            when(level.registryAccess()).thenReturn(registry);
            var storage = mock(MEStorage.class);
            var slot = mock(IInventorySlot.class);
            var saved = new CompoundTag();
            when(storage.extract(eq(key), eq(100L), eq(Actionable.MODULATE), any(IActionSource.class)))
                    .thenAnswer(call -> {
                        holder.savePendingItems(saved);
                        if (result == Long.MIN_VALUE) throw new IllegalStateException("deducted 40 then failed");
                        return result;
                    });
            assertThrows(IllegalStateException.class, () -> Ae2InputPuller.pullBatchForType(
                    level, holder, key, 100, -1, new KeyCounter(), storage, IActionSource.empty(),
                    List.of(slot), new long[] {100}, null, key.toStack(1), 0, BlockPos.ZERO,
                    null, new Ae2FingerprintCache(), new Ae2ExtractBudget()));
            verifyNoInteractions(slot);
            assertTrue(holder.getPendingItemBuffer().isBlocked());
            var restored = new Ae2OutputStateHolder();
            restored.loadPendingItems(saved);
            var transfer = restored.getPendingItemBuffer().transfer();
            assertEquals(100, transfer.offeredAmount());
            transfer.resolve(transfer.request(), 40, restored.getPendingItemBuffer(), 1);
            assertEquals(40, restored.getPendingItemBuffer().getTotalAmount());
        }
    }

    @Test
    void successfulLargeBatchDoesNotEncodeFingerprintOrLimitQuantity() {
        var transfer = new Ae2InputTransfer();
        var buffer = new Ae2PendingItemBuffer();
        transfer.beginExtract(() -> { throw new AssertionError("eager fingerprint"); }, Integer.MAX_VALUE);
        transfer.extracted(Integer.MAX_VALUE);
        transfer.offer(Integer.MAX_VALUE, 0);
        transfer.accepted(Integer.MAX_VALUE);
        transfer.finish(buffer, 1, false);
        assertFalse(transfer.isPresent());
        assertEquals(0, buffer.getTotalAmount());
    }

    @Test
    void savedExtractionRequiresActualWithdrawnAmountBeforeAnyRetry() {
        var buffer = new Ae2PendingItemBuffer();
        var transfer = buffer.transfer();
        transfer.beginExtract(() -> "component-exact", 100);
        var saved = new CompoundTag();
        buffer.save(saved);
        var restored = new Ae2PendingItemBuffer();
        restored.load(saved);
        assertTrue(restored.isBlocked());
        assertFalse(restored.hasRetryableItems(100));
        assertEquals(100, restored.transfer().offeredAmount());
        var request = restored.transfer().request();
        assertThrows(IllegalArgumentException.class,
                () -> restored.transfer().resolve(UUID.randomUUID(), 40, restored, 2));
        assertThrows(IllegalStateException.class,
                () -> restored.transfer().resolve(request, 101, restored, 2));
        restored.transfer().resolve(request, 40, restored, 2);
        assertEquals(40, restored.getTotalAmount());
        assertEquals(40, restored.snapshot(2).getFirst().returnToNetworkAmount());
        assertThrows(IllegalArgumentException.class,
                () -> restored.transfer().resolve(request, 40, restored, 2));
    }

    @Test
    void confirmedPrefixUnknownCurrentDeliveryAndUnattemptedSuffixRemainDistinct() {
        var buffer = new Ae2PendingItemBuffer();
        var transfer = buffer.transfer();
        transfer.beginExtract(() -> "iron", 100);
        transfer.extracted(100);
        transfer.offer(20, 0);
        transfer.accepted(20);
        transfer.offer(30, 1);
        var saved = new CompoundTag();
        buffer.save(saved);
        var restored = new Ae2PendingItemBuffer();
        restored.load(saved);
        assertEquals(50, restored.transfer().knownAmount());
        assertEquals(30, restored.transfer().offeredAmount());
        restored.transfer().resolve(restored.transfer().request(), 10, restored, 4);
        assertEquals(70, restored.getTotalAmount());
        assertEquals(70, restored.snapshot(4).getFirst().returnToNetworkAmount());
    }

    @Test
    void temporarilyDeferredTypeKeepsItsLaneUntilDemandIsExhausted() {
        var iron = new Ae2InputPuller.PullEntry(AEItemKey.of(Items.IRON_INGOT), 100);
        var gold = new Ae2InputPuller.PullEntry(AEItemKey.of(Items.GOLD_INGOT), 100);
        var occupied = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        occupied.setStack(new ItemStack(Items.IRON_INGOT, 64));
        var empty = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        var lanes = new Ae2InputLaneSnapshot();
        lanes.capture(List.of(occupied, empty), 2);
        assertTrue(Ae2InputPuller.hasUnassignedDemand(List.of(iron, gold), lanes, 2));
        gold.remaining = 0;
        assertFalse(Ae2InputPuller.hasUnassignedDemand(List.of(iron, gold), lanes, 2));
        gold.remaining = 100;
        empty.setStack(new ItemStack(Items.GOLD_INGOT, 64));
        lanes.refresh(1);
        assertFalse(Ae2InputPuller.hasUnassignedDemand(List.of(iron, gold), lanes, 2));
    }

    @Test
    void overflowingResolutionRetainsRequestAndKnownQuantityUntilCapacityExists() {
        var buffer = new Ae2PendingItemBuffer();
        buffer.enqueueReturnToNetwork("other", Long.MAX_VALUE, 0);
        buffer.transfer().beginKnown(() -> "iron", 40);
        var request = buffer.transfer().request();
        assertThrows(IllegalStateException.class, () -> buffer.transfer().resolve(request, 0, buffer, 0));
        assertEquals(40, buffer.transfer().knownAmount());
        buffer.consumeReturnToNetwork("other", 40, 0);
        buffer.transfer().resolve(request, 0, buffer, 0);
        var saved = new CompoundTag();
        buffer.save(saved);
        var restored = new Ae2PendingItemBuffer();
        restored.load(saved);
        assertFalse(restored.isBlocked());
        assertEquals(Long.MAX_VALUE, restored.getTotalAmount());
    }

    @Test
    void invalidPendingAndTransferDataRoundTripWithoutDiscardingRawEvidence() {
        var raw = new CompoundTag();
        raw.putString("productivebeesgenesis_ae_input_transfer", "invalid-transfer");
        raw.putString(Ae2NbtKeys.NBT_KEY_AE_PENDING_ITEMS, "invalid-buffer");
        var buffer = new Ae2PendingItemBuffer();
        buffer.load(raw);
        assertTrue(buffer.isBlocked());
        var saved = new CompoundTag();
        buffer.save(saved);
        assertEquals(raw, saved);
    }

    @Test
    void pendingSlotMutationThenThrowIsNotOfferedToAnotherSlotOrNetwork() {
        var holder = new Ae2OutputStateHolder();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(key, registry);
        holder.getPendingItemBuffer().enqueue(fingerprint, 100, 0);
        var first = mock(IInventorySlot.class);
        var second = mock(IInventorySlot.class);
        var storage = mock(MEStorage.class);
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(registry);
        var delivered = new AtomicInteger();
        var savedInsideCallback = new CompoundTag();
        when(first.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
            delivered.addAndGet(40);
            holder.savePendingItems(savedInsideCallback);
            throw new IllegalStateException("after mutation");
        });
        Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(first, second), 0);
        Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(first, second), 100);
        assertEquals(40, delivered.get());
        verifyNoInteractions(second, storage);
        assertTrue(holder.getPendingItemBuffer().isBlocked());
        var restored = new Ae2OutputStateHolder();
        restored.loadPendingItems(savedInsideCallback);
        assertTrue(restored.getPendingItemBuffer().isBlocked());
        var transfer = restored.getPendingItemBuffer().transfer();
        transfer.resolve(transfer.request(), 40, restored.getPendingItemBuffer(), 100);
        assertEquals(60, restored.getPendingItemBuffer().getTotalAmount());
    }

    @Test
    void pendingMeMutationThenThrowPersistsCurrentOfferAndKnownRemainder() {
        var holder = new Ae2OutputStateHolder();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(key, registry);
        holder.getPendingItemBuffer().enqueueReturnToNetwork(fingerprint, 100, 0);
        var storage = mock(MEStorage.class);
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(registry);
        when(storage.insert(eq(key), anyLong(), eq(Actionable.SIMULATE), any(IActionSource.class))).thenReturn(30L);
        var saved = new CompoundTag();
        when(storage.insert(eq(key), eq(30L), eq(Actionable.MODULATE), any(IActionSource.class))).thenAnswer(call -> {
            holder.savePendingItems(saved);
            throw new IllegalStateException("accepted 10 then failed");
        });
        Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(), 0);
        Ae2InputPuller.retryPendingItems(level, holder, storage, List.of(), 100);
        verify(storage, times(1)).insert(eq(key), eq(30L), eq(Actionable.MODULATE), any(IActionSource.class));
        var restored = new Ae2OutputStateHolder();
        restored.loadPendingItems(saved);
        var transfer = restored.getPendingItemBuffer().transfer();
        assertEquals(70, transfer.knownAmount());
        assertEquals(30, transfer.offeredAmount());
        transfer.resolve(transfer.request(), 10, restored.getPendingItemBuffer(), 100);
        assertEquals(90, restored.getPendingItemBuffer().getTotalAmount());
    }
}
