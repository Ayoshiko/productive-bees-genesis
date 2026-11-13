package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeIndex;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ApiaryProducedTransferMinecraftTest {
    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void standardSlotCapacityReflectsComponentLimitsWithoutCallingAFullSlotsValidator() {
        var calls = new int[1];
        var slot = BasicInventorySlot.at(stack -> {
            calls[0]++;
            return true;
        }, null, 0, 0);
        var input = mock(IMekCentrifugeTile.class);
        when(input.productivebeesgenesis$getInputSlot(0)).thenReturn(slot);
        var template = named(1);
        slot.setStack(template.copyWithCount(slot.getLimit(template)));
        calls[0] = 0;
        var manager = new CentrifugeInputSlotManager();
        assertFalse(manager.hasInputRoom(input, template, 1));
        assertEquals(0, calls[0]);
        slot.setStack(template.copyWithCount(3));
        calls[0] = 0;
        assertTrue(manager.hasInputRoom(input, template, 1));
        assertTrue(calls[0] > 0);
        var smaller = template.copy();
        smaller.set(DataComponents.MAX_STACK_SIZE, 1);
        calls[0] = 0;
        assertFalse(manager.hasInputRoom(input, smaller, 1));
        assertEquals(0, calls[0]);
    }

    @Test
    void holdStopsAtFirstValidRoomAndReadsChangedCapacityAgain() {
        var first = new Input(64);
        var second = new Input(64);
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            var template = named(1);
            assertTrue(fixture.handler.canAnyTargetAccept(template));
            verify(second.slot, never()).getStack();
            first.stack = named(64);
            second.stack = named(64);
            assertFalse(fixture.handler.canAnyTargetAccept(template));
            second.stack = named(63);
            assertTrue(fixture.handler.canAnyTargetAccept(template));
            verify(first.slot, never()).insertItem(any(), any(), any());
            verify(second.slot, never()).insertItem(any(), any(), any());
        }
    }

    @Test
    void holdRespectsCompleteComponentsOfferedLimitAndSlotValidator() {
        var first = new Input(16);
        first.stack = named(15);
        var second = new Input(64);
        second.stack = new ItemStack(Items.DIAMOND, 1);
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            var template = named(1);
            template.set(DataComponents.MAX_STACK_SIZE, 16);
            first.stack = template.copyWithCount(15);
            doReturn(false).when(first.slot).isItemValid(any());
            assertFalse(fixture.handler.canAnyTargetAccept(template));
            doReturn(true).when(first.slot).isItemValid(any());
            assertTrue(fixture.handler.canAnyTargetAccept(template));
            verify(second.slot, never()).getLimit(any());
        }
    }

    @Test
    void longBatchKeepsStableTemplateAndDebitsEachReceiptBeforeNextSlot() {
        var first = new Input(Integer.MAX_VALUE);
        var second = new Input(Integer.MAX_VALUE);
        var events = new ArrayList<String>();
        first.beforeInsert = () -> events.add("insert-first");
        second.beforeInsert = () -> events.add("insert-second");
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            var template = named(1);
            long requested = (long) Integer.MAX_VALUE + 7;
            long[] remaining = {requested};
            long handed = fixture.handler.transferProduced(template, requested, amount -> {
                events.add("debit-" + amount);
                remaining[0] -= amount;
                template.setCount(0);
            });
            assertEquals(requested, handed);
            assertEquals(0, remaining[0]);
            assertEquals(Integer.MAX_VALUE, first.stack.getCount());
            assertEquals(7, second.stack.getCount());
            assertEquals("identity", second.stack.getHoverName().getString());
            assertEquals(List.of("insert-first", "debit-" + Integer.MAX_VALUE, "insert-second", "debit-7"), events);
            assertFalse(fixture.custody().blocked());
        }
    }

    @Test
    void fullInputsNeverCreateAnInsertionOrCustodyReceipt() {
        var first = new Input(64);
        var second = new Input(64);
        first.stack = named(64);
        second.stack = named(64);
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            var source = named(8);
            var receipts = new ArrayList<Long>();
            assertEquals(0, fixture.handler.transferProduced(source, source.getCount(), receipts::add));
            assertEquals(8, source.getCount());
            assertTrue(receipts.isEmpty());
            verify(first.slot, never()).insertItem(any(), any(), any());
            verify(second.slot, never()).insertItem(any(), any(), any());
            assertFalse(fixture.custody().blocked());
        }
    }

    @Test
    void blockedSlottedOutputRefillsFreedInputOnTheNextGameTick() {
        var input = new Input(64);
        input.stack = named(64);
        try (var fixture = new Fixture(List.of(List.of(input)))) {
            doReturn(true).when(fixture.tile).isDirectEjectEnabled();
            when(input.slot.insertItem(any(), eq(Action.SIMULATE), eq(AutomationType.INTERNAL)))
                    .thenAnswer(call -> {
                        ItemStack offered = call.getArgument(0);
                        int accepted = Math.min(offered.getCount(), input.limit - input.stack.getCount());
                        return offered.copyWithCount(offered.getCount() - accepted);
                    });
            var source = fixture.tile.getOutputSlots().getFirst();
            source.setStack(named(8));
            fixture.handler.markEjectDirty();
            long version = fixture.tile.getOutputBuffer().getOutputVersion();
            for (int tick = 0; tick < 20; tick++) {
                when(fixture.level.getGameTime()).thenReturn(10L + tick);
                assertTrue(fixture.handler.tryDirectEject());
                assertFalse(fixture.handler.tryDirectEject());
            }
            assertEquals(8, source.getCount());
            assertEquals(version, fixture.tile.getOutputBuffer().getOutputVersion());
            input.stack = named(56);
            when(fixture.level.getGameTime()).thenReturn(30L);
            assertTrue(fixture.handler.tryDirectEject());
            assertTrue(source.isEmpty());
            assertEquals(64, input.stack.getCount());
            assertFalse(fixture.custody().blocked());
        }
    }

    @Test
    void fullTargetsDoNotQueryDynamicAdmissionAfterRecipeMatchingIsCached() {
        var first = new Input(64);
        var second = new Input(64);
        first.stack = named(64);
        second.stack = named(64);
        try (var fixture = new Fixture(List.of(List.of(first), List.of(second)))) {
            var source = named(9);
            assertTrue(fixture.handler.canAnyTargetProcess(source));
            fixture.runtime.clearInvocations();
            assertFalse(fixture.handler.canAnyTargetAccept(source));
            assertEquals(0, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            fixture.runtime.verify(() -> ResourceRuntime.centrifugeAdmission(eq(fixture.server), any()), never());
            assertEquals(9, source.getCount());
            verify(first.slot, never()).insertItem(any(), any(), any());
            verify(second.slot, never()).insertItem(any(), any(), any());
        }
    }

    @Test
    void unusedTargetsDoNotQueryAdmissionDuringHoldOrDirectTransfer() {
        var first = new Input(64);
        var second = new Input(64);
        try (var fixture = new Fixture(List.of(List.of(first), List.of(second)))) {
            var source = named(9);
            assertTrue(fixture.handler.canAnyTargetProcess(source));
            fixture.runtime.clearInvocations();
            assertTrue(fixture.handler.canAnyTargetAccept(source));
            fixture.runtime.verify(() -> ResourceRuntime.centrifugeAdmission(eq(fixture.server), any()), times(1));
            fixture.runtime.clearInvocations();
            assertEquals(9, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            fixture.runtime.verify(() -> ResourceRuntime.centrifugeAdmission(eq(fixture.server), any()), times(1));
            verify(second.slot, never()).getStack();
            verify(second.slot, never()).insertItem(any(), any(), any());
        }
    }

    @Test
    void firstReceiptCanRevokeAdmissionBeforeTheNextSlotOrTarget() {
        for (boolean separateTarget : new boolean[]{false, true}) {
            var first = new Input(3);
            var second = new Input(64);
            var targets = separateTarget ? List.of(List.of(first), List.of(second)) : List.of(List.of(first, second));
            try (var fixture = new Fixture(targets)) {
                boolean[] available = {true};
                fixture.runtime.when(() -> ResourceRuntime.centrifugeAdmission(eq(fixture.server), any()))
                        .thenAnswer(call -> available[0] ? ResourceRuntime.CentrifugeAdmission.UNMANAGED
                                : ResourceRuntime.CentrifugeAdmission.UNAVAILABLE);
                first.afterInsert = () -> available[0] = false;
                var source = named(9);
                assertEquals(3, fixture.handler.transferProduced(source, source.getCount(),
                        amount -> source.shrink((int) amount)));
                assertEquals(6, source.getCount());
                assertEquals(3, first.stack.getCount());
                assertTrue(second.stack.isEmpty());
                assertFalse(fixture.custody().blocked());
                assertFalse(fixture.handler.canAnyTargetAccept(source));
                verify(second.slot, never()).insertItem(any(), any(), any());
            }
        }
    }

    @Test
    void unknownSuffixIsDebitedOnceAndRemainingSourceIsNeverReplayed() {
        var first = new Input(3);
        var second = new Input(4);
        var third = new Input(64);
        second.afterInsert = () -> {
            throw new IllegalStateException("after target acceptance");
        };
        try (var fixture = new Fixture(List.of(List.of(first, second, third)))) {
            var source = named(10);
            assertEquals(7, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            assertEquals(3, source.getCount());
            assertEquals(3, first.stack.getCount());
            assertEquals(4, second.stack.getCount());
            assertTrue(third.stack.isEmpty());
            assertTrue(fixture.custody().blocked());
            assertEquals(4, fixture.custody().amount());
            assertEquals("centrifuge-input", fixture.custody().destination());
            assertEquals(0, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            verify(second.slot, times(1)).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
            verify(third.slot, never()).insertItem(any(), any(), any());
        }
    }

    @Test
    void laterReadFailureKeepsTheAlreadyDebitedPrefix() {
        var first = new Input(3);
        var second = new Input(64);
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            doThrow(new IllegalStateException("later input read")).when(second.slot).getStack();
            var source = named(10);
            var error = assertThrows(IllegalStateException.class, () ->
                    fixture.handler.transferProduced(source, source.getCount(),
                            amount -> source.shrink((int) amount)));
            assertEquals("later input read", error.getMessage());
            assertEquals(7, source.getCount());
            assertEquals(3, first.stack.getCount());
            assertFalse(fixture.custody().blocked());
        }
    }

    @Test
    void targetCallbackChangesLaterSlotsWithoutLeavingAStaleCapacityDecision() {
        var first = new Input(3);
        var second = new Input(64);
        var third = new Input(4);
        first.afterInsert = () -> second.stack = named(64);
        try (var fixture = new Fixture(List.of(List.of(first, second, third)))) {
            var source = named(9);
            assertEquals(7, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            assertEquals(2, source.getCount());
            assertEquals(64, second.stack.getCount());
            verify(second.slot, never()).insertItem(any(), any(), any());
            assertEquals(4, third.stack.getCount());
        }
    }

    @Test
    void actualInputReceiptControlsCustomRejection() {
        var input = new Input(64);
        try (var fixture = new Fixture(List.of(List.of(input)))) {
            doAnswer(call -> call.<ItemStack>getArgument(0)).when(input.slot)
                    .insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
            var source = named(9);
            assertEquals(0, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            assertEquals(9, source.getCount());
            assertTrue(input.stack.isEmpty());
            assertFalse(fixture.custody().blocked());
            verify(input.slot).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
        }
    }

    @Test
    void receiverMayMutateItsRequestWithoutChangingStableReceiptIdentity() {
        var first = new Input(3);
        var second = new Input(4);
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            doAnswer(call -> {
                ItemStack offered = call.getArgument(0);
                assertEquals("identity", offered.getHoverName().getString());
                first.stack = offered.copyWithCount(2);
                offered.set(DataComponents.CUSTOM_NAME, Component.literal("receiver-only"));
                offered.setCount(0);
                return named(1);
            }).when(first.slot).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
            var source = named(6);
            assertEquals(6, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            assertTrue(source.isEmpty());
            assertEquals(2, first.stack.getCount());
            assertEquals(4, second.stack.getCount());
            assertEquals("identity", second.stack.getHoverName().getString());
            assertFalse(fixture.custody().blocked());
        }
    }

    @Test
    void changedIdentityReceiptIsIsolatedAfterReceiverMutation() {
        var first = new Input(4);
        var second = new Input(64);
        try (var fixture = new Fixture(List.of(List.of(first, second)))) {
            doAnswer(call -> {
                ItemStack offered = call.getArgument(0);
                offered.set(DataComponents.CUSTOM_NAME, Component.literal("wrong-identity"));
                offered.setCount(1);
                return offered;
            }).when(first.slot).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
            var source = named(9);
            assertEquals(4, fixture.handler.transferProduced(source, source.getCount(),
                    amount -> source.shrink((int) amount)));
            assertEquals(5, source.getCount());
            assertTrue(fixture.custody().blocked());
            assertEquals(4, fixture.custody().amount());
            verify(second.slot, never()).insertItem(any(), any(), any());
        }
    }

    @Test
    void mixedCombCacheKeepsFullComponentsAndRefreshesForRecipeChanges() {
        var input = new Input(64);
        var originalVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
        try (var fixture = new Fixture(List.of(List.of(input)))) {
            var neighbor = fixture.neighbors.values().iterator().next();
            var centrifuge = (IMekCentrifugeTile) neighbor;
            when(centrifuge.productivebeesgenesis$isValidInput(any())).thenAnswer(call -> {
                ItemStack offered = call.getArgument(0);
                return "accepted".equals(offered.getHoverName().getString());
            });
            var accepted = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
            accepted.set(PbDataComponents.beeType(),
                    ResourceLocation.parse("productivebees:iron"));
            accepted.set(DataComponents.CUSTOM_NAME, Component.literal("accepted"));
            var rejected = accepted.copy();
            rejected.set(DataComponents.CUSTOM_NAME, Component.literal("rejected"));
            assertTrue(fixture.handler.canAnyTargetProcess(accepted));
            assertFalse(fixture.handler.canAnyTargetProcess(rejected));
            for (int index = 0; index < 62; index++) {
                var variant = accepted.copy();
                variant.set(PbDataComponents.beeType(),
                        ResourceLocation.parse("productivebees:cache_" + index));
                assertTrue(fixture.handler.canAnyTargetProcess(variant));
            }
            assertTrue(fixture.handler.canAnyTargetProcess(accepted));
            assertFalse(fixture.handler.canAnyTargetProcess(rejected));
            assertTrue(fixture.handler.canAnyTargetProcess(accepted.copy()));
            doReturn(false).when(centrifuge).productivebeesgenesis$isValidInput(any());
            ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
            assertFalse(fixture.handler.canAnyTargetProcess(accepted));
        } finally {
            ProductiveBeesGenesis.RECIPE_VERSION.set(originalVersion);
        }
    }

    @Test
    void originalListKeepsOneTargetStartForAllSourcesAndThenRotates() {
        var first = new Input(64);
        var second = new Input(64);
        try (var fixture = new Fixture(List.of(List.of(first), List.of(second)))) {
            var a = named(3);
            var b = named(4);
            fixture.handler.transferProducedStacks(List.of(a, b));
            assertTrue(a.isEmpty());
            assertTrue(b.isEmpty());
            assertEquals(7, first.stack.getCount());
            assertTrue(second.stack.isEmpty());
            var c = named(2);
            fixture.handler.transferProducedStacks(List.of(c));
            assertTrue(c.isEmpty());
            assertEquals(2, second.stack.getCount());
        }
    }

    private static ItemStack named(int count) {
        var stack = new ItemStack(Items.DIAMOND, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("identity"));
        return stack;
    }

    private static final class Input {
        private final IInventorySlot slot = mock(IInventorySlot.class);
        private final int limit;
        private ItemStack stack = ItemStack.EMPTY;
        private Runnable beforeInsert = () -> {};
        private Runnable afterInsert = () -> {};

        private Input(int limit) {
            this.limit = limit;
            when(slot.getStack()).thenAnswer(call -> stack);
            when(slot.getLimit(any())).thenAnswer(call -> this.limit);
            when(slot.isItemValid(any())).thenReturn(true);
            when(slot.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
                beforeInsert.run();
                ItemStack offered = call.getArgument(0);
                if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, offered)) return offered;
                int accepted = Math.min(offered.getCount(), Math.max(0, this.limit - stack.getCount()));
                if (accepted > 0) stack = offered.copyWithCount(stack.getCount() + accepted);
                afterInsert.run();
                return offered.copyWithCount(offered.getCount() - accepted);
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level = mock(ServerLevel.class);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final Map<BlockPos, BlockEntity> neighbors = new HashMap<>();
        private final MockedStatic<ResourceRuntime> runtime = mockStatic(ResourceRuntime.class);
        private final TileEntityMekApiary tile;
        private final ApiaryDirectEjectHandler handler;

        private Fixture(List<List<Input>> targets) {
            var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                    "productivebeesgenesis:mek_apiary")).defaultBlockState();
            tile = spy(new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state));
            doReturn(level).when(tile).getLevel();
            doNothing().when(tile).setChanged();
            when(level.getServer()).thenReturn(server);
            when(level.getGameTime()).thenReturn(10L);
            when(level.getBlockEntity(any(BlockPos.class))).thenAnswer(call -> neighbors.get(call.getArgument(0)));
            var index = ResourceRecipeIndex.build(0, List.of());
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            runtime.when(() -> ResourceRuntime.centrifugeAdmission(eq(server), any()))
                    .thenReturn(ResourceRuntime.CentrifugeAdmission.UNMANAGED);
            var config = tile.getConfig().getConfig(TransmissionType.ITEM);
            for (var side : RelativeSide.values()) {
                config.setDataType(DataType.NONE, side);
            }
            int targetIndex = 0;
            for (var direction : Direction.values()) {
                if (targetIndex >= targets.size()) break;
                var slots = targets.get(targetIndex++);
                BlockPos position = BlockPos.ZERO.relative(direction);
                var neighbor = mock(BlockEntity.class, withSettings().extraInterfaces(IMekCentrifugeTile.class));
                var centrifuge = (IMekCentrifugeTile) neighbor;
                when(neighbor.getLevel()).thenReturn(level);
                when(neighbor.getBlockPos()).thenReturn(position);
                when(centrifuge.productivebeesgenesis$getInputSlotCount()).thenReturn(slots.size());
                when(centrifuge.productivebeesgenesis$getInputSlot(anyInt())).thenAnswer(call ->
                        slots.get(call.<Integer>getArgument(0)).slot);
                when(centrifuge.productivebeesgenesis$isValidInput(any())).thenReturn(true);
                neighbors.put(position, neighbor);
            }
            assertEquals(targets.size(), targetIndex);
            handler = new ApiaryDirectEjectHandler(tile);
        }

        private OutputCustody custody() {
            return OutputCustody.of(tile);
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
