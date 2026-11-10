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
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2InputTransferMinecraftTest {
    @Test
    void sameTickMachinesAndExternalChangesCannotCrossReserveFloor() {
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        when(level.getGameTime()).thenReturn(20L);
        var key = AEItemKey.of(Items.IRON_INGOT);
        var cached = new KeyCounter();
        cached.add(key, 1_000);
        var storage = mock(MEStorage.class);
        long[] live = {1_000};
        when(storage.extract(eq(key), anyLong(), any(), any())).thenAnswer(call -> {
            long requested = call.getArgument(1);
            long extracted = Math.min(requested, live[0]);
            if (call.getArgument(2) == Actionable.MODULATE) live[0] -= extracted;
            return extracted;
        });
        var firstHolder = new Ae2OutputStateHolder();
        var secondHolder = new Ae2OutputStateHolder();
        var firstSlot = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        var secondSlot = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        Ae2InputExecution.pullBatchForType(level, firstHolder, key, 64, 900, storage,
                IActionSource.empty(), List.of(firstSlot), new long[]{64}, null, key.toStack(1), 0,
                BlockPos.ZERO, null, new Ae2FingerprintCache(), new Ae2ExtractBudget(), 1L);
        Ae2InputExecution.pullBatchForType(level, secondHolder, key, 64, 900, storage,
                IActionSource.empty(), List.of(secondSlot), new long[]{64}, null, key.toStack(1), 0,
                BlockPos.ZERO, null, new Ae2FingerprintCache(), new Ae2ExtractBudget(), 1L);
        assertEquals(64, firstSlot.getCount());
        assertEquals(36, secondSlot.getCount());
        assertEquals(900, live[0]);
        assertEquals(1_000, cached.get(key));

        live[0] = 800;
        Ae2InputExecution.pullBatchForType(level, secondHolder, key, 28, 900, storage,
                IActionSource.empty(), List.of(secondSlot), new long[]{28}, null, key.toStack(1), 0,
                BlockPos.ZERO, null, new Ae2FingerprintCache(), new Ae2ExtractBudget(), 1L);
        assertEquals(36, secondSlot.getCount());
        assertEquals(800, live[0]);
        verify(storage, times(2)).extract(eq(key), anyLong(), eq(Actionable.MODULATE), any());
    }

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
            assertThrows(IllegalStateException.class, () -> Ae2InputExecution.pullBatchForType(
                    level, holder, key, 100, -1, storage, IActionSource.empty(),
                    List.of(slot), new long[] {100}, null, key.toStack(1), 0, BlockPos.ZERO,
                    null, new Ae2FingerprintCache(), new Ae2ExtractBudget(), 1L));
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
    void sparseDeliveryKeepsCircularOrderAndIgnoresStaleCapacityValues() {
        var level = mock(Level.class);
        when(level.getGameTime()).thenReturn(40L);
        when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var holder = new Ae2OutputStateHolder();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var storage = mock(MEStorage.class);
        long[] live = {14};
        when(storage.extract(eq(key), anyLong(), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
            long extracted = Math.min((long) call.getArgument(1), live[0]);
            live[0] -= extracted;
            return extracted;
        });
        List<IInventorySlot> slots = new ArrayList<>();
        for (int lane = 0; lane < 64; lane++) slots.add(BasicInventorySlot.at(null, 0, 0));
        long[] capacities = new long[64];
        Arrays.fill(capacities, 64L);
        capacities[2] = 5;
        capacities[31] = 6;
        capacities[63] = 7;
        long selected = (1L << 2) | (1L << 31) | (1L << 63);

        var result = Ae2InputExecution.pullBatchForType(level, holder, key, 14, -1, storage,
                IActionSource.empty(), slots, capacities, null, key.toStack(1), 60, BlockPos.ZERO,
                null, new Ae2FingerprintCache(), new Ae2ExtractBudget(), selected);

        assertEquals(14, result.insertedCount());
        assertEquals(7, slots.get(63).getCount());
        assertEquals(5, slots.get(2).getCount());
        assertEquals(2, slots.get(31).getCount());
        assertEquals(14, slots.stream().mapToInt(IInventorySlot::getCount).sum());
        assertEquals(0, live[0]);
        assertFalse(holder.getPendingItemBuffer().isBlocked());
        assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
    }

    @Test
    void largerFactoryDeliveryUsesEveryPositiveQuotaBeyondTheMask() {
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var holder = new Ae2OutputStateHolder();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var storage = mock(MEStorage.class);
        when(storage.extract(eq(key), eq(11L), eq(Actionable.MODULATE), any())).thenReturn(11L);
        List<IInventorySlot> slots = new ArrayList<>();
        for (int lane = 0; lane < 65; lane++) slots.add(BasicInventorySlot.at(null, 0, 0));
        long[] capacities = new long[65];
        capacities[1] = 5;
        capacities[64] = 7;

        var result = Ae2InputExecution.pullBatchForType(level, holder, key, 11, -1, storage,
                IActionSource.empty(), slots, capacities, null, key.toStack(1), 63, BlockPos.ZERO,
                null, new Ae2FingerprintCache(), new Ae2ExtractBudget(), 0L);

        assertEquals(11, result.insertedCount());
        assertEquals(7, slots.get(64).getCount());
        assertEquals(4, slots.get(1).getCount());
        assertEquals(11, slots.stream().mapToInt(IInventorySlot::getCount).sum());
        assertFalse(holder.getPendingItemBuffer().isBlocked());
        assertEquals(0, holder.getPendingItemBuffer().getTotalAmount());
    }

    @Test
    void sparseDeliveryPreservesConfirmedPrefixAndUnknownCurrentOffer() {
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var holder = new Ae2OutputStateHolder();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var storage = mock(MEStorage.class);
        when(storage.extract(eq(key), eq(13L), eq(Actionable.MODULATE), any())).thenReturn(13L);
        var prefix = BasicInventorySlot.at(null, 0, 0);
        var suffix = BasicInventorySlot.at(null, 0, 0);
        var actualFaultSlot = BasicInventorySlot.at(null, 0, 0);
        var fault = mock(IInventorySlot.class);
        when(fault.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
            ItemStack offered = call.getArgument(0);
            actualFaultSlot.setStack(offered.copyWithCount(2));
            throw new IllegalStateException("accepted two before failure");
        });
        List<IInventorySlot> slots = new ArrayList<>();
        for (int lane = 0; lane < 8; lane++) slots.add(BasicInventorySlot.at(null, 0, 0));
        slots.set(6, prefix);
        slots.set(1, fault);
        slots.set(4, suffix);
        long[] capacities = new long[8];
        Arrays.fill(capacities, 64L);
        capacities[6] = 4;
        capacities[1] = 5;
        capacities[4] = 4;

        assertThrows(IllegalStateException.class, () -> Ae2InputExecution.pullBatchForType(level,
                holder, key, 13, -1, storage, IActionSource.empty(), slots, capacities, null,
                key.toStack(1), 6, BlockPos.ZERO, null, new Ae2FingerprintCache(),
                new Ae2ExtractBudget(), (1L << 6) | (1L << 1) | (1L << 4)));

        assertEquals(4, prefix.getCount());
        assertEquals(2, actualFaultSlot.getCount());
        assertTrue(suffix.isEmpty());
        assertTrue(holder.getPendingItemBuffer().isBlocked());
        var transfer = holder.getPendingItemBuffer().transfer();
        assertEquals("slot:1", transfer.destination());
        assertEquals(4, transfer.knownAmount());
        assertEquals(5, transfer.offeredAmount());
        var saved = new CompoundTag();
        holder.savePendingItems(saved);
        var restored = new Ae2OutputStateHolder();
        restored.loadPendingItems(saved);
        var recovered = restored.getPendingItemBuffer().transfer();
        recovered.resolve(recovered.request(), 2, restored.getPendingItemBuffer(), 41);
        assertEquals(7, restored.getPendingItemBuffer().getTotalAmount());
        assertEquals(13, prefix.getCount() + actualFaultSlot.getCount()
                + restored.getPendingItemBuffer().getTotalAmount());
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
        var iron = new Ae2InputExecution.PullEntry(AEItemKey.of(Items.IRON_INGOT), 100);
        var gold = new Ae2InputExecution.PullEntry(AEItemKey.of(Items.GOLD_INGOT), 100);
        var occupied = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        occupied.setStack(new ItemStack(Items.IRON_INGOT, 64));
        var empty = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        var lanes = new Ae2InputLaneSnapshot();
        lanes.capture(List.of(occupied, empty), 2);
        assertTrue(Ae2InputExecution.hasUnassignedDemand(List.of(iron, gold), lanes, 2));
        gold.remaining = 0;
        assertFalse(Ae2InputExecution.hasUnassignedDemand(List.of(iron, gold), lanes, 2));
        gold.remaining = 100;
        empty.setStack(new ItemStack(Items.GOLD_INGOT, 64));
        lanes.refresh(1);
        assertFalse(Ae2InputExecution.hasUnassignedDemand(List.of(iron, gold), lanes, 2));
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
        Ae2LeftoverReturner.retryPendingItems(level, holder, storage, List.of(first, second), 0, null, IActionSource.empty());
        Ae2LeftoverReturner.retryPendingItems(level, holder, storage, List.of(first, second), 100, null, IActionSource.empty());
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
        Ae2LeftoverReturner.retryPendingItems(level, holder, storage, List.of(), 0, null, IActionSource.empty());
        Ae2LeftoverReturner.retryPendingItems(level, holder, storage, List.of(), 100, null, IActionSource.empty());
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
