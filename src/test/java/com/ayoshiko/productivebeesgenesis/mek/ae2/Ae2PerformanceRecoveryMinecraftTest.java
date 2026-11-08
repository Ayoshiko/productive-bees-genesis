package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.storage.IStorageService;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import mekanism.api.inventory.IInventorySlot;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("minecraft")
class Ae2PerformanceRecoveryMinecraftTest {
    @AfterEach
    void clearSharedState() {
        Ae2NetworkCandidateDirectory.clearAll();
        Ae2FingerprintCache.clearShared();
    }

    @Test
    void ordinaryHostsShareReadsAndDiscoverNewKeysAtTheDirectoryBoundary() {
        var network = new Object();
        var service = mock(IStorageService.class);
        var inventory = new KeyCounter();
        var first = AEItemKey.of(Items.RAW_IRON);
        var second = AEItemKey.of(Items.RAW_GOLD);
        doAnswer(call -> {
            inventory.add(second, 7);
            return inventory;
        }).when(service).getCachedInventory();
        inventory.add(first, 5);
        assertSame(inventory, Ae2NetworkCandidateDirectory.readInventory(network, service, 100, false));
        var initial = Ae2NetworkCandidateDirectory.get(network, inventory, 100, inventory);
        assertEquals(2, initial.ordinaryKeys().size());
        clearInvocations(service);
        inventory.remove(second);
        for (long tick = 101; tick < 110; tick++) {
            assertSame(inventory, Ae2NetworkCandidateDirectory.readInventory(network, service, tick, false));
            assertSame(initial, Ae2NetworkCandidateDirectory.get(network, inventory, tick, inventory));
        }
        verifyNoInteractions(service);
        assertSame(inventory, Ae2NetworkCandidateDirectory.readInventory(network, service, 110, false));
        var refreshed = Ae2NetworkCandidateDirectory.get(network, inventory, 110, inventory);
        assertTrue(refreshed.ordinaryKeys().contains(second));
        assertTrue(refreshed.generation() > initial.generation());
        verify(service).getCachedInventory();
    }

    @Test
    void stockGuardsRefreshImmediatelyAndChangedNetworksDiscardTheSharedView() {
        var service = mock(IStorageService.class);
        var inventory = new KeyCounter();
        when(service.getCachedInventory()).thenReturn(inventory);
        var network = new Object();
        Ae2NetworkCandidateDirectory.readInventory(network, service, 100, false);
        Ae2NetworkCandidateDirectory.get(network, inventory, 100, inventory);
        Ae2NetworkCandidateDirectory.readInventory(network, service, 101, true);
        Ae2NetworkCandidateDirectory.readInventory(network, service, 101, true);
        verify(service, times(3)).getCachedInventory();
        Ae2NetworkCandidateDirectory.invalidate(network);
        Ae2NetworkCandidateDirectory.readInventory(network, service, 101, false);
        verify(service, times(4)).getCachedInventory();
        var other = new Object();
        Ae2NetworkCandidateDirectory.readInventory(other, service, 101, false);
        verify(service, times(5)).getCachedInventory();
        assertEquals(2, Ae2NetworkCandidateDirectory.directoryCountForTest());
    }

    @Test
    void fullReturnBackoffDoesNotSuppressHealthyPullsAndReconnectClearsBoth() {
        var holder = new Ae2OutputStateHolder();
        var state = holder.getPushState();
        long now = System.nanoTime();
        state.getReturnBackoff().recordFailure(now);
        assertTrue(state.getReturnBackoff().shouldSkip(now));
        assertFalse(state.getInputBackoff().shouldSkip(now));
        state.getInputBackoff().recordFailure(now);
        state.getInputBackoff().recordSuccess();
        assertTrue(state.getReturnBackoff().shouldSkip(now));
        holder.onGridChanged();
        assertFalse(state.getReturnBackoff().shouldSkip(now));
        assertFalse(state.getInputBackoff().shouldSkip(now));
    }

    @Test
    void newExtractionRetainsKnownLeftoverWhileReturnNetworkIsCooling() {
        var holder = new Ae2OutputStateHolder();
        var storage = mock(MEStorage.class);
        var slot = mock(IInventorySlot.class);
        var level = mock(Level.class);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        when(level.registryAccess()).thenReturn(registries);
        var key = AEItemKey.of(Items.RAW_IRON);
        when(storage.extract(eq(key), eq(5L), eq(Actionable.MODULATE), any(IActionSource.class))).thenReturn(5L);
        when(slot.insertItem(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        holder.getPushState().getReturnBackoff().recordFailure(System.nanoTime());
        Ae2InputExecution.pullBatchForType(level, holder, key, 5, -1, storage,
            IActionSource.empty(), List.of(slot), new long[] {5}, null, key.toStack(1), 0,
            BlockPos.ZERO, null, new Ae2FingerprintCache(), new Ae2ExtractBudget());
        assertEquals(5, holder.getPendingItemBuffer().getTotalAmount());
        assertFalse(holder.getPendingItemBuffer().isBlocked());
        verify(storage, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void boundedOfflineSnapshotKeepsLocalRecoveryReachableAndCopiesOwnership() {
        var buffer = new Ae2PendingItemBuffer();
        for (int index = 0; index < 60; index++) buffer.enqueueReturnToNetwork("network-" + index, 5, 10);
        buffer.enqueue("local", 7, 10);
        var offline = buffer.snapshot(10, 4, false);
        assertEquals(1, offline.size());
        assertEquals("local", offline.getFirst().fingerprint());
        buffer.consume("local", 7, 10);
        assertEquals(7, offline.getFirst().amount());
        assertEquals(300, buffer.getTotalAmount());
        var online = buffer.snapshot(10, 4, true);
        assertEquals(4, online.size());
        assertEquals("network-0", online.getFirst().fingerprint());
        assertEquals(60, buffer.snapshot(10).size());
    }

    @Test
    void decodeCachePreservesComponentsAndIsInvalidatedByReloadAndRegistryIdentity() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var stack = new ItemStack(Items.HONEYCOMB);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("组件身份"));
        var key = AEItemKey.of(stack);
        String encoded = Ae2ItemFingerprint.encode(key, registries);
        var first = Ae2FingerprintCache.decode(encoded, registries);
        assertEquals(key, first);
        assertSame(first, Ae2FingerprintCache.decode(encoded, registries));
        var provider = mock(HolderLookup.Provider.class);
        try (var codec = mockStatic(Ae2ItemFingerprint.class)) {
            codec.when(() -> Ae2ItemFingerprint.decode(encoded, provider)).thenReturn(key);
            assertEquals(key, Ae2FingerprintCache.decode(encoded, provider));
            assertSame(key, Ae2FingerprintCache.decode(encoded, provider));
            codec.verify(() -> Ae2ItemFingerprint.decode(encoded, provider));
            ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
            assertEquals(key, Ae2FingerprintCache.decode(encoded, provider));
            codec.verify(() -> Ae2ItemFingerprint.decode(encoded, provider), times(2));
        }
    }

    @Test
    void failedDecodesAndOversizedFingerprintsRemainRetryableWithoutCaching() {
        var provider = mock(HolderLookup.Provider.class);
        var key = AEItemKey.of(Items.HONEYCOMB);
        String huge = "{" + "x".repeat(Ae2FingerprintCache.MAX_CACHED_CHARACTERS);
        try (var codec = mockStatic(Ae2ItemFingerprint.class)) {
            codec.when(() -> Ae2ItemFingerprint.decode("missing", provider)).thenReturn(null, key);
            assertNull(Ae2FingerprintCache.decode("missing", provider));
            assertEquals(key, Ae2FingerprintCache.decode("missing", provider));
            codec.when(() -> Ae2ItemFingerprint.decode(huge, provider)).thenReturn(key);
            assertEquals(key, Ae2FingerprintCache.decode(huge, provider));
            assertEquals(key, Ae2FingerprintCache.decode(huge, provider));
            codec.verify(() -> Ae2ItemFingerprint.decode(huge, provider), times(2));
        }
    }
}
