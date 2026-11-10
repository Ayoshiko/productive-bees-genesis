package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
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
                fixture.buffers.fingerprintCache, fixture.buffers.extractBudget, 1L);
        assertEquals(5, slot.getCount());
        assertEquals(90, live[0]);
        assertEquals(1_000, fixture.inventory.get(key));
        assertFalse(fixture.holder.getPendingItemBuffer().isBlocked());
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

            var first = fixture.collectWithoutFilter(100);
            assertEquals(expectedOrder, first.stream().map(entry -> entry.key).toList());
            assertEquals(17, first.stream().mapToInt(entry -> entry.remaining).sum());
            assertTrue(first.stream().filter(entry -> entry.key.equals(deferred)).findFirst().orElseThrow().resourceDeferred);
            assertFalse(first.stream().filter(entry -> entry.key.equals(accepted)).findFirst().orElseThrow().resourceDeferred);
            var gate = fixture.buffers.resourceInputGate;
            for (var key : List.of(accepted, deferred, rejected)) verify(gate, times(1)).decision(key);

            decisions.put(deferred, Ae2ResourceInputGate.Decision.ACCEPTED);
            decisions.put(rejected, Ae2ResourceInputGate.Decision.ACCEPTED);
            clearInvocations(gate);
            var next = fixture.collectWithoutFilter(101);
            assertEquals(Set.of(accepted, deferred, rejected),
                    next.stream().map(entry -> entry.key).collect(java.util.stream.Collectors.toSet()));
            assertEquals(27, next.stream().mapToInt(entry -> entry.remaining).sum());
            assertTrue(next.stream().noneMatch(entry -> entry.resourceDeferred));
            for (var key : List.of(accepted, deferred, rejected)) verify(gate, times(1)).decision(key);
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
