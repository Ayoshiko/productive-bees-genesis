package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.DeferredItemOutput;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2DeferredItemOutputsMinecraftTest {
    private static int nextFixtureTick = 10_000;
    @Test
    void sameNetworkKeyUsesOneInsertAndDebitsEveryOriginalOwner() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            assertTrue(Ae2DeferredItemOutputs.enqueue(first.output));
            assertTrue(Ae2DeferredItemOutputs.enqueue(first.output));
            assertTrue(Ae2DeferredItemOutputs.enqueue(second.output));
            assertEquals(4, first.output.amount(0));
            assertEquals(6, second.output.amount(0));
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());

            Ae2DeferredItemOutputs.flush(fixture.server);

            verify(storage, times(1)).insert(eq(AEItemKey.of(first.output.template(0))),
                    eq(10L), eq(Actionable.MODULATE), any());
            assertEquals(0, first.output.amount(0));
            assertEquals(0, second.output.amount(0));
            assertEquals(List.of(4L), first.output.debits);
            assertEquals(List.of(6L), second.output.debits);
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            assertEquals(1, first.session.attemptedCount());
            assertEquals(1, second.session.attemptedCount());
            assertTrue(first.output.finished);
            assertTrue(second.output.finished);
        }
    }

    @Test
    void partialReceiptUsesContributionOrderAndLeavesTheExactSuffix() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            when(storage.insert(any(), eq(10L), eq(Actionable.MODULATE), any())).thenReturn(5L);
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(0, first.output.amount(0));
            assertEquals(5, second.output.amount(0));
            assertEquals(List.of(4L), first.output.debits);
            assertEquals(List.of(1L), second.output.debits);
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            verify(storage, times(1)).insert(any(), anyLong(), any(), any());
        }
    }

    @Test
    void zeroReceiptClearsAllPreparationsWithoutDebitingTheSources() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            when(storage.insert(any(), anyLong(), any(), any())).thenReturn(0L);
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(4, first.output.amount(0));
            assertEquals(6, second.output.amount(0));
            assertTrue(first.output.debits.isEmpty());
            assertTrue(second.output.debits.isEmpty());
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void receiverExceptionMovesEveryContributionToItsExistingCustodyAndCannotReplay() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            when(storage.insert(any(), anyLong(), any(), any()))
                    .thenThrow(new IllegalStateException("receipt lost after possible acceptance"));
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(0, first.output.amount(0));
            assertEquals(0, second.output.amount(0));
            assertTrue(first.custody.blocked());
            assertTrue(second.custody.blocked());
            assertEquals(4, first.custody.amount());
            assertEquals(6, second.custody.amount());
            assertEquals(0, first.custody.sourceDebitPending());
            assertEquals(0, second.custody.sourceDebitPending());
            assertEquals(first.custody.sequence(), second.custody.sequence());
            assertTrue(first.custody.recoveredItem().isEmpty());
            assertTrue(second.custody.recoveredItem().isEmpty());
            var restoredFirst = restore(first.custody);
            var restoredSecond = restore(second.custody);
            assertTrue(restoredFirst.blocked());
            assertTrue(restoredSecond.blocked());
            assertEquals(4, restoredFirst.amount());
            assertEquals(6, restoredSecond.amount());
            Ae2DeferredItemOutputs.flush(fixture.server);
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void invalidReceiptIsIsolatedAsUnknownForEveryOwner() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            when(storage.insert(any(), eq(10L), any(), any())).thenReturn(11L);
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertTrue(first.custody.blocked());
            assertTrue(second.custody.blocked());
            assertEquals(4, first.custody.amount());
            assertEquals(6, second.custody.amount());
            assertEquals(0, first.output.amount(0));
            assertEquals(0, second.output.amount(0));
            assertTrue(first.custody.failure().contains("Invalid shared AE item receipt"));
        }
    }

    @Test
    void differentCompleteComponentsAndDifferentNetworksNeverShareAnInsert() {
        try (var fixture = new Fixture()) {
            var firstStorage = storageAcceptingAll();
            var secondStorage = storageAcceptingAll();
            var first = fixture.endpoint(firstStorage, 4);
            var variant = fixture.endpoint(firstStorage, 6);
            variant.output.templates[0].set(DataComponents.CUSTOM_NAME, Component.literal("distinct identity"));
            var otherNetwork = fixture.endpoint(secondStorage, 9);
            fixture.enqueue(first, variant, otherNetwork);

            Ae2DeferredItemOutputs.flush(fixture.server);

            verify(firstStorage).insert(eq(AEItemKey.of(first.output.template(0))), eq(4L), any(), any());
            verify(firstStorage).insert(eq(AEItemKey.of(variant.output.template(0))), eq(6L), any(), any());
            verify(secondStorage).insert(eq(AEItemKey.of(otherNetwork.output.template(0))), eq(9L), any(), any());
            verify(firstStorage, times(2)).insert(any(), anyLong(), any(), any());
            verify(secondStorage, times(1)).insert(any(), anyLong(), any(), any());
            assertEquals(0, first.output.amount(0));
            assertEquals(0, variant.output.amount(0));
            assertEquals(0, otherNetwork.output.amount(0));
        }
    }

    @Test
    void oneDeferredKeyDoesNotCancelTheSameOwnersHealthyKey() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 3, 7);
            endpoint.output.templates[1].set(DataComponents.CUSTOM_NAME, Component.literal("healthy key"));
            var deferredKey = AEItemKey.of(endpoint.output.template(0));
            var healthyKey = AEItemKey.of(endpoint.output.template(1));
            var backoff = spy(new Ae2KeyBackoffRegistry<AEItemKey>());
            doReturn(true).when(backoff).shouldSkip(eq(deferredKey), anyLong());
            endpoint.session.reset(endpoint.holder, storage, backoff, fixture.gameTick, null);
            fixture.enqueue(endpoint);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(3, endpoint.output.amount(0));
            assertEquals(0, endpoint.output.amount(1));
            assertEquals(List.of(1), endpoint.output.debitGroups);
            assertEquals(List.of(7L), endpoint.output.debits);
            assertFalse(endpoint.output.cancelled);
            assertFalse(endpoint.custody.blocked());
            assertEquals(1, endpoint.session.attemptedCount());
            verify(storage, times(1)).insert(eq(healthyKey), eq(7L), any(), any());
            verify(storage, times(1)).insert(any(), anyLong(), any(), any());
        }
    }

    @Test
    void oneOwnerWithSeveralEqualKeyGroupsKeepsPerGroupDebitIdentity() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 3, 7);
            fixture.enqueue(endpoint);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(0, endpoint.output.amount(0));
            assertEquals(0, endpoint.output.amount(1));
            assertEquals(List.of(0, 1), endpoint.output.debitGroups);
            assertEquals(List.of(3L, 7L), endpoint.output.debits);
            assertFalse(endpoint.custody.blocked());
            assertEquals(1, endpoint.session.attemptedCount());
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void longOverflowSplitsOnlyAtLongMaxAndPreservesBothSources() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var first = fixture.endpoint(storage, Long.MAX_VALUE - 2);
            var second = fixture.endpoint(storage, 7);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            var calls = inOrder(storage);
            calls.verify(storage).insert(any(), eq(Long.MAX_VALUE), any(), any());
            calls.verify(storage).insert(any(), eq(5L), any(), any());
            assertEquals(0, first.output.amount(0));
            assertEquals(0, second.output.amount(0));
            assertEquals(List.of(2L, 5L), second.output.debits);
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
        }
    }

    @Test
    void overflowPartRechecksSourceAfterTheFirstExternalCallbackChangesItsHolder() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            var first = fixture.endpoint(storage, Long.MAX_VALUE - 2);
            var second = fixture.endpoint(storage, 7);
            when(storage.insert(any(), anyLong(), any(), any())).thenAnswer(call -> {
                second.holder.setAeItemOutputEnabled(false);
                return call.<Long>getArgument(1);
            });
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(0, first.output.amount(0));
            assertEquals(5, second.output.amount(0));
            assertEquals(List.of(Long.MAX_VALUE - 2), first.output.debits);
            assertEquals(List.of(2L), second.output.debits);
            assertTrue(second.output.cancelled);
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            verify(storage, times(1)).insert(any(), eq(Long.MAX_VALUE), any(), any());
        }
    }

    @Test
    void firstOverflowPartPartialReceiptStopsBeforeTheSecondExternalRequest() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            when(storage.insert(any(), eq(Long.MAX_VALUE), any(), any())).thenReturn(Long.MAX_VALUE - 3);
            var first = fixture.endpoint(storage, Long.MAX_VALUE - 2);
            var second = fixture.endpoint(storage, 7);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(1, first.output.amount(0));
            assertEquals(7, second.output.amount(0));
            assertEquals(List.of(Long.MAX_VALUE - 3), first.output.debits);
            assertTrue(second.output.debits.isEmpty());
            verify(storage, times(1)).insert(any(), anyLong(), any(), any());
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
        }
    }

    @Test
    void registrationCallbackCanSaveAndCancelBeforeExternalExecution() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            CompoundTag[] saved = {null};
            first.changed = () -> {
                if (saved[0] != null) return;
                saved[0] = first.custody.save(registries());
                Ae2DeferredItemOutputs.cancel(fixture.server);
            };
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertNotNull(saved[0]);
            assertEquals(4, saved[0].getLong("source_debit_pending"));
            assertEquals(4, saved[0].getLong("amount"));
            assertFalse(saved[0].getBoolean("receipt_known"));
            assertEquals(1, ItemStack.parse(registries(), saved[0].getCompound("item")).orElseThrow().getCount());
            assertEquals(4, first.output.amount(0));
            assertEquals(6, second.output.amount(0));
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            assertTrue(first.output.cancelled);
            assertTrue(second.output.cancelled);
            verifyNoInteractions(storage);
        }
    }

    @Test
    void registrationCallbackFailurePreservesFailedSourceAndDeliversHealthyParticipant() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            boolean[] failed = {false};
            second.changed = () -> {
                if (failed[0]) return;
                failed[0] = true;
                throw new IllegalStateException("registration listener failed");
            };
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertTrue(failed[0]);
            assertEquals(0, first.output.amount(0));
            assertEquals(6, second.output.amount(0));
            assertEquals(List.of(4L), first.output.debits);
            assertTrue(second.output.debits.isEmpty());
            assertTrue(second.output.cancelled);
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            verify(storage, times(1)).insert(any(), eq(4L), any(), any());
        }
    }

    @Test
    void receiverCancellationCannotRevokeAlreadyAcceptedSourceDebits() {
        try (var fixture = new Fixture()) {
            var storage = mock(MEStorage.class);
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            when(storage.insert(any(), anyLong(), any(), any())).thenAnswer(call -> {
                Ae2DeferredItemOutputs.cancel(fixture.server);
                return call.<Long>getArgument(1);
            });
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertTrue(first.output.cancelled);
            assertTrue(second.output.cancelled);
            assertEquals(0, first.output.amount(0));
            assertEquals(0, second.output.amount(0));
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void acceptedReceiptListenerFailureStillUsesTheRecordedSourceDebit() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 10);
            int[] events = {0};
            endpoint.changed = () -> {
                events[0]++;
                if (events[0] == 2) throw new IllegalStateException("receipt listener failed");
            };
            fixture.enqueue(endpoint);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(0, endpoint.output.amount(0));
            assertEquals(List.of(10L), endpoint.output.debits);
            assertFalse(endpoint.custody.blocked());
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void failedSourceDebitRetainsTheExactOutstandingDebitAndBlocksReplay() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 10);
            endpoint.output.beforeDebit = amount -> {
                throw new IllegalStateException("source mutation refused");
            };
            fixture.enqueue(endpoint);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(10, endpoint.output.amount(0));
            assertTrue(endpoint.custody.blocked());
            assertTrue(endpoint.custody.sourceDebitUnknown());
            assertEquals(10, endpoint.custody.sourceDebitPending());
            assertTrue(endpoint.custody.recoveredItem().isEmpty());
            var restored = restore(endpoint.custody);
            assertEquals(10, restored.sourceDebitPending());
            assertTrue(restored.sourceDebitUnknown());
            endpoint.output.requeue();
            assertTrue(Ae2DeferredItemOutputs.enqueue(endpoint.output));
            Ae2DeferredItemOutputs.flush(fixture.server);
            assertEquals(10, endpoint.output.amount(0));
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void sourceDebitFailureAfterMutationNeverAssumesTheMutationDidNotHappen() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 10);
            endpoint.output.afterDebit = amount -> {
                throw new IllegalStateException("source listener failed after mutation");
            };
            fixture.enqueue(endpoint);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(0, endpoint.output.amount(0));
            assertTrue(endpoint.custody.blocked());
            assertTrue(endpoint.custody.sourceDebitUnknown());
            assertEquals(10, endpoint.custody.sourceDebitPending());
            assertEquals(10, endpoint.custody.amount());
            assertTrue(endpoint.custody.recoveredItem().isEmpty());
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    @Test
    void offThenOnInvalidatesTheAlreadyQueuedTicketEvenWhenEnabledAtFlush() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 10);
            fixture.enqueue(endpoint);
            long originalRevision = endpoint.holder.getOutputRevision();
            endpoint.holder.setAeItemOutputEnabled(false);
            endpoint.holder.setAeItemOutputEnabled(true);
            assertNotEquals(originalRevision, endpoint.holder.getOutputRevision());

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(10, endpoint.output.amount(0));
            assertTrue(endpoint.output.cancelled);
            assertFalse(endpoint.custody.blocked());
            verifyNoInteractions(storage);
        }
    }

    @Test
    void nodeStateChangeCancelsTheOldTicketBeforeClaimingIt() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 10);
            fixture.enqueue(endpoint);
            endpoint.holder.onNodeStateChanged();

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(10, endpoint.output.amount(0));
            assertTrue(endpoint.output.cancelled);
            assertFalse(endpoint.output.claimed);
            verifyNoInteractions(storage);
        }
    }

    @Test
    void gridChangeInsideRegistrationPreservesChangedSourceAndDeliversHealthyParticipant() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var replacement = storageAcceptingAll();
            var first = fixture.endpoint(storage, 4);
            var second = fixture.endpoint(storage, 6);
            boolean[] changed = {false};
            first.changed = () -> {
                if (changed[0]) return;
                changed[0] = true;
                first.storage = replacement;
                first.holder.onGridChanged();
            };
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertTrue(changed[0]);
            assertEquals(4, first.output.amount(0));
            assertEquals(0, second.output.amount(0));
            assertTrue(first.output.cancelled);
            assertTrue(first.output.debits.isEmpty());
            assertEquals(List.of(6L), second.output.debits);
            assertFalse(first.custody.blocked());
            assertFalse(second.custody.blocked());
            verify(storage, times(1)).insert(any(), eq(6L), any(), any());
            verifyNoInteractions(replacement);
        }
    }

    @Test
    void offlineNodeAtExecutionDoesNotUseItsCachedStorage() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var endpoint = fixture.endpoint(storage, 10);
            when(endpoint.node.isPowered()).thenReturn(false);
            endpoint.holder.onNodeStateChanged();
            fixture.enqueue(endpoint);

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(10, endpoint.output.amount(0));
            assertFalse(endpoint.custody.blocked());
            verifyNoInteractions(storage);
        }
    }

    @Test
    void staleServerTickAndRemovedOwnerKeepTheirOriginalOutputs() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var first = fixture.endpoint(storage, 4);
            fixture.enqueue(first);
            fixture.serverTick++;

            Ae2DeferredItemOutputs.flush(fixture.server);

            assertEquals(4, first.output.amount(0));
            assertTrue(first.output.cancelled);
            var second = fixture.endpoint(storage, 6);
            when(second.owner.isRemoved()).thenReturn(true);
            fixture.enqueue(second);
            Ae2DeferredItemOutputs.flush(fixture.server);
            assertEquals(6, second.output.amount(0));
            assertTrue(second.output.cancelled);
            verifyNoInteractions(storage);
        }
    }

    @Test
    void sharedCostIsMeasuredOnceAndEachContributingSessionGetsOneBudgetCharge() {
        try (var fixture = new Fixture()) {
            var storage = storageAcceptingAll();
            var firstTracker = spy(new Ae2InsertCostTracker());
            var secondTracker = spy(new Ae2InsertCostTracker());
            var first = fixture.endpoint(storage, firstTracker, 4);
            var second = fixture.endpoint(storage, secondTracker, 6);
            fixture.enqueue(first, second);

            Ae2DeferredItemOutputs.flush(fixture.server);

            long cost = first.session.batchCostNanos();
            assertTrue(cost >= 0);
            assertEquals(cost, firstTracker.tileSpentThisTickNanos(fixture.gameTick));
            assertEquals(cost, secondTracker.tileSpentThisTickNanos(fixture.gameTick));
            verify(firstTracker, never()).recordSharedForNetwork(any(), anyLong(), anyLong());
            verify(secondTracker, times(1)).recordSharedForNetwork(eq(storage), eq(fixture.gameTick), eq(cost));
            assertEquals(1, first.session.attemptedCount());
            assertEquals(1, second.session.attemptedCount());
            verify(storage, times(1)).insert(any(), eq(10L), any(), any());
        }
    }

    private static MEStorage storageAcceptingAll() {
        var storage = mock(MEStorage.class);
        when(storage.insert(any(), anyLong(), eq(Actionable.MODULATE), any()))
                .thenAnswer(call -> call.<Long>getArgument(1));
        return storage;
    }

    private static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static OutputCustody restore(OutputCustody original) {
        var saved = original.save(registries());
        var restored = new OutputCustody(() -> {});
        restored.load(registries(), saved);
        assertEquals(saved, restored.save(registries()));
        return restored;
    }

    private static final class Source implements DeferredItemOutput {
        private final TileEntityMekanism owner;
        private final ItemStack[] templates;
        private final long[] amounts;
        private final List<Long> debits = new ArrayList<>();
        private final List<Integer> debitGroups = new ArrayList<>();
        private long epoch = 1;
        private boolean claimed;
        private boolean cancelled;
        private boolean finished;
        private LongConsumer beforeDebit = amount -> {};
        private LongConsumer afterDebit = amount -> {};

        private Source(TileEntityMekanism owner, long[] amounts) {
            this.owner = owner;
            this.amounts = amounts.clone();
            this.templates = new ItemStack[amounts.length];
            for (int group = 0; group < amounts.length; group++) {
                this.templates[group] = new ItemStack(Items.HONEYCOMB);
            }
        }

        @Override
        public TileEntityMekanism owner() {
            return owner;
        }

        @Override
        public long epoch() {
            return epoch;
        }

        @Override
        public boolean available(long expected) {
            return expected == epoch && !cancelled && !finished;
        }

        @Override
        public boolean claim(long expected) {
            if (!available(expected) || claimed) return false;
            claimed = true;
            return true;
        }

        @Override
        public int groupCount() {
            return amounts.length;
        }

        @Override
        public ItemStack template(int group) {
            return templates[group];
        }

        @Override
        public long amount(int group) {
            return amounts[group];
        }

        @Override
        public void debit(int group, long amount) {
            if (!claimed || amount < 0 || amount > amounts[group]) {
                throw new IllegalStateException("Invalid test source debit");
            }
            beforeDebit.accept(amount);
            amounts[group] -= amount;
            debitGroups.add(group);
            debits.add(amount);
            afterDebit.accept(amount);
        }

        @Override
        public void finish(long expected) {
            if (expected != epoch) return;
            claimed = false;
            finished = true;
        }

        @Override
        public void cancel(long expected) {
            if (expected == epoch) cancelled = true;
        }

        private void requeue() {
            epoch++;
            claimed = false;
            cancelled = false;
            finished = false;
        }
    }

    private static final class Endpoint {
        private final TileEntityMekanism owner;
        private final IAe2OutputHostBase host;
        private final Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
        private final IGridNode node = mock(IGridNode.class);
        private final Source output;
        private Runnable changed = () -> {};
        private final OutputCustody custody = new OutputCustody(() -> changed.run());
        private final Ae2DirectItemPushSession session;
        private MEStorage storage;

        private Endpoint(Fixture fixture, MEStorage storage, Ae2InsertCostTracker tracker, long... amounts) {
            this.storage = storage;
            owner = mock(TileEntityMekanism.class, withSettings().extraInterfaces(IAe2OutputHostBase.class));
            host = (IAe2OutputHostBase) owner;
            output = new Source(owner, amounts);
            var component = mock(GenesisTileComponentEjector.class);
            when(component.outputCustody()).thenReturn(custody);
            when(owner.getComponents()).thenReturn(List.of(component));
            var position = new BlockPos(fixture.endpoints.size(), 0, 0);
            when(owner.getLevel()).thenReturn(fixture.level);
            when(owner.getBlockPos()).thenReturn(position);
            when(host.productivebeesgenesis$getAe2Level()).thenReturn(fixture.level);
            when(host.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
            when(host.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
            when(host.productivebeesgenesis$isOutputPushEnabled()).thenReturn(true);
            when(host.productivebeesgenesis$isAeItemOutputEnabled()).thenReturn(true);
            var managed = mock(IManagedGridNode.class);
            var grid = mock(IGrid.class);
            var service = mock(IStorageService.class);
            when(host.productivebeesgenesis$getAe2GridNode()).thenReturn(managed);
            when(managed.getNode()).thenReturn(node);
            when(managed.getGrid()).thenReturn(grid);
            when(node.isPowered()).thenReturn(true);
            when(node.hasGridBooted()).thenReturn(true);
            when(node.meetsChannelRequirements()).thenReturn(true);
            when(grid.getService(IStorageService.class)).thenReturn(service);
            when(service.getInventory()).thenAnswer(call -> this.storage);
            holder.setAe2GridNode(managed);
            holder.setCentrifugeDirectAeOutputEnabled(true);
            holder.setAeItemOutputEnabled(true);
            session = spy(new Ae2DirectItemPushSession(holder, storage, null, fixture.gameTick, tracker));
            session.custody = custody;
            if (tracker == null) doReturn(0L).when(session).batchCostNanos();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final Map<IAe2OutputHostBase, Endpoint> endpoints = new IdentityHashMap<>();
        private final MockedStatic<Ae2OutputPusher> pusher = mockStatic(Ae2OutputPusher.class);
        private final MockedStatic<Ae2IntegrationLoader> integration = mockStatic(Ae2IntegrationLoader.class);
        private int serverTick = nextFixtureTick++;
        private final long gameTick = serverTick;

        private Fixture() {
            Ae2DeferredItemOutputs.clearAll();
            integration.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(true);
            when(server.isSameThread()).thenReturn(true);
            when(server.getTickCount()).thenAnswer(call -> serverTick);
            when(level.getServer()).thenReturn(server);
            when(level.getGameTime()).thenAnswer(call -> gameTick);
            when(level.hasChunkAt(any())).thenReturn(true);
            when(level.getBlockEntity(any())).thenAnswer(call -> endpoints.values().stream()
                    .filter(endpoint -> endpoint.owner.getBlockPos().equals(call.getArgument(0)))
                    .map(endpoint -> endpoint.owner).findFirst().orElse(null));
            pusher.when(() -> Ae2OutputPusher.prepareDirectItemPush(any())).thenAnswer(call -> {
                Endpoint endpoint = endpoints.get(call.getArgument(0));
                return endpoint == null ? null : endpoint.session;
            });
        }

        private Endpoint endpoint(MEStorage storage, long... amounts) {
            return endpoint(storage, null, amounts);
        }

        private Endpoint endpoint(MEStorage storage, Ae2InsertCostTracker tracker, long... amounts) {
            var endpoint = new Endpoint(this, storage, tracker, amounts);
            endpoints.put(endpoint.host, endpoint);
            return endpoint;
        }

        private void enqueue(Endpoint... outputs) {
            for (Endpoint endpoint : outputs) assertTrue(Ae2DeferredItemOutputs.enqueue(endpoint.output));
        }

        @Override
        public void close() {
            Ae2DeferredItemOutputs.clearAll();
            for (Endpoint endpoint : endpoints.values()) endpoint.holder.clear();
            integration.close();
            pusher.close();
        }
    }
}
