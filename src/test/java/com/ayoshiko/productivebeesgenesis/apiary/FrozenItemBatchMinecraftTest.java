package com.ayoshiko.productivebeesgenesis.apiary;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2DirectItemPushSession;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.mojang.serialization.Codec;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class FrozenItemBatchMinecraftTest {
    private static final ApiaryBatchUpgradeSnapshot AE = new ApiaryBatchUpgradeSnapshot(
            0, false, false, 1, false, false, false, true, false, false);
    private static final ApiaryBatchUpgradeSnapshot AE_NEIGHBORS = new ApiaryBatchUpgradeSnapshot(
            0, false, false, 1, false, false, false, true, true, false);
    private static final ApiaryBatchUpgradeSnapshot AE_CENTRIFUGE = new ApiaryBatchUpgradeSnapshot(
            0, false, false, 1, false, false, false, true, false, true);
    private static final ApiaryBatchUpgradeSnapshot CENTRIFUGE = new ApiaryBatchUpgradeSnapshot(
            0, false, false, 1, false, false, false, false, false, true);

    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    private record Fixture(TileEntityMekApiary tile, BasicInventorySlot output, BeeProduceProcessor processor) {
        ApiaryProductionState production() {
            return processor.production;
        }
    }

    private static Fixture fixture(boolean blockFallback) {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                "productivebeesgenesis:mek_apiary")).defaultBlockState();
        var tile = spy(new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state));
        tile.setCentrifugePriorityEnabled(false);
        var output = BasicInventorySlot.at(tile::onOutputSlotContentsChanged, 0, 0);
        doReturn(List.of(output)).when(tile).getOutputSlots();
        if (blockFallback) {
            output.setStack(new ItemStack(Items.STONE, 64));
            for (int index = 0; index < ApiaryOutputBuffer.MAX_BUFFER_GROUPS; index++) {
                var held = new ItemStack(Items.IRON_INGOT);
                held.set(DataComponents.CUSTOM_NAME, Component.literal("held-" + index));
                assertEquals(1, tile.getOutputBuffer().accept(held));
            }
        }
        return new Fixture(tile, output, new BeeProduceProcessor(mock(ApiaryUpgradeHandler.class), tile));
    }

    private static void freeze(Fixture fixture, ItemStack... items) {
        fixture.production().freeze(new BeeProduceProcessor.Generated(List.of(items), FluidStack.EMPTY, 0));
    }

    private static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static Fixture restore(Fixture original) {
        var production = original.production().save(registries());
        var custody = OutputCustody.of(original.tile()).save(registries());
        var restored = fixture(true);
        restored.production().load(registries(), production);
        OutputCustody.of(restored.tile()).load(registries(), custody);
        assertEquals(production, restored.production().save(registries()));
        assertEquals(custody, OutputCustody.of(restored.tile()).save(registries()));
        return restored;
    }

    @Test
    void nonAdjacentSourcesShareOneRequestAndFallbackStaysInPhysicalOrder() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.EMERALD, 3),
                new ItemStack(Items.DIAMOND, 6));
        List<ItemStack> sources = List.copyOf(fixture.production().items());
        var events = new ArrayList<String>();
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            long amount = call.getArgument(1);
            if (stack.getItem() == Items.DIAMOND) {
                assertSame(sources.getFirst(), stack);
                assertEquals(10, amount);
                events.add("ae-diamond");
                return 2L;
            }
            assertSame(sources.get(1), stack);
            assertEquals(3, amount);
            events.add("ae-emerald");
            return 3L;
        }).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            assertEquals(Items.DIAMOND, stack.getItem());
            events.add("neighbor-" + stack.getCount());
            return stack.getCount();
        }).when(fixture.tile()).pushGeneratedItemToNeighbors(any());
        assertTrue(fixture.processor().flushFrozen(AE_NEIGHBORS));
        assertEquals(List.of("ae-diamond", "neighbor-2", "ae-emerald", "neighbor-6"), events);
        assertTrue(sources.stream().allMatch(ItemStack::isEmpty));
        verify(fixture.tile(), times(2)).pushGeneratedItemToAe(any(), anyLong());
        verify(fixture.tile(), never()).pushGeneratedItemToAe(any());
    }

    @Test
    void exactComponentsRemainSeparateEvenWhenStackComparisonIgnoresThem() {
        var fixture = fixture(false);
        var animationId = animationIdComponent();
        ItemStack first = new ItemStack(Items.DIAMOND, 4);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("same-name"));
        first.set(animationId, 1L);
        ItemStack second = first.copyWithCount(3);
        second.set(animationId, 2L);
        ItemStack differentName = first.copyWithCount(5);
        differentName.set(DataComponents.CUSTOM_NAME, Component.literal("other-name"));
        var firstKey = AEItemKey.of(first);
        var secondKey = AEItemKey.of(second);
        var nameKey = AEItemKey.of(differentName);
        assertNotEquals(firstKey, secondKey);
        assertNotEquals(firstKey, nameKey);
        freeze(fixture, first, second, first.copyWithCount(6), differentName);
        var keys = new ArrayList<AEItemKey>();
        var amounts = new ArrayList<Long>();
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            long amount = call.getArgument(1);
            keys.add(AEItemKey.of(stack));
            amounts.add(amount);
            return amount;
        }).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        try (var comparison = mockStatic(ItemStack.class, CALLS_REAL_METHODS)) {
            comparison.when(() -> ItemStack.isSameItemSameComponents(any(), any())).thenReturn(true);
            assertTrue(fixture.processor().flushFrozen(AE));
            comparison.verify(() -> ItemStack.isSameItemSameComponents(any(), any()), never());
        }
        assertEquals(List.of(firstKey, secondKey, nameKey), keys);
        assertEquals(List.of(10L, 3L, 5L), amounts);
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<Long> animationIdComponent() {
        var id = ResourceLocation.parse("geckolib:stack_animatable_id");
        if (BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(id)) {
            return (DataComponentType<Long>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
        }
        return DataComponentType.<Long>builder().persistent(Codec.LONG).build();
    }

    @Test
    void longPartialReceiptDebitsOriginalSourcesAndTheSuffixSurvivesRestore() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, Integer.MAX_VALUE), new ItemStack(Items.EMERALD, 2),
                new ItemStack(Items.DIAMOND, Integer.MAX_VALUE));
        List<ItemStack> sources = List.copyOf(fixture.production().items());
        long request = 2L * Integer.MAX_VALUE;
        long accepted = (long) Integer.MAX_VALUE + 7;
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            long amount = call.getArgument(1);
            if (stack.getItem() != Items.DIAMOND) return 0L;
            assertEquals(request, amount);
            return accepted;
        }).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        assertFalse(fixture.processor().flushFrozen(AE));
        assertTrue(sources.getFirst().isEmpty());
        assertEquals(2, sources.get(1).getCount());
        assertEquals(Integer.MAX_VALUE - 7, sources.get(2).getCount());
        assertSame(sources.get(1), fixture.production().items().getFirst());
        assertSame(sources.get(2), fixture.production().items().get(1));
        var restored = restore(fixture);
        long[] resumed = {0};
        doAnswer(call -> {
            long amount = call.getArgument(1);
            resumed[0] += amount;
            return amount;
        }).when(restored.tile()).pushGeneratedItemToAe(any(), anyLong());
        assertTrue(restored.processor().flushFrozen(AE));
        assertEquals(request + 2, accepted + resumed[0]);
    }

    @Test
    void zeroReceiptIsAttemptedOncePerIdentityAndRestoredBatchCanRetry() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.EMERALD, 3),
                new ItemStack(Items.DIAMOND, 6));
        doReturn(0L).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        clearInvocations(fixture.tile());
        assertFalse(fixture.processor().flushFrozen(AE));
        verify(fixture.tile(), times(2)).pushGeneratedItemToAe(any(), anyLong());
        verify(fixture.tile(), never()).setChanged();
        assertEquals(List.of(4, 3, 6), fixture.production().items().stream().map(ItemStack::getCount).toList());
        var restored = restore(fixture);
        var amounts = new ArrayList<Long>();
        doAnswer(call -> {
            long amount = call.getArgument(1);
            amounts.add(amount);
            return amount;
        }).when(restored.tile()).pushGeneratedItemToAe(any(), anyLong());
        assertTrue(restored.processor().flushFrozen(AE));
        assertEquals(List.of(10L, 3L), amounts);
    }

    @Test
    void unknownLongReceiptMovesOnlyItsGroupToCustodyAndCannotReplayAfterRestore() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, Integer.MAX_VALUE), new ItemStack(Items.EMERALD, 3),
                new ItemStack(Items.DIAMOND, Integer.MAX_VALUE));
        long requested = 2L * Integer.MAX_VALUE;
        long[] externalReceived = {0};
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            long amount = call.getArgument(1);
            assertEquals(requested, amount);
            return OutputCustody.of(fixture.tile()).handoffItem(stack, amount, "test-frozen-ae", () -> {
                externalReceived[0] += 9;
                throw new IllegalStateException("receipt lost after external insert");
            });
        }).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        assertFalse(fixture.processor().flushFrozen(AE));
        assertEquals(9, externalReceived[0]);
        var custody = OutputCustody.of(fixture.tile());
        assertTrue(custody.blocked());
        assertEquals(requested, custody.amount());
        assertEquals(1, fixture.production().items().size());
        assertEquals(Items.EMERALD, fixture.production().items().getFirst().getItem());
        assertEquals(3, fixture.production().items().getFirst().getCount());
        verify(fixture.tile(), times(1)).pushGeneratedItemToAe(any(), anyLong());
        var restored = restore(fixture);
        assertFalse(restored.processor().flushFrozen(AE));
        verify(restored.tile(), never()).pushGeneratedItemToAe(any(), anyLong());
        assertEquals(requested, OutputCustody.of(restored.tile()).amount());
        assertEquals(3, restored.production().items().getFirst().getCount());
    }

    @Test
    void laterRouteExceptionKeepsAllDebitedSourcesAndReleasesTheBatchForNextFlush() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.EMERALD, 3),
                new ItemStack(Items.DIAMOND, 6));
        List<ItemStack> sources = List.copyOf(fixture.production().items());
        doReturn(5L).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        doThrow(new IllegalStateException("later route unavailable")).when(fixture.tile())
                .shouldHoldForCentrifuge(argThat(stack -> stack.getItem() == Items.EMERALD));
        clearInvocations(fixture.tile());
        var failure = assertThrows(IllegalStateException.class, () -> fixture.processor().flushFrozen(AE));
        assertEquals("later route unavailable", failure.getMessage());
        assertTrue(sources.getFirst().isEmpty());
        assertEquals(5, sources.get(2).getCount());
        assertSame(sources.get(2), fixture.production().items().get(1));
        verify(fixture.tile()).setChanged();
        var snapshot = fixture.production().save(registries());
        assertEquals(5, snapshot.getList("items", 10).getCompound(1).getInt("count"));
        doReturn(false).when(fixture.tile()).shouldHoldForCentrifuge(any());
        doAnswer(call -> call.<Long>getArgument(1)).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        assertTrue(fixture.processor().flushFrozen(AE));
        assertFalse(fixture.production().hasFrozen());
    }

    @Test
    void priorityBatchRetainsLiveHoldChecksAfterPartialReceipt() {
        var fixture = fixture(false);
        fixture.tile().setCentrifugePriorityEnabled(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 6));
        var available = new boolean[]{true};
        doAnswer(call -> available[0]).when(fixture.tile()).shouldHoldForCentrifuge(any());
        doAnswer(call -> {
            ItemStack offered = call.getArgument(0);
            assertEquals(Items.DIAMOND, offered.getItem());
            assertEquals(10L, call.<Long>getArgument(1));
            call.<LongConsumer>getArgument(2).accept(4);
            available[0] = false;
            return 4L;
        }).when(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            assertFalse(available[0]);
            assertEquals(6, stack.getCount());
            return stack.getCount();
        }).when(fixture.tile()).pushGeneratedItemToAe(any());
        assertTrue(fixture.processor().flushFrozen(AE_CENTRIFUGE));
        verify(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        verify(fixture.tile(), never()).directTransferProducedToCentrifuges(anyList());
        verify(fixture.tile(), times(1)).pushGeneratedItemToAe(any());
        verify(fixture.tile(), never()).pushGeneratedItemToAe(any(), anyLong());
    }

    @Test
    void centrifugeBatchPreservesIdentityOrderAndDebitsEachReceiptImmediately() {
        var fixture = fixture(false);
        fixture.tile().setCentrifugePriorityEnabled(true);
        ItemStack named = new ItemStack(Items.DIAMOND, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("separate"));
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 6),
                named, new ItemStack(Items.DIAMOND, 3));
        List<ItemStack> sources = List.copyOf(fixture.production().items());
        List<String> events = new ArrayList<>();
        doAnswer(call -> {
            ItemStack template = call.getArgument(0);
            assertEquals(10L, call.<Long>getArgument(1));
            LongConsumer debit = call.getArgument(2);
            debit.accept(4);
            assertTrue(sources.getFirst().isEmpty());
            assertEquals(6, sources.get(1).getCount());
            assertEquals(Items.DIAMOND, template.getItem());
            assertFalse(fixture.processor().flushFrozen(CENTRIFUGE));
            debit.accept(6);
            events.add("plain-10");
            return 10L;
        }).when(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        doAnswer(call -> {
            List<ItemStack> offered = call.getArgument(0);
            ItemStack stack = offered.getFirst();
            events.add((stack.has(DataComponents.CUSTOM_NAME) ? "named-" : "plain-") + stack.getCount());
            stack.setCount(0);
            return List.of();
        }).when(fixture.tile()).directTransferProducedToCentrifuges(anyList());
        assertTrue(fixture.processor().flushFrozen(CENTRIFUGE));
        assertEquals(List.of("plain-10", "named-2", "plain-3"), events);
        assertTrue(sources.stream().allMatch(ItemStack::isEmpty));
        verify(fixture.tile(), never()).pushGeneratedItemToAe(any());
        verify(fixture.tile(), times(1)).directTransferProducedToCentrifuges(any(), anyLong(), any());
    }

    @Test
    void completedAePrefixDoesNotEraseTheRemainingCentrifugeTemplate() {
        var fixture = fixture(false);
        fixture.tile().setCentrifugePriorityEnabled(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 6));
        boolean[] available = {false};
        doAnswer(call -> available[0]).when(fixture.tile()).shouldHoldForCentrifuge(any());
        doAnswer(call -> {
            available[0] = true;
            return call.<ItemStack>getArgument(0).getCount();
        }).when(fixture.tile()).pushGeneratedItemToAe(any());
        doAnswer(call -> {
            ItemStack template = call.getArgument(0);
            assertEquals(Items.DIAMOND, template.getItem());
            assertEquals(6L, call.<Long>getArgument(1));
            call.<LongConsumer>getArgument(2).accept(6);
            return 6L;
        }).when(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        assertTrue(fixture.processor().flushFrozen(AE_CENTRIFUGE));
        verify(fixture.tile()).pushGeneratedItemToAe(any());
        verify(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
    }

    @Test
    void failedLaterCentrifugeRouteKeepsDebitedPrefixAndRestoresLongSuffix() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, Integer.MAX_VALUE),
                new ItemStack(Items.DIAMOND, Integer.MAX_VALUE));
        long accepted = (long) Integer.MAX_VALUE + 7;
        doAnswer(call -> {
            assertEquals(2L * Integer.MAX_VALUE, call.<Long>getArgument(1));
            call.<LongConsumer>getArgument(2).accept(Integer.MAX_VALUE);
            call.<LongConsumer>getArgument(2).accept(7);
            throw new IllegalStateException("next target unavailable");
        }).when(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        assertEquals("next target unavailable", assertThrows(IllegalStateException.class,
                () -> fixture.processor().flushFrozen(CENTRIFUGE)).getMessage());
        assertEquals(1, fixture.production().items().size());
        assertEquals(Integer.MAX_VALUE - 7, fixture.production().items().getFirst().getCount());
        var restored = restore(fixture);
        long[] resumed = {0};
        doAnswer(call -> {
            ItemStack stack = call.<List<ItemStack>>getArgument(0).getFirst();
            resumed[0] += stack.getCount();
            stack.setCount(0);
            return List.of();
        }).when(restored.tile()).directTransferProducedToCentrifuges(anyList());
        assertTrue(restored.processor().flushFrozen(CENTRIFUGE));
        assertEquals(2L * Integer.MAX_VALUE, accepted + resumed[0]);
    }

    @Test
    void unknownCentrifugeChunkHasOneCustodyOwnerAndRetainsTheUnattemptedSuffix() {
        var fixture = fixture(true);
        freeze(fixture, new ItemStack(Items.DIAMOND, Integer.MAX_VALUE),
                new ItemStack(Items.DIAMOND, Integer.MAX_VALUE));
        long[] received = {0};
        doAnswer(call -> {
            ItemStack template = call.getArgument(0);
            long handed = OutputCustody.of(fixture.tile()).handoffItem(
                    template, Integer.MAX_VALUE, "test-centrifuge", () -> {
                        received[0] += 9;
                        throw new IllegalStateException("receipt lost");
                    });
            call.<LongConsumer>getArgument(2).accept(handed);
            return handed;
        }).when(fixture.tile()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        assertFalse(fixture.processor().flushFrozen(CENTRIFUGE));
        assertEquals(9, received[0]);
        assertEquals(Integer.MAX_VALUE, OutputCustody.of(fixture.tile()).amount());
        assertEquals(1, fixture.production().items().size());
        assertEquals(Integer.MAX_VALUE, fixture.production().items().getFirst().getCount());
        var restored = restore(fixture);
        assertFalse(restored.processor().flushFrozen(CENTRIFUGE));
        verify(restored.tile(), never()).directTransferProducedToCentrifuges(anyList());
        verify(restored.tile(), never()).directTransferProducedToCentrifuges(any(), anyLong(), any());
        assertEquals(Integer.MAX_VALUE, OutputCustody.of(restored.tile()).amount());
    }

    @Test
    void receiverReentryCannotClearOrReplayTheActiveBatch() {
        var fixture = fixture(false);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 6));
        doAnswer(call -> {
            assertFalse(fixture.processor().flushFrozen(AE));
            return call.<Long>getArgument(1);
        }).when(fixture.tile()).pushGeneratedItemToAe(any(), anyLong());
        assertTrue(fixture.processor().flushFrozen(AE));
        verify(fixture.tile(), times(1)).pushGeneratedItemToAe(any(), anyLong());
        freeze(fixture, new ItemStack(Items.EMERALD, 2), new ItemStack(Items.EMERALD, 3));
        assertTrue(fixture.processor().flushFrozen(AE));
        verify(fixture.tile(), times(2)).pushGeneratedItemToAe(any(), anyLong());
    }

    @Test
    void oneSourceUsesTheExistingDirectPath() {
        var fixture = fixture(false);
        freeze(fixture, new ItemStack(Items.DIAMOND, 4));
        doReturn(4).when(fixture.tile()).pushGeneratedItemToAe(any());
        assertTrue(fixture.processor().flushFrozen(AE));
        verify(fixture.tile()).pushGeneratedItemToAe(any());
        verify(fixture.tile(), never()).pushGeneratedItemToAe(any(), anyLong());
    }

    @Test
    void longAndStackAdapterCallsShareTheMachineQuotaAndBackoff() {
        var fixture = fixture(false);
        var level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);
        fixture.tile().setLevel(level);
        var adapter = new ApiaryAe2HostAdapter(fixture.tile());
        var session = mock(Ae2DirectItemPushSession.class);
        when(session.push(any(), anyLong())).thenAnswer(call -> call.<Long>getArgument(1));
        var stack = new ItemStack(Items.DIAMOND, 3);
        long amount = 2L * Integer.MAX_VALUE;
        try (var integration = mockStatic(Ae2IntegrationLoader.class);
                var output = mockStatic(Ae2OutputPusher.class)) {
            integration.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(true);
            output.when(() -> Ae2OutputPusher.prepareDirectItemPush(fixture.tile())).thenReturn(session);
            for (int index = 0; index < 31; index++) assertEquals(amount, adapter.pushGeneratedItem(stack, amount));
            assertEquals(3, adapter.pushGeneratedItem(stack));
            assertEquals(0, adapter.pushGeneratedItem(stack, amount));
            assertEquals(3, stack.getCount());
            verify(session, times(32)).push(any(), anyLong());

            when(level.getGameTime()).thenReturn(101L);
            var backoff = adapter.getAe2StateHolder().getPushState().getItemBackoff();
            backoff.recordFailure(System.nanoTime());
            assertEquals(0, adapter.pushGeneratedItem(stack, amount));
            verify(session, times(32)).push(any(), anyLong());
            backoff.reset();
            assertEquals(amount, adapter.pushGeneratedItem(stack, amount));
            verify(session, times(33)).push(any(), anyLong());
        }
    }
}
