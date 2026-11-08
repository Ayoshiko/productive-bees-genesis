package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2CandidateScanMinecraftTest {
    @AfterEach
    void releaseSharedState() {
        Ae2NetworkCandidateDirectory.clearAll();
        Ae2NetworkWorkCoordinator.clearAll();
    }

    @Test
    void preciseWhitelistUsesConfiguredCompleteKeyWithoutEnumeratingTheNetwork() {
        var fixture = new Fixture();
        var configured = namedComb("configured");
        fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        fixture.filter.setPreciseMode(true);
        fixture.holder.setAeInputNbtIgnore(false);
        fixture.direct(configured, 7);
        var selected = fixture.collect(100);
        assertEquals(1, selected.size());
        assertEquals(configured, selected.getFirst().key);
        assertEquals(7, selected.getFirst().remaining);
        assertTrue(selected.getFirst().marked);
        assertFalse(selected.getFirst().smelting);
        assertFalse(selected.getFirst().resourceDeferred);
        assertEquals(-1, selected.getFirst().reserveFloor);
        verifyNoInteractions(fixture.service, fixture.storage);
    }

    @Test
    void mixedDirectAndDirectoryPathsDeduplicateWithoutLosingComponentVariants() {
        var fixture = new Fixture();
        var first = namedComb("first");
        var second = namedComb("second");
        fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        fixture.filter.setPreciseMode(false);
        fixture.direct(first, 64);
        fixture.inventory.add(first, 8);
        fixture.inventory.add(second, 9);
        fixture.inventory.add(AEItemKey.of(Items.RAW_IRON), 64);
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
        var selected = fixture.collect(100);
        assertEquals(2, selected.size());
        assertEquals(Set.of(first, second), selected.stream().map(entry -> entry.key).collect(java.util.stream.Collectors.toSet()));
        assertTrue(selected.stream().allMatch(entry -> entry.marked && !entry.smelting));
        assertEquals(17, selected.stream().mapToInt(entry -> entry.remaining).sum());
        verify(fixture.service).getCachedInventory();
        verifyNoInteractions(fixture.storage);
    }

    @Test
    void sharedDirectoryDoesNotCacheQuantitiesOrRepeatHostClassification() {
        var fixture = new Fixture();
        var first = namedComb("first");
        var second = namedComb("second");
        fixture.inventory.add(first, 8);
        fixture.inventory.add(second, 9);
        fixture.inventory.add(AEItemKey.of(Items.RAW_IRON), 64);
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
        var firstPass = fixture.collectWithoutFilter(100);
        assertEquals(2, firstPass.size());
        fixture.inventory.remove(first);
        clearInvocations(fixture.host, fixture.service);
        var secondPass = fixture.collectWithoutFilter(101);
        assertEquals(1, secondPass.size());
        assertEquals(second, secondPass.getFirst().key);
        assertEquals(9, secondPass.getFirst().remaining);
        verify(fixture.host, never()).productivebeesgenesis$canProcessInput(any());
        verifyNoInteractions(fixture.service, fixture.storage);
    }

    @Test
    void candidateReserveIsRecheckedAgainstExternalChangesBeforeExactCommit() {
        var fixture = new Fixture();
        var key = namedComb("reserved");
        fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        fixture.filter.setPreciseMode(true);
        fixture.direct(key, 32);
        fixture.filter.setDirectReserveAmountAt(0, 90);
        fixture.filter.toggleDirectNetworkStockAt(0);
        fixture.inventory.add(key, 1_000);
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
        long[] live = {100};
        when(fixture.storage.extract(eq(key), anyLong(), any(), any())).thenAnswer(call -> {
            long extracted = Math.min((long) call.getArgument(1), live[0]);
            if (call.getArgument(2) == Actionable.MODULATE) live[0] -= extracted;
            return extracted;
        });
        var selected = fixture.collect(100);
        assertEquals(1, selected.size());
        var entry = selected.getFirst();
        assertEquals(90, entry.reserveFloor);
        assertEquals(32, entry.remaining);
        live[0] = 95;
        var slot = BasicInventorySlot.at(null, 0, 0);
        Ae2InputExecution.pullBatchForType(fixture.level, fixture.holder, key, entry.remaining,
                entry.reserveFloor, fixture.storage, IActionSource.empty(), List.of(slot),
                new long[] {64}, null, key.toStack(1), 0, BlockPos.ZERO, null,
                fixture.buffers.fingerprintCache, fixture.buffers.extractBudget);
        assertEquals(5, slot.getCount());
        assertEquals(90, live[0]);
        assertEquals(1_000, fixture.inventory.get(key));
        assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
    }

    private static AEItemKey namedComb(String name) {
        var stack = new ItemStack(Items.HONEYCOMB);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }

    private static final class Fixture {
        final IAe2InputHost host = mock(IAe2InputHost.class);
        final Level level = mock(Level.class);
        final IStorageService service = mock(IStorageService.class);
        final MEStorage storage = mock(MEStorage.class);
        final KeyCounter inventory = new KeyCounter();
        final Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
        final Ae2PushBuffers buffers = new Ae2PushBuffers();
        final Ae2InputFilter filter = holder.getOrCreateInputFilter();
        final Ae2KeyBackoffRegistry<AEItemKey> backoff = new Ae2KeyBackoffRegistry<>();

        Fixture() {
            holder.setSmeltingCompatEnabled(false);
            when(host.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
            when(host.productivebeesgenesis$canProcessInput(any())).thenReturn(true);
            when(level.getGameTime()).thenReturn(100L);
            when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        }

        void direct(AEItemKey key, int amount) {
            filter.setDirectEntryFingerprintAt(0, "candidate-test");
            filter.resolveDirectKey(0, key);
            filter.setDirectAmountAt(0, amount);
        }

        List<Ae2InputExecution.PullEntry> collect(long tick) {
            return Ae2InputCandidatePolicy.collect(host, holder, buffers, level, service, storage,
                    tick, 64, 3, filter, false, backoff, IActionSource.empty());
        }

        List<Ae2InputExecution.PullEntry> collectWithoutFilter(long tick) {
            return Ae2InputCandidatePolicy.collect(host, holder, buffers, level, service, storage,
                    tick, 64, 3, null, false, backoff, IActionSource.empty());
        }
    }
}
