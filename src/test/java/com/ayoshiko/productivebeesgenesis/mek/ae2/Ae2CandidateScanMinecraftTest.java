package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void resolvedDirectEntriesKeepAmountsAndResolveReplacementFingerprints() {
        var fixture = new Fixture();
        var first = namedComb("first");
        var replacement = namedComb("replacement");
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        fixture.filter.setPreciseMode(true);
        fixture.holder.setAeInputNbtIgnore(false);
        fixture.filter.setDirectEntryFingerprintAt(0, Ae2ItemFingerprint.encode(first, registries));
        fixture.filter.setDirectAmountAt(0, 7);
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);

        var initial = fixture.collect(100);
        assertEquals(first, initial.getFirst().key);
        assertEquals(7, initial.getFirst().remaining);
        assertEquals(first, fixture.filter.getResolvedDirectKey(0));

        fixture.filter.setDirectAmountAt(0, 9);
        var resolved = fixture.collect(101);
        assertEquals(1, resolved.size());
        assertEquals(first, resolved.getFirst().key);
        assertEquals(9, resolved.getFirst().remaining);

        fixture.filter.setDirectEntryFingerprintAt(0, Ae2ItemFingerprint.encode(replacement, registries));
        fixture.filter.setDirectAmountAt(0, 11);
        var replaced = fixture.collect(102);
        assertEquals(1, replaced.size());
        assertEquals(replacement, replaced.getFirst().key);
        assertEquals(11, replaced.getFirst().remaining);
        assertEquals(replacement, fixture.filter.getResolvedDirectKey(0));
    }

    @Test
    void directoryCursorRetainsRotationAndRelocatesAfterRebuild() {
        var fixture = new Fixture();
        for (int index = 0; index < 9; index++) fixture.inventory.add(namedComb("comb_" + index), 10 + index);
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
        fixture.collectWithoutFilter(100);
        var expected = new ArrayList<AEItemKey>();
        var seen = new java.util.HashSet<AEItemKey>();
        var previous = (AEItemKey) fixture.holder.getInputCombCandidateCursor();
        Ae2CursorScan.collectPrioritizedRotating(expected, seen, List.of(),
                fixture.buffers.scanCandidateKeys, null, previous, 6, key -> fixture.inventory.get(key) > 0);

        var stable = fixture.collectWithoutFilter(101);
        assertEquals(expected, stable.stream().map(entry -> entry.key).toList());
        assertEquals(stable.getLast().key, fixture.holder.getInputCombCandidateCursor());

        previous = (AEItemKey) fixture.holder.getInputCombCandidateCursor();
        fixture.inventory.remove(previous);
        fixture.inventory.add(namedComb("new_component_variant"), 27);
        var refreshed = fixture.collectWithoutFilter(110);
        expected.clear();
        seen.clear();
        Ae2CursorScan.collectPrioritizedRotating(expected, seen, List.of(),
                fixture.buffers.scanCandidateKeys, null, previous, 6, key -> fixture.inventory.get(key) > 0);
        assertEquals(expected, refreshed.stream().map(entry -> entry.key).toList());
        assertEquals(6, refreshed.size());
        assertTrue(refreshed.stream().allMatch(entry -> entry.remaining == fixture.inventory.get(entry.key)));
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
        fixture.holder.setAeInputNbtIgnore(false);
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
        verifyNoInteractions(fixture.service, fixture.storage);
        live[0] = 95;
        var slot = BasicInventorySlot.at(null, 0, 0);
        Ae2InputExecution.pullBatchForType(fixture.level, fixture.holder, key, entry.remaining,
                entry.reserveFloor, fixture.storage, IActionSource.empty(), List.of(slot),
                new long[] {64}, null, key.toStack(1), 0, BlockPos.ZERO, null,
                fixture.buffers.fingerprintCache, fixture.buffers.extractBudget, 1L);
        assertEquals(5, slot.getCount());
        assertEquals(90, live[0]);
        assertEquals(1_000, fixture.inventory.get(key));
        assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
        assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
        var calls = inOrder(fixture.storage);
        calls.verify(fixture.storage).extract(eq(key), eq(122L), eq(Actionable.SIMULATE), any());
        calls.verify(fixture.storage).extract(eq(key), eq(5L), eq(Actionable.MODULATE), any());
        calls.verifyNoMoreInteractions();
    }

    @Test
    void preciseTypedCombIndexKeepsDuplicateLimitsAndFullComponentMatching() {
        var filter = new Ae2InputFilter();
        filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        filter.setPreciseMode(true);
        var first = typedComb("iron", false, "first");
        var renamed = typedComb("iron", false, "renamed");
        var block = typedComb("iron", true, "first");
        var anotherType = typedComb("gold", false, "first");
        direct(filter, 0, first, 7);
        direct(filter, 1, first, 9);
        direct(filter, 2, block, 5);

        for (var candidate : List.of(first, renamed, block, anotherType)) {
            assertMatchesLinearFilter(filter, candidate, false, 100, false);
        }
        assertEquals(16, filter.getPullLimitIfAllowed(first, 100, false));
        assertEquals(5, filter.getPullLimitIfAllowed(block, 100, false));
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, filter.getPullLimitIfAllowed(renamed, 100, false));

        filter.setDirectReserveAmountAt(0, 11);
        filter.toggleDirectNetworkStockAt(0);
        filter.toggleDirectUnlimitedAt(1);
        assertMatchesLinearFilter(filter, first, false, 73, false);
        filter.setFilterMode(Ae2InputFilter.FilterMode.BLACKLIST);
        assertMatchesLinearFilter(filter, first, false, 73, false);
        assertMatchesLinearFilter(filter, renamed, false, 73, false);
    }

    @Test
    void typedCombMatchingFallsBackForFuzzyIgnoreComponentsAndUnresolvedKeys() {
        var filter = new Ae2InputFilter();
        filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        filter.setPreciseMode(true);
        var first = typedComb("iron", false, "first");
        var renamed = typedComb("iron", false, "renamed");
        var block = typedComb("iron", true, "first");
        direct(filter, 0, first, 7);
        assertMatchesLinearFilter(filter, first, false, 100, false);
        filter.setEntryAt(1, ResourceLocation.fromNamespaceAndPath("productivebees", "iron"), false);
        assertMatchesLinearFilter(filter, renamed, false, 100, false);
        assertNotEquals(Ae2InputFilter.PULL_DISALLOWED, filter.getPullLimitIfAllowed(renamed, 100, false));
        assertMatchesLinearFilter(filter, block, false, 100, false);
        filter.removeEntryAt(1);
        assertMatchesLinearFilter(filter, renamed, true, 100, false);
        assertEquals(7, filter.getPullLimitIfAllowed(renamed, 100, true));
        filter.setPreciseMode(false);
        assertMatchesLinearFilter(filter, block, false, 100, false);
        assertEquals(7, filter.getPullLimitIfAllowed(block, 100, false));
        filter.setPreciseMode(true);

        var saved = new CompoundTag();
        filter.save(saved);
        var restored = new Ae2InputFilter();
        restored.load(saved);
        assertNull(restored.getResolvedDirectKey(0));
        assertMatchesLinearFilter(restored, first, false, 100, false);
        restored.resolveDirectKey(0, first);
        assertMatchesLinearFilter(restored, first, false, 100, false);
        restored.resolveDirectKey(0, renamed);
        assertMatchesLinearFilter(restored, renamed, false, 100, false);
        assertMatchesLinearFilter(restored, first, false, 100, false);
        assertEquals(7, restored.getPullLimitIfAllowed(renamed, 100, false));
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, restored.getPullLimitIfAllowed(first, 100, false));
    }

    @Test
    void compiledFilterKeepsLiveStockDynamicAndOldConfigurationCoherent() {
        var filter = new Ae2InputFilter();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var copper = AEItemKey.of(Items.COPPER_INGOT);
        filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        filter.setPreciseMode(true);
        direct(filter, 0, iron, 40);
        direct(filter, 1, iron, 25);
        direct(filter, 2, gold, 8);
        filter.setDirectReserveAmountAt(0, 30);
        filter.toggleDirectNetworkStockAt(0);
        var original = filter.pullSnapshot(false);
        var decision = new Ae2PullDecision();
        assertEquals(65, original.pullLimit(iron, 100, false, decision));
        assertEquals(10, original.pullLimit(iron, 40, false, decision));
        assertEquals(0, original.pullLimit(iron, 20, false, decision));
        assertEquals(30, decision.reserveFloor);
        filter.setDirectVisibleAmountAt(0, 999);
        assertSame(original, filter.pullSnapshot(false));

        filter.setDirectAmountAt(1, 90);
        filter.setDirectReserveAmountAt(0, 50);
        filter.resolveDirectKey(2, copper);
        var revised = filter.pullSnapshot(false);
        assertNotSame(original, revised);
        assertEquals(50, revised.pullLimit(iron, 100, false, decision));
        assertEquals(65, original.pullLimit(iron, 100, false, decision));
        // gold 尚未在旧快照中查过：冷查询也必须使用原数组和原解析键。
        assertEquals(8, original.pullLimit(gold, 100, false, decision));
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, revised.pullLimit(gold, 100, false, decision));
        assertEquals(8, revised.pullLimit(copper, 100, false, decision));
        filter.toggleDirectUnlimitedAt(0);
        assertEquals(450, filter.pullSnapshot(false).pullLimit(iron, 500, false, decision));
        assertTrue(decision.unlimited);
        filter.setFilterMode(Ae2InputFilter.FilterMode.BLACKLIST);
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, filter.pullSnapshot(false).pullLimit(iron, 500, false, decision));
        assertEquals(50, revised.pullLimit(iron, 100, false, decision));
    }

    @Test
    void emptyFilterSnapshotsRespectModeTagsAndGlobalPolicyChanges() {
        var filter = new Ae2InputFilter();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var decision = new Ae2PullDecision();
        var initial = filter.pullSnapshot(false);
        assertEquals(-1, initial.pullLimit(key, 80, false, decision));
        filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        var whitelist = filter.pullSnapshot(false);
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, whitelist.pullLimit(key, 80, false, decision));
        var tagged = filter.pullSnapshot(true);
        assertEquals(-1, tagged.pullLimit(key, 80, false, decision));
        assertFalse(decision.marked);

        filter.setGlobalNetworkStock(true);
        filter.setGlobalReserveAmount(30);
        var reserved = filter.pullSnapshot(true);
        assertEquals(50, reserved.pullLimit(key, 80, false, decision));
        assertEquals(0, reserved.pullLimit(key, 20, false, decision));
        filter.toggleUnlimitedAllFallback();
        var unlimited = filter.pullSnapshot(true);
        assertEquals(50, unlimited.pullLimit(key, 80, false, decision));
        assertTrue(decision.unlimited);
        assertEquals(30, decision.reserveFloor);
        filter.cycleFilterMode();
        assertEquals(50, filter.pullSnapshot(false).pullLimit(key, 80, false, decision));
        filter.resetPersistentState();
        assertEquals(-1, filter.pullSnapshot(false).pullLimit(key, 80, false, decision));
        assertFalse(decision.unlimited);
        assertEquals(-1, decision.reserveFloor);
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, whitelist.pullLimit(key, 80, false, decision));
        assertEquals(50, reserved.pullLimit(key, 80, false, decision));
        assertFalse(decision.unlimited);
    }

    @Test
    void compiledRuleEvictionKeepsCompleteKeysAndMatchingModesSeparate() {
        var filter = new Ae2InputFilter();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        filter.setPreciseMode(true);
        direct(filter, 0, iron, 40);
        var snapshot = filter.pullSnapshot(false);
        var decision = new Ae2PullDecision();
        for (int variant = 0; variant < 140; variant++) {
            var stack = new ItemStack(Items.IRON_INGOT);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("variant_" + variant));
            var key = AEItemKey.of(stack);
            assertEquals(Ae2InputFilter.PULL_DISALLOWED, snapshot.pullLimit(key, 100, false, decision));
            assertEquals(40, snapshot.pullLimit(key, 100, true, decision));
        }
        assertEquals(40, snapshot.pullLimit(iron, 100, false, decision));
        var first = new ItemStack(Items.IRON_INGOT);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("variant_0"));
        assertEquals(Ae2InputFilter.PULL_DISALLOWED, snapshot.pullLimit(AEItemKey.of(first), 100, false, decision));
        assertEquals(40, snapshot.pullLimit(AEItemKey.of(first), 100, true, decision));
        filter.setDirectAmountAt(0, 7);
        assertEquals(7, filter.pullSnapshot(false).pullLimit(iron, 100, false, decision));
        assertEquals(40, snapshot.pullLimit(iron, 100, false, decision));
    }

    @Test
    void preciseReserveCollectionKeepsDemandAndReadsLiveStockOnlyBeforeCommit() {
        for (boolean globalReserve : List.of(false, true)) {
            var fixture = new Fixture();
            var key = namedComb("live_stock");
            fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
            fixture.filter.setPreciseMode(true);
            fixture.holder.setAeInputNbtIgnore(false);
            fixture.direct(key, 64);
            if (globalReserve) {
                fixture.filter.setGlobalReserveAmount(10);
                fixture.filter.setGlobalNetworkStock(true);
            } else {
                fixture.filter.setDirectReserveAmountAt(0, 10);
                fixture.filter.toggleDirectNetworkStockAt(0);
            }
            fixture.inventory.add(key, 1);
            when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
            long[] live = {30};
            when(fixture.storage.extract(eq(key), anyLong(), any(), any())).thenAnswer(call -> {
                long extracted = Math.min((long) call.getArgument(1), live[0]);
                if (call.getArgument(2) == Actionable.MODULATE) {
                    live[0] -= extracted;
                }
                return extracted;
            });
            var snapshot = fixture.filter.pullSnapshot(false);

            var first = fixture.collect(100);
            assertEquals(1, first.size());
            assertEquals(64, first.getFirst().remaining);
            assertEquals(10, first.getFirst().reserveFloor);
            verifyNoInteractions(fixture.service, fixture.storage);
            var firstSlot = BasicInventorySlot.at(null, 0, 0);
            Ae2InputExecution.pullBatchForType(fixture.level, fixture.holder, key,
                    first.getFirst().remaining, first.getFirst().reserveFloor, fixture.storage,
                    IActionSource.empty(), List.of(firstSlot), new long[] {64}, null, key.toStack(1),
                    0, BlockPos.ZERO, null, fixture.buffers.fingerprintCache,
                    fixture.buffers.extractBudget, 1L);
            assertEquals(20, firstSlot.getCount());
            assertEquals(10, live[0]);

            live[0] = 15;
            clearInvocations(fixture.service, fixture.storage);
            var next = fixture.collect(101);
            assertEquals(1, next.size());
            assertEquals(64, next.getFirst().remaining);
            assertEquals(10, next.getFirst().reserveFloor);
            assertSame(snapshot, fixture.filter.pullSnapshot(false));
            verifyNoInteractions(fixture.service, fixture.storage);
            var nextSlot = BasicInventorySlot.at(null, 0, 0);
            Ae2InputExecution.pullBatchForType(fixture.level, fixture.holder, key,
                    next.getFirst().remaining, next.getFirst().reserveFloor, fixture.storage,
                    IActionSource.empty(), List.of(nextSlot), new long[] {64}, null, key.toStack(1),
                    0, BlockPos.ZERO, null, fixture.buffers.fingerprintCache,
                    fixture.buffers.extractBudget, 1L);
            assertEquals(5, nextSlot.getCount());
            assertEquals(10, live[0]);
            assertEquals(1, fixture.inventory.get(key));
            assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
            assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
            var calls = inOrder(fixture.storage);
            calls.verify(fixture.storage).extract(eq(key), eq(74L), eq(Actionable.SIMULATE), any());
            calls.verify(fixture.storage).extract(eq(key), eq(5L), eq(Actionable.MODULATE), any());
            calls.verifyNoMoreInteractions();
        }
    }

    @Test
    void healthyReserveWaitDoesNotDelayRefillAfterRepeatedEmptyRounds() {
        for (boolean globalReserve : List.of(false, true)) {
            var fixture = new Fixture();
            var key = namedComb("reserve_wait");
            fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
            fixture.filter.setPreciseMode(true);
            fixture.holder.setAeInputNbtIgnore(false);
            fixture.direct(key, 64);
            if (globalReserve) {
                fixture.filter.setGlobalReserveAmount(10);
                fixture.filter.setGlobalNetworkStock(true);
            } else {
                fixture.filter.setDirectReserveAmountAt(0, 10);
                fixture.filter.toggleDirectNetworkStockAt(0);
            }
            when(fixture.host.productivebeesgenesis$getAe2Level()).thenReturn(fixture.level);
            when(fixture.host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
            long[] live = {10};
            when(fixture.storage.extract(eq(key), anyLong(), any(), any())).thenAnswer(call -> {
                long extracted = Math.min((long) call.getArgument(1), live[0]);
                if (call.getArgument(2) == Actionable.MODULATE) {
                    live[0] -= extracted;
                }
                return extracted;
            });
            var slot = BasicInventorySlot.at(null, 0, 0);
            var fairness = new Ae2InputFairnessScheduler();
            try (var health = mockStatic(Ae2StorageHealth.class, CALLS_REAL_METHODS)) {
                health.when(() -> Ae2StorageHealth.isPathological(anyLong())).thenReturn(false);
                for (long tick = 100; tick < 106; tick++) {
                    when(fixture.level.getGameTime()).thenReturn(tick);
                    fairness.roll(tick);
                    var result = Ae2InputExecution.execute(fixture.host, fixture.holder,
                            fixture.buffers, fixture.collect(tick), List.of(slot), fixture.storage,
                            fixture.backoff, fairness, 64, 64, 64, IActionSource.empty());
                    assertEquals(0, result.totalPulled());
                    assertEquals(10, live[0]);
                    assertTrue(slot.isEmpty());
                    assertEquals(0, fixture.backoff.size());
                }
                verify(fixture.backoff, never()).recordFailure(any(), anyLong());
                verify(fixture.storage, never()).extract(any(), anyLong(), eq(Actionable.MODULATE), any());

                live[0] = 17;
                when(fixture.level.getGameTime()).thenReturn(106L);
                fairness.roll(106);
                var refilled = Ae2InputExecution.execute(fixture.host, fixture.holder,
                        fixture.buffers, fixture.collect(106), List.of(slot), fixture.storage,
                        fixture.backoff, fairness, 64, 64, 64, IActionSource.empty());
                assertEquals(7, refilled.totalPulled());
                assertEquals(7, slot.getCount());
                assertTrue(key.matches(slot.getStack()));
                assertEquals(10, live[0]);
                assertEquals(0, fixture.backoff.size());
                assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
                assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
            } finally {
                Ae2NetworkWorkCoordinator.clearAll();
            }
        }
    }

    @Test
    void slowReserveWaitStillRecordsPerKeyBackoffWithoutExtracting() {
        var fixture = new Fixture();
        var key = namedComb("slow_reserve_wait");
        when(fixture.storage.extract(eq(key), anyLong(), eq(Actionable.SIMULATE), any()))
                .thenReturn(10L);
        var slot = BasicInventorySlot.at(null, 0, 0);
        try (var health = mockStatic(Ae2StorageHealth.class, CALLS_REAL_METHODS)) {
            health.when(() -> Ae2StorageHealth.isPathological(anyLong())).thenReturn(true);
            var result = Ae2InputExecution.pullBatchForType(fixture.level, fixture.holder, key,
                    64, 10, fixture.storage, IActionSource.empty(), List.of(slot),
                    new long[] {64}, null, key.toStack(1), 0, BlockPos.ZERO, fixture.backoff,
                    fixture.buffers.fingerprintCache, fixture.buffers.extractBudget, 1L);
            assertEquals(0, result.insertedCount());
            assertTrue(result.slowExtract());
            assertTrue(result.degraded());
            assertFalse(result.healthy());
            verify(fixture.backoff).recordFailure(eq(key), anyLong());
            assertEquals(1, fixture.backoff.size());
            assertTrue(slot.isEmpty());
            verify(fixture.storage, never()).extract(any(), anyLong(), eq(Actionable.MODULATE), any());
            assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
            assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
        }
    }

    @Test
    void candidateCollectionCarriesResourceDecisionOnceAndRefreshesOnNextCollection() {
        var accepted = namedComb("accepted");
        var deferred = namedComb("deferred");
        var rejected = namedComb("rejected");
        Map<AEItemKey, Ae2ResourceInputGate.Decision> decisions = new HashMap<>();
        decisions.put(accepted, Ae2ResourceInputGate.Decision.ACCEPTED);
        decisions.put(deferred, Ae2ResourceInputGate.Decision.DEFERRED);
        decisions.put(rejected, Ae2ResourceInputGate.Decision.REJECTED);
        try (var gates = mockConstruction(Ae2ResourceInputGate.class, (gate, context) -> {
            when(gate.resourcesAvailable(any(AEItemKey.class))).thenReturn(true);
            when(gate.decision(any(AEItemKey.class)))
                    .thenAnswer(call -> decisions.get(call.getArgument(0)));
        })) {
            var fixture = new Fixture();
            fixture.inventory.add(accepted, 8);
            fixture.inventory.add(deferred, 9);
            fixture.inventory.add(rejected, 10);
            when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
            var expectedOrder = new ArrayList<AEItemKey>();
            for (var entry : fixture.inventory) {
                if (!entry.getKey().equals(rejected)) expectedOrder.add((AEItemKey) entry.getKey());
            }

            var first = fixture.collect(100);
            assertEquals(expectedOrder, first.stream().map(entry -> entry.key).toList());
            assertEquals(17, first.stream().mapToInt(entry -> entry.remaining).sum());
            assertTrue(first.stream().filter(entry -> entry.key.equals(deferred)).findFirst().orElseThrow().resourceDeferred);
            assertFalse(first.stream().filter(entry -> entry.key.equals(accepted)).findFirst().orElseThrow().resourceDeferred);
            var gate = fixture.buffers.resourceInputGate;
            for (var key : List.of(accepted, deferred, rejected)) verify(gate, times(1)).decision(key);

            decisions.put(deferred, Ae2ResourceInputGate.Decision.ACCEPTED);
            decisions.put(rejected, Ae2ResourceInputGate.Decision.ACCEPTED);
            clearInvocations(gate);
            var next = fixture.collect(101);
            assertEquals(Set.of(accepted, deferred, rejected),
                    next.stream().map(entry -> entry.key).collect(java.util.stream.Collectors.toSet()));
            assertEquals(27, next.stream().mapToInt(entry -> entry.remaining).sum());
            assertTrue(next.stream().noneMatch(entry -> entry.resourceDeferred));
            for (var key : List.of(accepted, deferred, rejected)) verify(gate, times(1)).decision(key);
            verifyNoInteractions(fixture.storage);
        }
    }

    @Test
    void coolingDirectTypesRetainTheirLaneShareAndCanResumeAfterBackoff() {
        var fixture = new Fixture();
        when(fixture.host.productivebeesgenesis$getAe2Level()).thenReturn(fixture.level);
        when(fixture.host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
        fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        fixture.filter.setPreciseMode(true);
        fixture.holder.setAeInputNbtIgnore(false);
        var healthy = namedComb("healthy");
        var cooling = namedComb("cooling");
        direct(fixture.filter, 0, healthy, 1216);
        direct(fixture.filter, 1, cooling, 1216);
        fixture.filter.toggleDirectUnlimitedAt(0);
        fixture.filter.toggleDirectUnlimitedAt(1);
        doReturn(true).when(fixture.backoff).shouldSkip(eq(cooling), anyLong());
        when(fixture.storage.extract(any(), anyLong(), eq(Actionable.MODULATE), any()))
                .thenAnswer(call -> call.getArgument(1));
        List<IInventorySlot> slots = new ArrayList<>();
        for (int index = 0; index < 19; index++) slots.add(BasicInventorySlot.at(null, 0, 0));
        var fairness = new Ae2InputFairnessScheduler();
        var first = Ae2InputCandidatePolicy.collect(fixture.host, fixture.holder, fixture.buffers,
                fixture.level, fixture.service, fixture.storage, 100, 1216, 19,
                fixture.filter, true, fixture.backoff, IActionSource.empty());
        assertEquals(List.of(healthy, cooling), first.stream().map(entry -> entry.key).toList());
        fairness.roll(100);
        var firstResult = Ae2InputExecution.execute(fixture.host, fixture.holder, fixture.buffers,
                first, slots, fixture.storage, fixture.backoff, fairness, 1216, 1216, 64,
                IActionSource.empty());
        assertEquals(640, firstResult.totalPulled());
        assertEquals(10, slots.stream().filter(slot -> healthy.matches(slot.getStack())).count());
        assertEquals(9, slots.stream().filter(IInventorySlot::isEmpty).count());
        verify(fixture.storage, never()).extract(eq(cooling), anyLong(), any(), any());
        verifyNoInteractions(fixture.service);

        doReturn(false).when(fixture.backoff).shouldSkip(eq(cooling), anyLong());
        when(fixture.level.getGameTime()).thenReturn(101L);
        var next = Ae2InputCandidatePolicy.collect(fixture.host, fixture.holder, fixture.buffers,
                fixture.level, fixture.service, fixture.storage, 101, 576, 19,
                fixture.filter, true, fixture.backoff, IActionSource.empty());
        fairness.roll(101);
        var nextResult = Ae2InputExecution.execute(fixture.host, fixture.holder, fixture.buffers,
                next, slots, fixture.storage, fixture.backoff, fairness, 576, 576, 64,
                IActionSource.empty());
        assertEquals(576, nextResult.totalPulled());
        assertEquals(9, slots.stream().filter(slot -> cooling.matches(slot.getStack())).count());
        assertTrue(slots.stream().noneMatch(IInventorySlot::isEmpty));
        assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
        assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
    }

    @Test
    void multiTypeReservedCandidatesUseAvailableDemandAndResumeAfterReplenishment() {
        var fixture = new Fixture();
        when(fixture.host.productivebeesgenesis$getAe2Level()).thenReturn(fixture.level);
        when(fixture.host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
        fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
        fixture.filter.setPreciseMode(true);
        fixture.holder.setAeInputNbtIgnore(false);
        List<AEItemKey> keys = new ArrayList<>();
        Map<AEItemKey, Long> stock = new HashMap<>();
        for (int index = 0; index < 5; index++) {
            var key = namedComb("reserved_" + index);
            keys.add(key);
            direct(fixture.filter, index, key, 1216);
            fixture.filter.toggleDirectUnlimitedAt(index);
            fixture.filter.toggleDirectNetworkStockAt(index);
            fixture.filter.setDirectReserveAmountAt(index, 10);
            stock.put(key, index < 2 ? 586L : 10L);
            fixture.inventory.add(key, 1);
        }
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
        when(fixture.storage.extract(any(), anyLong(), any(), any())).thenAnswer(call -> {
            AEItemKey key = call.getArgument(0);
            long available = stock.getOrDefault(key, 0L);
            long extracted = Math.min((long) call.getArgument(1), available);
            if (call.getArgument(2) == Actionable.MODULATE) {
                stock.put(key, available - extracted);
            }
            return extracted;
        });
        List<IInventorySlot> slots = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            slots.add(BasicInventorySlot.at(null, 0, 0));
        }
        var fairness = new Ae2InputFairnessScheduler();
        var candidates = Ae2InputCandidatePolicy.collect(fixture.host, fixture.holder, fixture.buffers,
                fixture.level, fixture.service, fixture.storage, 100, 1152, 18,
                fixture.filter, true, fixture.backoff, IActionSource.empty());
        assertEquals(List.of(keys.get(0), keys.get(1)),
                candidates.stream().map(entry -> entry.key).toList());
        assertEquals(List.of(576, 576), candidates.stream().map(entry -> entry.remaining).toList());
        fairness.roll(100);
        try (var health = mockStatic(Ae2StorageHealth.class, CALLS_REAL_METHODS)) {
            health.when(() -> Ae2StorageHealth.isPathological(anyLong())).thenReturn(false);
            var first = Ae2InputExecution.execute(fixture.host, fixture.holder, fixture.buffers,
                    candidates, slots, fixture.storage, fixture.backoff, fairness,
                    1152, 1152, 64, IActionSource.empty());
            assertEquals(1152, first.totalPulled());
            assertEquals(9, slots.stream().filter(slot -> keys.get(0).matches(slot.getStack())).count());
            assertEquals(9, slots.stream().filter(slot -> keys.get(1).matches(slot.getStack())).count());
            assertTrue(slots.stream().noneMatch(IInventorySlot::isEmpty));
            for (var key : keys) {
                assertEquals(10L, stock.get(key));
            }

            slots.getFirst().setEmpty();
            stock.put(keys.get(2), 74L);
            when(fixture.level.getGameTime()).thenReturn(101L);
            var replenished = Ae2InputCandidatePolicy.collect(fixture.host, fixture.holder, fixture.buffers,
                    fixture.level, fixture.service, fixture.storage, 101, 64, 18,
                    fixture.filter, true, fixture.backoff, IActionSource.empty());
            assertEquals(List.of(keys.get(2)), replenished.stream().map(entry -> entry.key).toList());
            assertEquals(64, replenished.getFirst().remaining);
            fairness.roll(101);
            var resumed = Ae2InputExecution.execute(fixture.host, fixture.holder, fixture.buffers,
                    replenished, slots, fixture.storage, fixture.backoff, fairness,
                    64, 64, 64, IActionSource.empty());
            assertEquals(64, resumed.totalPulled());
            assertTrue(keys.get(2).matches(slots.getFirst().getStack()));
            assertEquals(64, slots.getFirst().getCount());
            assertEquals(10L, stock.get(keys.get(2)));
            assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
            assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
        } finally {
            Ae2NetworkWorkCoordinator.clearAll();
        }
    }

    @Test
    void globalReserveScanSkipsTheProbeWhenReportedStockCoversTheRequest() {
        var fixture = new Fixture();
        var key = namedComb("reported_stock");
        fixture.filter.setGlobalNetworkStock(true);
        fixture.filter.setGlobalReserveAmount(10);
        fixture.inventory.add(key, 74);
        when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);

        var candidates = fixture.collect(100);

        assertEquals(List.of(key), candidates.stream().map(entry -> entry.key).toList());
        assertEquals(64, candidates.getFirst().remaining);
        assertEquals(10, candidates.getFirst().reserveFloor);
        verifyNoInteractions(fixture.storage);
    }

    @Test
    void fuzzyReservedVariantsAtTheFloorDoNotOccupyLanesAndResumeAfterRefill() {
        // 本测试验证库存与车道分配；候选探针和实际提取都不以 Mockito 的调用耗时限流。
        try (var budgets = mockConstruction(Ae2ExtractBudget.class, (budget, context) ->
                when(budget.canExtractNow(anyLong(), anyInt(), anyLong())).thenReturn(true));
                var probes = mockConstruction(Ae2StockProbePolicy.class, (policy, context) ->
                        when(policy.shouldProbe(anyLong(), any())).thenReturn(true));
                var coordinator = mockStatic(Ae2NetworkWorkCoordinator.class, CALLS_REAL_METHODS)) {
            coordinator.when(() -> Ae2NetworkWorkCoordinator.recordResolvedExtractCost(
                    any(), anyLong(), anyLong(), anyLong())).thenAnswer(call -> null);
            var fixture = new Fixture();
            when(fixture.host.productivebeesgenesis$getAe2Level()).thenReturn(fixture.level);
            when(fixture.host.productivebeesgenesis$getAe2BlockPos()).thenReturn(BlockPos.ZERO);
            fixture.filter.setFilterMode(Ae2InputFilter.FilterMode.WHITELIST);
            fixture.filter.setPreciseMode(false);
            fixture.holder.setAeInputNbtIgnore(false);
            var ironBlock = typedComb("iron", true, "reserved");
            var goldBlock = typedComb("gold", true, "reserved");
            var ironComb = typedComb("iron", false, "reserved");
            var goldComb = typedComb("gold", false, "reserved");
            Map<AEItemKey, Long> stock = new HashMap<>();
            var blocks = List.of(ironBlock, goldBlock);
            for (int index = 0; index < blocks.size(); index++) {
                var key = blocks.get(index);
                direct(fixture.filter, index, key, 64);
                fixture.filter.toggleDirectUnlimitedAt(index);
                fixture.filter.toggleDirectNetworkStockAt(index);
                fixture.filter.setDirectReserveAmountAt(index, 10);
                stock.put(key, 138L);
            }
            stock.put(ironComb, 10L);
            stock.put(goldComb, 10L);
            for (var key : stock.keySet()) {
                fixture.inventory.add(key, 1);
            }
            when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
            when(fixture.storage.extract(any(), anyLong(), any(), any())).thenAnswer(call -> {
                AEItemKey key = call.getArgument(0);
                long available = stock.getOrDefault(key, 0L);
                long extracted = Math.min((long) call.getArgument(1), available);
                if (call.getArgument(2) == Actionable.MODULATE) {
                    stock.put(key, available - extracted);
                }
                return extracted;
            });
            List<IInventorySlot> slots = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                var slot = BasicInventorySlot.at(null, 0, 0);
                assertEquals(64, slot.getLimit(ironBlock.toStack(1)));
                slots.add(slot);
            }
            var fairness = new Ae2InputFairnessScheduler();
            try (var health = mockStatic(Ae2StorageHealth.class, CALLS_REAL_METHODS)) {
                health.when(() -> Ae2StorageHealth.isPathological(anyLong())).thenReturn(false);
                var candidates = Ae2InputCandidatePolicy.collect(fixture.host, fixture.holder, fixture.buffers,
                        fixture.level, fixture.service, fixture.storage, 100, 256, 4,
                        fixture.filter, true, fixture.backoff, IActionSource.empty());
                assertEquals(blocks, candidates.stream().map(entry -> entry.key).toList());
                assertEquals(List.of(128, 128), candidates.stream().map(entry -> entry.remaining).toList());
                fairness.roll(100);
                var filled = Ae2InputExecution.execute(fixture.host, fixture.holder, fixture.buffers,
                        candidates, slots, fixture.storage, fixture.backoff, fairness,
                        256, 256, 64, IActionSource.empty());
                assertEquals(256, filled.totalPulled());
                assertEquals(2, slots.stream().filter(slot -> ironBlock.matches(slot.getStack())).count());
                assertEquals(2, slots.stream().filter(slot -> goldBlock.matches(slot.getStack())).count());
                assertTrue(slots.stream().noneMatch(IInventorySlot::isEmpty));
                for (long remaining : stock.values()) {
                    assertEquals(10, remaining);
                }

                slots.getFirst().setEmpty();
                stock.put(ironComb, 74L);
                when(fixture.level.getGameTime()).thenReturn(101L);
                var replenished = Ae2InputCandidatePolicy.collect(fixture.host, fixture.holder, fixture.buffers,
                        fixture.level, fixture.service, fixture.storage, 101, 64, 4,
                        fixture.filter, true, fixture.backoff, IActionSource.empty());
                assertEquals(List.of(ironComb), replenished.stream().map(entry -> entry.key).toList());
                assertEquals(64, replenished.getFirst().remaining);
                fairness.roll(101);
                var refilled = Ae2InputExecution.execute(fixture.host, fixture.holder, fixture.buffers,
                        replenished, slots, fixture.storage, fixture.backoff, fairness,
                        64, 64, 64, IActionSource.empty());
                assertEquals(64, refilled.totalPulled());
                assertTrue(ironComb.matches(slots.getFirst().getStack()));
                assertEquals(10L, stock.get(ironComb));
                assertEquals(0, fixture.holder.getPendingItemBuffer().getTotalAmount());
                assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
            } finally {
                Ae2NetworkWorkCoordinator.clearAll();
            }
        }
    }

    @Test
    void ordinaryDirectoryIdentitySurvivesQuantityRefreshButChangesWithCompleteKeys() {
        var network = new Object();
        var inventory = new KeyCounter();
        var iron = AEItemKey.of(Items.RAW_IRON);
        var gold = AEItemKey.of(Items.RAW_GOLD);
        inventory.add(iron, 5);
        var first = Ae2NetworkCandidateDirectory.get(network, inventory, 100, inventory);
        assertEquals(List.of(iron), first.ordinaryKeys());

        inventory.add(iron, 7);
        var quantityRefresh = Ae2NetworkCandidateDirectory.get(network, inventory, 110, inventory);
        assertSame(first.ordinaryKeys(), quantityRefresh.ordinaryKeys());
        assertEquals(12, inventory.get(iron));
        assertTrue(quantityRefresh.generation() > first.generation());

        inventory.add(gold, 3);
        var added = Ae2NetworkCandidateDirectory.get(network, inventory, 120, inventory);
        assertNotSame(quantityRefresh.ordinaryKeys(), added.ordinaryKeys());
        assertEquals(Set.of(iron, gold), Set.copyOf(added.ordinaryKeys()));
        inventory.remove(iron);
        var removed = Ae2NetworkCandidateDirectory.get(network, inventory, 130, inventory);
        assertNotSame(added.ordinaryKeys(), removed.ordinaryKeys());
        assertEquals(List.of(gold), removed.ordinaryKeys());

        var renamed = new ItemStack(Items.RAW_GOLD);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("component_variant"));
        var variant = AEItemKey.of(renamed);
        inventory.remove(gold);
        inventory.add(variant, 3);
        var replaced = Ae2NetworkCandidateDirectory.get(network, inventory, 140, inventory);
        assertNotSame(removed.ordinaryKeys(), replaced.ordinaryKeys());
        assertEquals(List.of(variant), replaced.ordinaryKeys());
        assertEquals(List.of(gold), removed.ordinaryKeys());
    }

    @Test
    void ordinaryHostClassificationUsesStableKeysAndInvalidatesForRecipeTagsAndMode() {
        var allowed = new AtomicBoolean(true);
        long originalRecipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
        try (var caches = mockConstruction(Ae2SmeltingInputCache.class, (cache, context) ->
                when(cache.contains(any(), any())).thenAnswer(call -> allowed.get()))) {
            var fixture = new Fixture();
            fixture.holder.setSmeltingCompatEnabled(true);
            var iron = AEItemKey.of(Items.RAW_IRON);
            fixture.inventory.add(iron, 5);
            when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
            var cache = fixture.buffers.smeltingInputCache;

            var first = fixture.collectWithoutFilter(100);
            assertEquals(List.of(iron), first.stream().map(entry -> entry.key).toList());
            assertTrue(first.getFirst().smelting);
            verify(cache).contains(fixture.level, iron);

            fixture.inventory.add(iron, 7);
            clearInvocations(cache);
            var quantityRefresh = fixture.collectWithoutFilter(110);
            assertEquals(12, quantityRefresh.getFirst().remaining);
            verifyNoInteractions(cache);

            allowed.set(false);
            ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
            assertTrue(fixture.collectWithoutFilter(111).isEmpty());
            verify(cache).contains(fixture.level, iron);

            allowed.set(true);
            fixture.holder.setSmeltingCompatEnabled(false);
            clearInvocations(cache);
            assertTrue(fixture.collectWithoutFilter(112).isEmpty());
            verifyNoInteractions(cache);
            fixture.holder.setSmeltingCompatEnabled(true);
            assertEquals(List.of(iron), fixture.collectWithoutFilter(113).stream()
                    .map(entry -> entry.key).toList());
            verify(cache).contains(fixture.level, iron);

            clearInvocations(cache);
            assertTrue(fixture.holder.getAeTagFilter().apply("", "test:blocked"));
            assertEquals(List.of(iron), fixture.collectWithoutFilter(114).stream()
                    .map(entry -> entry.key).toList());
            verify(cache).contains(fixture.level, iron);
            allowed.set(false);
            fixture.holder.getAeTagFilter().reset();
            clearInvocations(cache);
            assertTrue(fixture.collectWithoutFilter(115).isEmpty());
            verify(cache).contains(fixture.level, iron);
            verifyNoInteractions(fixture.storage);
        } finally {
            ProductiveBeesGenesis.RECIPE_VERSION.set(originalRecipeVersion);
        }
    }

    @Test
    void combResourceEligibilityStillRefreshesWithinTheTenTickDirectoryWindow() {
        var key = namedComb("dynamic_resources");
        var available = new AtomicBoolean(false);
        try (var gates = mockConstruction(Ae2ResourceInputGate.class, (gate, context) -> {
            when(gate.resourcesAvailable(any(AEItemKey.class))).thenAnswer(call -> available.get());
            when(gate.decision(any(AEItemKey.class))).thenReturn(Ae2ResourceInputGate.Decision.ACCEPTED);
        })) {
            var fixture = new Fixture();
            fixture.inventory.add(key, 9);
            when(fixture.service.getCachedInventory()).thenReturn(fixture.inventory);
            assertTrue(fixture.collectWithoutFilter(100).isEmpty());
            var gate = fixture.buffers.resourceInputGate;

            available.set(true);
            clearInvocations(gate);
            assertTrue(fixture.collectWithoutFilter(109).isEmpty());
            verify(gate, never()).resourcesAvailable(any(AEItemKey.class));
            var refreshed = fixture.collectWithoutFilter(110);
            assertEquals(List.of(key), refreshed.stream().map(entry -> entry.key).toList());
            assertEquals(9, refreshed.getFirst().remaining);
            verify(gate).resourcesAvailable(key);
            verifyNoInteractions(fixture.storage);
        }
    }

    private static void assertMatchesLinearFilter(Ae2InputFilter filter, AEItemKey key,
            boolean ignoreNbt, long visibleStock, boolean tagFilterActive) {
        var saved = new CompoundTag();
        filter.save(saved);
        var state = Ae2InputFilterNbtCodec.load(saved);
        var keys = new AEItemKey[state.slots().length];
        for (int index = 0; index < keys.length; index++) {
            keys[index] = filter.getResolvedDirectKey(index);
        }
        var expected = new Ae2PullDecision();
        long expectedLimit = Ae2InputFilterQuerySupport.pullLimitIfAllowed(key, visibleStock, ignoreNbt,
                state.filterMode(), state.preciseMode(), state.slots(),
                Ae2InputFilterQuerySupport.compileFuzzyEntries(state.slots()), keys,
                state.directAmounts(), state.directReserveAmounts(), state.directUnlimited(),
                state.directNetworkStock(), state.unlimitedAllFallback(), tagFilterActive, true,
                state.globalNetworkStock(), state.globalReserveAmount(), null, expected);
        var actual = new Ae2PullDecision();
        long actualLimit = filter.getPullLimitIfAllowed(key, visibleStock, ignoreNbt, tagFilterActive, actual);

        assertEquals(expectedLimit, actualLimit);
        assertEquals(expected.admitted, actual.admitted);
        assertEquals(expected.marked, actual.marked);
        assertEquals(expected.unlimited, actual.unlimited);
        assertEquals(expected.reserveFloor, actual.reserveFloor);
    }

    private static void direct(Ae2InputFilter filter, int index, AEItemKey key, int amount) {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        filter.setDirectEntryFingerprintAt(index, Ae2ItemFingerprint.encode(key, registries));
        filter.resolveDirectKey(index, key);
        filter.setDirectAmountAt(index, amount);
    }

    private static AEItemKey typedComb(String bee, boolean block, String name) {
        var stack = new ItemStack(block ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get());
        stack.set(PbDataComponents.beeType(), ResourceLocation.fromNamespaceAndPath("productivebees", bee));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
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
        final Ae2KeyBackoffRegistry<AEItemKey> backoff = spy(new Ae2KeyBackoffRegistry<AEItemKey>());

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
