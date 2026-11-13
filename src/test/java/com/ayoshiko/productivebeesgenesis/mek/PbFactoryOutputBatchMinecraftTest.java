package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.LocalBatch.Completion;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PbFactoryOutputBatchMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void myriadFullLocalSlotsPreserveVirtualTickBudgetWhenAeOutputIsEnabled() {
        for (int route = 0; route < 3; route++) {
            boolean direct = route != 2;
            boolean accepts = route == 0;
            var fixture = new Fixture(mock(PbRecipeContext.class), 1);
            var level = mock(ServerLevel.class);
            when(level.registryAccess()).thenReturn(REGISTRIES);
            when(fixture.context.level()).thenReturn(level);
            when(fixture.context.getTicksForBase(anyInt())).thenReturn(2);
            when(fixture.context.productivityModifier()).thenReturn(1);
            when(fixture.context.productivebeesgenesis$outputSlotsFull(0)).thenReturn(true);
            when(fixture.context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(direct);
            when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                    .thenAnswer(call -> accepts ? call.getArgument(1) : 0L);
            when(fixture.context.productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong()))
                    .thenAnswer(call -> accepts ? call.getArgument(1) : 0L);
            var energy = mock(MachineEnergyContainer.class);
            when(fixture.context.energyContainer()).thenReturn(energy);
            when(energy.getEnergy()).thenReturn(1_000L);
            when(energy.extract(anyLong(), any(), any())).thenAnswer(call -> call.getArgument(0));
            fixture.inputs[0].setStack(new ItemStack(Items.HONEYCOMB, 8));
            int[] progress = new int[1];
            var handler = new MyriadCreationsHandler(fixture.context, "myriad-full-output-test",
                    progress, new boolean[1], new int[1], new RecipeHolder[1],
                    mock(PbRecipeFinder.class), fixture.owners);
            var type = ResourceLocation.parse("productivebees:iron");
            var selected = List.of(type);
            var snapshot = mock(MyriadBeeTypeCache.BeeTypeCacheSnapshot.class);
            when(snapshot.selectTemplates(eq(selected), eq(false), any()))
                    .thenReturn(Map.of(type, new ItemStack(Items.IRON_INGOT)));
            var selector = mock(WeightedTypeSelector.class);
            when(selector.getWeightsFor(selected)).thenReturn(new double[]{1});
            try (var events = mockStatic(MyriadCreationsEventHandler.class);
                 var types = mockStatic(MyriadBeeTypeCache.class);
                 var selectors = mockStatic(WeightedTypeSelector.class)) {
                events.when(MyriadCreationsEventHandler::isMyriadCreationsEnabled).thenReturn(true);
                events.when(() -> MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(any())).thenReturn(true);
                events.when(() -> MyriadCreationsEventHandler.selectDistinctBeeTypesCached(anyInt(), eq(level), eq(false)))
                        .thenReturn(selected);
                types.when(MyriadBeeTypeCache::snapshot).thenReturn(snapshot);
                selectors.when(WeightedTypeSelector::getInstance).thenReturn(selector);
                assertTrue(handler.tryProcessMyriadCreations(0, fixture.inputs[0].getStack(), 10, 1, 8));
            }
            int completed = direct ? 4 : 1;
            assertEquals(8 - completed, fixture.inputs[0].getCount());
            assertEquals(0, progress[0]);
            verify(energy).extract(eq(direct ? 80L : 20L), any(), any());
            assertEquals(accepts ? 0 : completed, pending(fixture.owners[0]));
            assertEquals(accepts ? 0 : 100L * completed, fixture.owners[0].getPendingFluidAmount());
            if (!accepts) {
                assertFalse(handler.tryProcessMyriadCreations(0, fixture.inputs[0].getStack(), 10, 1, 8));
                assertEquals(8 - completed, fixture.inputs[0].getCount());
                verify(energy, times(1)).extract(anyLong(), any(), any());
            }
        }
    }

    @Test
    void combinesIdenticalItemsAndFluidsWithinTheSameTick() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        when(fixture.context.productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3, 100);
        fixture.prepare(1, named("same"), 7, 250);
        verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
        assertTrue(fixture.inputs[0].isEmpty());
        assertTrue(fixture.inputs[1].isEmpty());
        fixture.batch.finish();
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
        verify(fixture.context).productivebeesgenesis$pushGeneratedFluidToAe(any(), eq(350L));
        assertFalse(fixture.owners[0].hasPaidPendingOutputs());
        assertFalse(fixture.owners[1].hasPaidPendingOutputs());
        fixture.batch.begin();
        fixture.batch.finish();
        verifyNoMoreInteractionsWithDirectOutputs(fixture.context);
    }

    @Test
    void partialReceiptDebitsOnlyItsSourcesAndDoesNotRetryAeDuringLocalFallback() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong())).thenReturn(5L);
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3, 0);
        fixture.prepare(1, named("same"), 7, 0);
        fixture.batch.finish();
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
        assertEquals(0, pending(fixture.owners[0]));
        assertEquals(5, pending(fixture.owners[1]));
        var saved = fixture.owners[1].saveCommittedPending(REGISTRIES);
        var restored = new PbRecipeCompleter(fixture.context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertEquals(5, pending(restored));
        assertEquals(0, restored.getPendingInputShrink());
    }

    @Test
    void longReceiptDebitsSourcesInOrderWithoutIntSegmentation() {
        long maximum = Integer.MAX_VALUE;
        long total = 3 * maximum + 7;
        long[] amounts = {maximum - 5, maximum, maximum, 12};
        for (long receipt : new long[]{0, 2 * maximum + 3, total}) {
            var fixture = new Fixture(mock(PbRecipeContext.class), amounts.length);
            when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(total)))
                    .thenReturn(receipt);
            fixture.batch.begin();
            for (int lane = 0; lane < amounts.length; lane++) {
                fixture.prepare(lane, named("same"), amounts[lane], 0);
            }
            fixture.batch.finish();
            verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(total));
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt());
            long leftToDebit = receipt;
            long remaining = 0;
            for (int lane = 0; lane < amounts.length; lane++) {
                long debit = Math.min(leftToDebit, amounts[lane]);
                leftToDebit -= debit;
                assertEquals(amounts[lane] - debit, pending(fixture.owners[lane]));
                assertEquals(0, fixture.owners[lane].getPendingInputShrink());
                assertTrue(fixture.inputs[lane].isEmpty());
                remaining += pending(fixture.owners[lane]);
            }
            assertEquals(total, receipt + remaining);
        }
    }

    @Test
    void overflowingCombinedAmountSplitsOnlyAtLongBoundaryAndStopsOnPartialReceipt() {
        var fixture = new Fixture(mock(PbRecipeContext.class), 4);
        long[] amounts = {Long.MAX_VALUE - 3, 3, Long.MAX_VALUE, 7};
        List<Long> requests = new ArrayList<>();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> {
                    long amount = call.getArgument(1);
                    requests.add(amount);
                    return requests.size() == 1 ? amount : 9L;
                });
        fixture.batch.begin();
        for (int lane = 0; lane < amounts.length; lane++) {
            fixture.prepare(lane, named("same"), amounts[lane], 0);
        }
        fixture.batch.finish();
        assertEquals(List.of(Long.MAX_VALUE, Long.MAX_VALUE), requests);
        assertEquals(0, pending(fixture.owners[0]));
        assertEquals(0, pending(fixture.owners[1]));
        assertEquals(Long.MAX_VALUE - 9, pending(fixture.owners[2]));
        assertEquals(7, pending(fixture.owners[3]));
        for (var owner : fixture.owners) {
            assertEquals(0, owner.getPendingInputShrink());
        }
    }

    @Test
    void restoredPreparedPendingUsesRotatingOrderAndRetriesOnlyOnNextPass() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        fixture.freeze(0, named("same"), 3, 0);
        fixture.freeze(1, named("same"), 7, 0);
        for (int lane = 0; lane < 2; lane++) {
            assertTrue(fixture.owners[lane].commitCompletedInput(lane));
        }
        fixture.inputs[0].setStack(new ItemStack(Items.STONE, 8));
        var processor = fixture.restoreProcessor();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L))).thenReturn(5L);
        processor.beginFactoryOutputBatch(1);
        try {
            assertFalse(processor.tryProcessPbRecipe(0));
            processor.updateFactoryInputState(1, true);
            assertFalse(processor.drainCommittedPendingOutputs(1));
            CompoundTag saved = new CompoundTag();
            processor.saveAdditional(saved, REGISTRIES);
            ListTag pending = saved.getList("productivebeesgenesis_pb_committed_pending", 10);
            assertEquals(2, pending.size());
            for (int index = 0; index < pending.size(); index++) {
                CompoundTag entry = pending.getCompound(index);
                var owner = new PbRecipeCompleter(fixture.context);
                owner.loadCommittedPending(entry, REGISTRIES);
                assertEquals(entry.getInt("process") == 0 ? 3L : 2L, pending(owner));
            }
            verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
        } finally {
            processor.finishFactoryOutputBatch();
        }
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(5L))).thenReturn(5L);
        processor.beginFactoryOutputBatch(0);
        processor.finishFactoryOutputBatch();
        CompoundTag saved = new CompoundTag();
        processor.saveAdditional(saved, REGISTRIES);
        assertTrue(saved.getList("productivebeesgenesis_pb_committed_pending", 10).isEmpty());
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(5L));
        verify(fixture.context, times(2)).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
        assertEquals(8, fixture.inputs[0].getCount());
    }

    @Test
    void sharedFactoryEntryPointDrainsRestoredPendingWithEmptyInputSlots() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        fixture.freeze(0, named("same"), 3, 0);
        fixture.freeze(1, named("same"), 7, 0);
        for (int lane = 0; lane < 2; lane++) {
            assertTrue(fixture.owners[lane].commitCompletedInput(lane));
        }
        var processor = fixture.restoreProcessor();
        var energy = mock(MachineEnergyContainer.class);
        when(fixture.context.energyContainer()).thenReturn(energy);
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L))).thenReturn(10L);
        MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(false, 0L, energy, 2,
                List.of(fixture.inputs), processor, fixture.context, false, active -> {}, usage -> {});
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
        CompoundTag saved = new CompoundTag();
        processor.saveAdditional(saved, REGISTRIES);
        assertTrue(saved.getList("productivebeesgenesis_pb_committed_pending", 10).isEmpty());
        assertTrue(fixture.inputs[0].isEmpty());
        assertTrue(fixture.inputs[1].isEmpty());
        verify(energy, never()).extract(anyLong(), any(), any());
    }

    @Test
    void preDrainLeavesUncommittedPaymentAndLegacyOutputOnOriginalRecoveryPaths() {
        var fixture = new Fixture(mock(PbRecipeContext.class), 4);
        for (int lane = 0; lane < 4; lane++) {
            fixture.freeze(lane, named("same"), lane + 1, 0);
        }
        assertTrue(fixture.owners[0].commitCompletedInput(0));
        assertTrue(fixture.owners[3].commitCompletedInput(3));
        var energy = mock(MachineEnergyContainer.class);
        when(fixture.context.energyContainer()).thenReturn(energy);
        assertFalse(fixture.owners[2].payForPlan(PbVirtualTickPlan.create(0, 1, 1, 1, 1, 10, 10), () -> {}));
        CompoundTag legacy = fixture.owners[3].saveCommittedPending(REGISTRIES);
        legacy.remove("prepared");
        fixture.owners[3].loadCommittedPending(legacy, REGISTRIES);
        var processor = fixture.restoreProcessor();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        processor.beginFactoryOutputBatch(0);
        try {
            verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(1L));
            verify(fixture.context, times(1)).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            assertEquals(1, fixture.inputs[1].getCount());
            assertEquals(1, fixture.inputs[2].getCount());
            assertTrue(processor.drainCommittedPendingOutputs(3));
            verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(4L));
            assertFalse(processor.drainCommittedPendingOutputs(2));
            CompoundTag saved = new CompoundTag();
            processor.saveAdditional(saved, REGISTRIES);
            ListTag pending = saved.getList("productivebeesgenesis_pb_committed_pending", 10);
            assertEquals(2, pending.size());
            assertEquals(1, pending.getCompound(0).getInt("process"));
            assertEquals(2, pending.getCompound(1).getInt("process"));
            assertTrue(pending.getCompound(1).hasUUID("payment_request"));
        } finally {
            processor.finishFactoryOutputBatch();
        }
    }

    @Test
    void distinctComponentsKeepSeparateReceipts() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        List<String> names = new ArrayList<>();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong())).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            names.add(stack.getHoverName().getString());
            return call.getArgument(1);
        });
        fixture.batch.begin();
        fixture.prepare(0, named("first"), 3, 0);
        fixture.prepare(1, named("second"), 7, 0);
        fixture.batch.finish();
        assertEquals(List.of("first", "second"), names);
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(3L));
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(7L));
        assertEquals(0, pending(fixture.owners[0]) + pending(fixture.owners[1]));
    }

    @Test
    void unknownCombinedReceiptHasOneCustodyOwnerAndSurvivesRestart() {
        var machine = mock(TileEntityMekCentrifuge.class);
        var ejector = mock(GenesisTileComponentEjector.class);
        var custody = new OutputCustody(() -> {});
        when(machine.getEjector()).thenReturn(ejector);
        when(ejector.outputCustody()).thenReturn(custody);
        var fixture = new Fixture(machine);
        when(machine.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenThrow(new IllegalStateException("receiver accepted an unknown prefix"));
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3_000_000_000L, 0);
        fixture.prepare(1, named("same"), 1_000_000_000L, 0);
        fixture.batch.finish();
        assertTrue(custody.blocked());
        assertEquals(4_000_000_000L, custody.amount());
        assertEquals(0, pending(fixture.owners[0]) + pending(fixture.owners[1]));
        var restored = new OutputCustody(() -> {});
        restored.load(REGISTRIES, custody.save(REGISTRIES));
        restored.resolve(restored.sequence(), 1_000_000_000L);
        assertEquals(3_000_000_000L, restored.amount());
        assertEquals(Integer.MAX_VALUE, restored.recoveredItem().getCount());
        assertEquals("same", restored.recoveredItem().getHoverName().getString());
        fixture.batch.begin();
        fixture.batch.finish();
        verify(machine).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(4_000_000_000L));
    }

    @Test
    void reconciledHandoffDoesNotSkipConversionForTheNextUseOfTheSameRecipe() {
        var machine = mock(TileEntityMekCentrifuge.class);
        var ejector = mock(GenesisTileComponentEjector.class);
        var custody = new OutputCustody(() -> {});
        var level = mock(Level.class);
        when(machine.getEjector()).thenReturn(ejector);
        when(ejector.outputCustody()).thenReturn(custody);
        when(machine.level()).thenReturn(level);
        when(machine.productivebeesgenesis$hasRawOreSmeltingUpgrade()).thenReturn(true);
        var fixture = new Fixture(machine);
        var output = new ChancedOutput(Ingredient.of(Items.RAW_IRON), 1, 1, 1);
        var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
            @Override
            public FluidStack getFluidOutputs() {
                return FluidStack.EMPTY;
            }
        };
        when(machine.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenThrow(new IllegalStateException("unknown receipt"));
        try (var conversion = mockStatic(RawOreSmeltingUpgradeHelper.class)) {
            conversion.when(() -> RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(eq(level), anyMap()))
                    .thenAnswer(call -> {
                        Map<ItemStack, Long> pending = call.getArgument(1);
                        long count = pending.values().stream().mapToLong(Long::longValue).sum();
                        assertTrue(pending.keySet().stream().allMatch(stack -> stack.is(Items.RAW_IRON)));
                        pending.clear();
                        pending.put(new ItemStack(Items.IRON_INGOT), count);
                        return true;
                    });
            fixture.batch.begin();
            for (int lane = 0; lane < 2; lane++) {
                fixture.inputs[lane].setStack(new ItemStack(Items.HONEYCOMB));
                fixture.owners[lane].accumulatePbRecipeOutputsBatch(recipe, lane, 1, 1);
                fixture.owners[lane].freezeCompletedBatch(fixture.inputs[lane].getStack());
                assertTrue(fixture.owners[lane].flushCompletedBatch(lane));
            }
            fixture.batch.finish();
            assertTrue(custody.blocked());
            assertEquals(2, custody.amount());
            for (var owner : fixture.owners) {
                assertFalse(owner.isPreparedOutput());
                assertFalse(owner.hasPaidPendingOutputs());
            }
            custody.resolve(custody.sequence(), 2);
            assertFalse(custody.blocked());
            doAnswer(call -> {
                ItemStack stack = call.getArgument(0);
                assertTrue(stack.is(Items.IRON_INGOT));
                return call.getArgument(1);
            }).when(machine).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            fixture.batch.begin();
            fixture.inputs[0].setStack(new ItemStack(Items.HONEYCOMB));
            fixture.owners[0].accumulatePbRecipeOutputsBatch(recipe, 0, 1, 1);
            fixture.owners[0].freezeCompletedBatch(fixture.inputs[0].getStack());
            assertTrue(fixture.owners[0].flushCompletedBatch(0));
            fixture.batch.finish();
            assertFalse(fixture.owners[0].hasPaidPendingOutputs());
            conversion.verify(() -> RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(eq(level), anyMap()), times(3));
            verify(machine).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(1L));
        }
    }

    @Test
    void resourceAndOrdinaryLanesShareOneReceiptAndConfirmAfterTheHandoff() {
        var fixture = new Fixture(mock(PbRecipeContext.class), 3);
        var first = mock(Completion.class);
        var second = mock(Completion.class);
        List<Long> receipts = new ArrayList<>();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> {
                    verifyNoInteractions(first, second);
                    receipts.add(call.getArgument(1));
                    return 5L;
                });
        fixture.batch.begin();
        fixture.prepareResource(0, 3, first);
        fixture.prepareResource(1, 7, second);
        fixture.prepare(2, new ItemStack(Items.IRON_INGOT), 11, 0);
        verifyNoInteractions(first, second);
        fixture.batch.finish();
        assertEquals(List.of(21L), receipts);
        assertEquals(0, pending(fixture.owners[0]));
        assertEquals(5, pending(fixture.owners[1]));
        assertEquals(11, pending(fixture.owners[2]));
        verify(first).complete();
        verify(second).complete();
        verify(first, never()).uncertain(anyString());
        verify(second, never()).uncertain(anyString());
        for (var input : fixture.inputs) assertTrue(input.isEmpty());
    }

    @Test
    void unknownGroupSuspendsOnlyTheResourceLanesThatContributedToIt() {
        var machine = mock(TileEntityMekCentrifuge.class);
        var ejector = mock(GenesisTileComponentEjector.class);
        var custody = new OutputCustody(() -> {});
        when(machine.getEjector()).thenReturn(ejector);
        when(ejector.outputCustody()).thenReturn(custody);
        var fixture = new Fixture(machine, 3);
        var affected = mock(Completion.class);
        var untouched = mock(Completion.class);
        when(machine.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenThrow(new IllegalStateException("mixed network receipt unknown"));
        fixture.batch.begin();
        fixture.prepareResource(0, 3, affected);
        fixture.prepare(1, new ItemStack(Items.IRON_INGOT), 7, 0);
        fixture.prepareResource(2, Items.GOLD_INGOT, 11, untouched);
        fixture.batch.finish();
        assertEquals(10, custody.amount());
        assertEquals(0, pending(fixture.owners[0]));
        assertEquals(0, pending(fixture.owners[1]));
        assertEquals(11, pending(fixture.owners[2]));
        verify(affected).uncertain(anyString());
        verify(affected, never()).complete();
        verify(untouched).complete();
        verify(untouched, never()).uncertain(anyString());
        verify(machine, times(1)).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
    }

    @Test
    void unloadDuringHandoffPreservesTheResourceRequestAndDebitsTheKnownPbReceipt() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        var completion = mock(Completion.class);
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> {
                    fixture.batch.abandon();
                    return 2L;
                });
        fixture.batch.begin();
        fixture.prepareResource(0, 3, completion);
        fixture.prepare(1, new ItemStack(Items.IRON_INGOT), 7, 0);
        fixture.batch.finish();
        verify(completion).uncertain(anyString());
        verify(completion, never()).complete();
        assertEquals(1, pending(fixture.owners[0]));
        assertEquals(7, pending(fixture.owners[1]));
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
    }

    @Test
    void confirmationFailureDoesNotDiscardOtherResourceCompletions() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        var first = mock(Completion.class);
        var second = mock(Completion.class);
        doThrow(new IllegalStateException("source generation changed")).when(first).complete();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        fixture.batch.begin();
        fixture.prepareResource(0, 3, first);
        fixture.prepareResource(1, 7, second);
        fixture.batch.finish();
        verify(first).complete();
        verify(first).uncertain(anyString());
        verify(second).complete();
        assertEquals(0, pending(fixture.owners[0]) + pending(fixture.owners[1]));
    }

    @Test
    void unloadDuringLaterConfirmationDoesNotInvalidateTheNextBatch() {
        var fixture = new Fixture(mock(PbRecipeContext.class), 3);
        var first = mock(Completion.class);
        var second = mock(Completion.class);
        var third = mock(Completion.class);
        doAnswer(call -> {
            fixture.batch.abandon();
            return null;
        }).when(second).complete();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        fixture.batch.begin();
        fixture.prepareResource(0, 3, first);
        fixture.prepareResource(1, 7, second);
        fixture.prepareResource(2, 11, third);
        fixture.batch.finish();
        verify(first).complete();
        verify(first, never()).uncertain(anyString());
        verify(second).complete();
        verify(third).uncertain(anyString());
        verify(third, never()).complete();
        for (var owner : fixture.owners) assertFalse(owner.hasPendingOutputs());

        var next = mock(Completion.class);
        fixture.batch.begin();
        fixture.prepareResource(0, 5, next);
        fixture.batch.finish();
        verify(next).complete();
        verify(next, never()).uncertain(anyString());
        verifyNoMoreInteractions(first, second, third);
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(21L));
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(5L));
    }

    @Test
    void overflowingFluidGroupsStopAfterTheFirstPartialReceiptForThatKey() {
        var fixture = new Fixture(mock(PbRecipeContext.class), 3);
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        when(fixture.context.productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong())).thenReturn(5L);
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 1, Long.MAX_VALUE - 3);
        fixture.prepare(1, named("same"), 1, Long.MAX_VALUE);
        fixture.prepare(2, named("same"), 1, 3);
        fixture.batch.finish();
        verify(fixture.context).productivebeesgenesis$pushGeneratedFluidToAe(any(), eq(Long.MAX_VALUE - 3));
        verify(fixture.context, times(1)).productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong());
        assertEquals(Long.MAX_VALUE - 8, fixture.owners[0].getPendingFluidAmount());
        assertEquals(Long.MAX_VALUE, fixture.owners[1].getPendingFluidAmount());
        assertEquals(3, fixture.owners[2].getPendingFluidAmount());
    }

    @Test
    void disabledFactoryBatchLeavesResourcePlanOnItsSynchronousPath() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        when(fixture.context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(false);
        when(fixture.context.productivityModifier()).thenReturn(1);
        fixture.context.primaryOutputSlot(0).setStack(ItemStack.EMPTY);
        var completion = mock(Completion.class);
        var plan = new PbCentrifugeOutputPlan(fixture.owners[0], Fixture.resourceRecipe(Items.IRON_INGOT), 0);
        fixture.batch.begin();
        var local = plan.prepare(3, 91);
        assertFalse(local.defer(() -> completion));
        assertFalse(fixture.owners[0].hasPendingOutputs());
        verifyNoInteractions(completion);
        fixture.batch.finish();
    }

    @Test
    void reusedBatchRechecksComponentsWhenTheSameTemplateReferenceChanges() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        var first = named("same");
        var second = named("same");
        List<String> requests = new ArrayList<>();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> {
                    ItemStack template = call.getArgument(0);
                    requests.add(template.getHoverName().getString() + ":" + call.getArgument(1));
                    return call.getArgument(1);
                });
        fixture.batch.begin();
        fixture.prepareReference(0, first, 3);
        fixture.prepareReference(1, first, 7);
        fixture.batch.finish();
        first.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        fixture.batch.begin();
        fixture.prepareReference(1, second, 11);
        fixture.prepareReference(0, first, 5);
        fixture.batch.finish();
        assertEquals(List.of("same:10", "same:11", "changed:5"), requests);
        for (var owner : fixture.owners) assertFalse(owner.hasPaidPendingOutputs());
    }

    @Test
    void reusedSourceStorageDebitsTheNewLaneOrderAfterPartialReceipt() {
        var fixture = new Fixture(mock(PbRecipeContext.class), 3);
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3, 0);
        fixture.prepare(1, named("same"), 7, 0);
        fixture.batch.finish();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(28L))).thenReturn(13L);
        fixture.batch.begin();
        fixture.prepare(2, named("same"), 11, 0);
        fixture.prepare(0, named("same"), 5, 0);
        fixture.prepare(1, named("same"), 12, 0);
        fixture.batch.finish();
        assertEquals(0, pending(fixture.owners[2]));
        assertEquals(3, pending(fixture.owners[0]));
        assertEquals(12, pending(fixture.owners[1]));
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(28L));
    }

    @Test
    void queuedItemsKeepOriginalOwnershipAndApplyCumulativeReceiptsExactlyOnce() {
        var fixture = liveFixture(2, true);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.processor.beginFactoryOutputBatch(0);
            fixture.prepare(0, named("same"), 3, 100);
            fixture.prepare(1, named("same"), 7, 250);
            fixture.processor.finishFactoryOutputBatch();
            long epoch = fixture.batch.epoch();
            assertSame(fixture.context, fixture.batch.owner());
            assertTrue(fixture.batch.available(epoch));
            assertEquals(1, fixture.batch.groupCount());
            assertEquals(10, fixture.batch.amount(0));
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
            assertTrue(fixture.inputs[0].isEmpty());
            assertTrue(fixture.inputs[1].isEmpty());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong());

            assertTrue(fixture.batch.claim(epoch));
            assertFalse(fixture.batch.claim(epoch));
            fixture.batch.debit(0, 2);
            assertEquals(1, pending(fixture.owners[0]));
            fixture.batch.debit(0, 3);
            assertEquals(0, pending(fixture.owners[0]));
            assertEquals(5, pending(fixture.owners[1]));
            assertEquals(10, fixture.batch.amount(0));
            assertThrows(IllegalStateException.class, () -> fixture.batch.debit(0, 6));
            assertEquals(5, pending(fixture.owners[1]));

            when(fixture.context.productivebeesgenesis$pushGeneratedFluidToAe(any(), eq(350L))).thenReturn(350L);
            fixture.batch.finish(epoch);
            assertFalse(fixture.batch.available(epoch));
            assertEquals(0, fixture.batch.groupCount());
            assertEquals(0, fixture.owners[0].getPendingFluidAmount());
            assertEquals(0, fixture.owners[1].getPendingFluidAmount());
            assertEquals(5, pending(fixture.owners[1]));
            assertFalse(fixture.owners[0].isPreparedOutput());
            assertTrue(fixture.owners[1].isPreparedOutput());
            assertThrows(IllegalStateException.class, () -> fixture.batch.debit(0, 1));
            fixture.batch.finish(epoch);
            fixture.batch.cancel(epoch);
            verify(fixture.context).productivebeesgenesis$pushGeneratedFluidToAe(any(), eq(350L));
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            integration.verify(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch));
        }
    }

    @Test
    void deniedQueueAndResourceCompletionKeepTheirSynchronousHandoff() {
        var denied = liveFixture(2, false);
        var resource = liveFixture(2, false);
        var completion = mock(Completion.class);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(denied.batch)).thenReturn(false);
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(resource.batch)).thenReturn(true);
            when(denied.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L))).thenReturn(10L);
            denied.batch.begin();
            denied.prepare(0, named("same"), 3, 0);
            denied.prepare(1, named("same"), 7, 0);
            denied.batch.finish(true);
            assertEquals(0, pending(denied.owners[0]) + pending(denied.owners[1]));
            assertEquals(0, denied.batch.groupCount());
            verify(denied.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
            integration.verify(() -> Ae2IntegrationLoader.deferItemOutput(denied.batch));

            when(resource.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L))).thenReturn(10L);
            resource.batch.begin();
            resource.prepareResource(0, 3, completion);
            resource.prepare(1, new ItemStack(Items.IRON_INGOT), 7, 0);
            resource.batch.finish(true);
            integration.verify(() -> Ae2IntegrationLoader.deferItemOutput(resource.batch), never());
            verify(completion).complete();
            verify(completion, never()).uncertain(anyString());
            assertEquals(0, pending(resource.owners[0]) + pending(resource.owners[1]));
        }
    }

    @Test
    void savingQueuedOutputCancelsItsTicketAndRestoresAllPaidComponents() {
        var fixture = liveFixture(2, true);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.processor.beginFactoryOutputBatch(0);
            fixture.prepare(0, named("saved-component"), 3, 100);
            fixture.prepare(1, named("saved-component"), 7, 250);
            fixture.processor.finishFactoryOutputBatch();
            long epoch = fixture.batch.epoch();
            CompoundTag saved = new CompoundTag();
            fixture.processor.saveAdditional(saved, REGISTRIES);
            assertFalse(fixture.batch.available(epoch));
            assertFalse(fixture.batch.claim(epoch));
            assertEquals(0, fixture.batch.groupCount());
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
            fixture.batch.finish(epoch);
            fixture.batch.cancel(epoch);

            var restoredFixture = new Fixture(mock(PbRecipeContext.class), 2, true);
            var level = mock(Level.class);
            when(restoredFixture.context.level()).thenReturn(level);
            when(level.registryAccess()).thenReturn(REGISTRIES);
            when(restoredFixture.context.canFunction()).thenReturn(true);
            restoredFixture.processor.loadAdditional(saved, REGISTRIES);
            assertEquals(3, pending(restoredFixture.owners[0]));
            assertEquals(7, pending(restoredFixture.owners[1]));
            for (var owner : restoredFixture.owners) {
                ItemStack template = owner.getPendingOutputs().keySet().iterator().next();
                assertTrue(ItemStack.isSameItemSameComponents(named("saved-component"), template));
                assertEquals(0, owner.getPendingInputShrink());
                assertTrue(owner.isPreparedOutput());
            }
            assertEquals(100, restoredFixture.owners[0].getPendingFluidAmount());
            assertEquals(250, restoredFixture.owners[1].getPendingFluidAmount());
            when(restoredFixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L))).thenReturn(10L);
            when(restoredFixture.context.productivebeesgenesis$pushGeneratedFluidToAe(any(), eq(350L))).thenReturn(350L);
            restoredFixture.processor.beginFactoryOutputBatch(0);
            restoredFixture.processor.finishFactoryOutputBatch();
            CompoundTag after = new CompoundTag();
            restoredFixture.processor.saveAdditional(after, REGISTRIES);
            assertTrue(after.getList("productivebeesgenesis_pb_committed_pending", 10).isEmpty());
            verify(restoredFixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
        }
    }

    @Test
    void unloadingUnclaimedOutputReleasesTicketReferencesAndRetainsPaidSources() {
        var fixture = liveFixture(2, true);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.processor.beginFactoryOutputBatch(0);
            fixture.prepare(0, named("same"), 3, 100);
            fixture.prepare(1, named("same"), 7, 250);
            fixture.processor.finishFactoryOutputBatch();
            long epoch = fixture.batch.epoch();
            fixture.processor.clearTransientCaches();
            assertFalse(fixture.batch.available(epoch));
            assertFalse(fixture.batch.claim(epoch));
            fixture.batch.finish(epoch);
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
            assertEquals(100, fixture.owners[0].getPendingFluidAmount());
            assertEquals(250, fixture.owners[1].getPendingFluidAmount());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong());
        }
    }

    @Test
    void claimedCallbacksCannotReplaceSourcesAndCancellationStillDebitsKnownReceipt() {
        var fixture = liveFixture(2, true);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.processor.beginFactoryOutputBatch(0);
            fixture.prepare(0, named("same"), 3, 100);
            fixture.prepare(1, named("same"), 7, 250);
            fixture.processor.finishFactoryOutputBatch();
            long epoch = fixture.batch.epoch();
            assertTrue(fixture.batch.claim(epoch));
            assertThrows(IllegalStateException.class, fixture.owners[0]::clearPendingOutputs);
            assertThrows(IllegalStateException.class,
                    () -> fixture.owners[0].loadCommittedPending(new CompoundTag(), REGISTRIES));
            assertThrows(IllegalStateException.class,
                    () -> fixture.processor.loadAdditional(new CompoundTag(), REGISTRIES));
            assertThrows(IllegalStateException.class, () -> fixture.processor.beginFactoryOutputBatch(0));
            CompoundTag duringCallback = new CompoundTag();
            fixture.processor.saveAdditional(duringCallback, REGISTRIES);
            assertEquals(2, duringCallback.getList("productivebeesgenesis_pb_committed_pending", 10).size());
            fixture.processor.clearTransientCaches();
            fixture.batch.cancel(epoch);
            assertFalse(fixture.batch.available(epoch));
            assertFalse(fixture.batch.claim(epoch));
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
            fixture.batch.debit(0, 4);
            assertEquals(0, pending(fixture.owners[0]));
            assertEquals(6, pending(fixture.owners[1]));
            assertEquals(10, fixture.batch.amount(0));
            fixture.batch.finish(epoch);
            assertEquals(100, fixture.owners[0].getPendingFluidAmount());
            assertEquals(250, fixture.owners[1].getPendingFluidAmount());
            assertEquals(6, pending(fixture.owners[1]));
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToNeighbors(any(), anyInt());
        }
    }

    @Test
    void newPassDrainsCancelledTicketAndRejectsAllOldCallbacks() {
        var fixture = liveFixture(2, true);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.processor.beginFactoryOutputBatch(0);
            fixture.prepare(0, named("same"), 3, 0);
            fixture.prepare(1, named("same"), 7, 0);
            fixture.processor.finishFactoryOutputBatch();
            long firstEpoch = fixture.batch.epoch();
            when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L))).thenReturn(10L);
            fixture.processor.beginFactoryOutputBatch(0);
            verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10L));
            fixture.prepare(0, named("next"), 4, 0);
            fixture.prepare(1, named("next"), 6, 0);
            fixture.processor.finishFactoryOutputBatch();
            long secondEpoch = fixture.batch.epoch();
            assertTrue(secondEpoch > firstEpoch);
            fixture.batch.finish(firstEpoch);
            fixture.batch.cancel(firstEpoch);
            assertFalse(fixture.batch.claim(firstEpoch));
            assertTrue(fixture.batch.available(secondEpoch));
            assertEquals(4, pending(fixture.owners[0]));
            assertEquals(6, pending(fixture.owners[1]));
            assertTrue(fixture.batch.claim(secondEpoch));
            fixture.batch.debit(0, 10);
            fixture.batch.finish(secondEpoch);
            assertEquals(0, pending(fixture.owners[0]) + pending(fixture.owners[1]));
            integration.verify(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch), times(2));
            verify(fixture.context, times(1)).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
        }
    }

    @Test
    void upgradeChangesCancelExistingTicketsWithoutReplacingPaidSources() {
        var machine = mock(TileEntityMekCentrifugeFactory.class);
        var ejector = mock(GenesisTileComponentEjector.class);
        when(machine.getEjector()).thenReturn(ejector);
        when(ejector.outputCustody()).thenReturn(new OutputCustody(() -> {}));
        var fixture = new Fixture(machine, 3, true);
        bindLiveOwner(fixture);
        when(machine.productivebeesgenesis$getPbProcessor()).thenReturn(fixture.processor);
        var upgrades = new MekCentrifugePbUpgradeHandler(machine);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.processor.beginFactoryOutputBatch(0);
            fixture.prepare(0, named("same"), 3, 0);
            fixture.prepare(1, named("same"), 7, 0);
            fixture.processor.finishFactoryOutputBatch();
            long firstEpoch = fixture.batch.epoch();
            upgrades.invalidateMultiplierCache();
            assertFalse(fixture.batch.available(firstEpoch));
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));

            fixture.batch.begin();
            assertTrue(fixture.batch.defer(fixture.owners[0], 0));
            assertTrue(fixture.batch.defer(fixture.owners[1], 1));
            fixture.batch.finish(true);
            long secondEpoch = fixture.batch.epoch();
            assertTrue(fixture.batch.available(secondEpoch));
            upgrades.loadCounts(new CompoundTag());
            assertFalse(fixture.batch.available(secondEpoch));
            assertFalse(fixture.batch.claim(secondEpoch));
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
            verify(machine, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
        }
    }

    @Test
    void sourceComponentChangesInvalidateQueuedSnapshotWithoutConsumingOutput() {
        var fixture = liveFixture(2, false);
        try (var integration = mockStatic(Ae2IntegrationLoader.class)) {
            integration.when(() -> Ae2IntegrationLoader.deferItemOutput(fixture.batch)).thenReturn(true);
            fixture.batch.begin();
            fixture.prepare(0, named("original"), 3, 0);
            fixture.prepare(1, named("original"), 7, 0);
            fixture.batch.finish(true);
            long epoch = fixture.batch.epoch();
            ItemStack source = fixture.owners[0].getPendingOutputs().keySet().iterator().next();
            source.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
            assertEquals("original", fixture.batch.template(0).getHoverName().getString());
            assertFalse(fixture.batch.claim(epoch));
            assertFalse(fixture.batch.available(epoch));
            assertEquals(3, pending(fixture.owners[0]));
            assertEquals(7, pending(fixture.owners[1]));
            assertEquals("changed", source.getHoverName().getString());
            verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
        }
    }

    private static Fixture liveFixture(int lanes, boolean processorOwned) {
        BlockEntity owner = mock(BlockEntity.class, withSettings().extraInterfaces(PbRecipeContext.class));
        var fixture = new Fixture((PbRecipeContext) owner, lanes, processorOwned);
        bindLiveOwner(fixture);
        return fixture;
    }

    private static void bindLiveOwner(Fixture fixture) {
        var owner = (BlockEntity) fixture.context;
        var level = mock(ServerLevel.class);
        when(owner.getLevel()).thenReturn(level);
        when(owner.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(level.registryAccess()).thenReturn(REGISTRIES);
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(owner);
        when(fixture.context.level()).thenReturn(level);
        when(fixture.context.canFunction()).thenReturn(true);
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void verifyNoMoreInteractionsWithDirectOutputs(PbRecipeContext context) {
        verify(context, times(1)).productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong());
        verify(context, times(1)).productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong());
    }

    private static ItemStack named(String name) {
        ItemStack result = new ItemStack(Items.IRON_INGOT);
        result.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return result;
    }

    private static long pending(PbRecipeCompleter owner) {
        return owner.getPendingOutputs().values().stream().mapToLong(Long::longValue).sum();
    }

    private static final class Fixture {
        final PbRecipeContext context;
        final PbRecipeCompleter[] owners;
        final BasicInventorySlot[] inputs;
        final PbFactoryOutputBatch batch;
        final PbRecipeProcessor processor;

        Fixture(PbRecipeContext context) {
            this(context, 2);
        }

        Fixture(PbRecipeContext context, int lanes) {
            this(context, lanes, false);
        }

        Fixture(PbRecipeContext context, int lanes, boolean processorOwned) {
            this.context = context;
            inputs = new BasicInventorySlot[lanes];
            when(context.processes()).thenReturn(lanes);
            when(context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
            for (int lane = 0; lane < lanes; lane++) {
                inputs[lane] = BasicInventorySlot.at(null, 0, 0);
                when(context.inputSlot(lane)).thenReturn(inputs[lane]);
                for (int output = 0; output < 3; output++) {
                    var slot = BasicInventorySlot.at(null, 0, 0);
                    slot.setStack(new ItemStack(Items.DIAMOND, 64));
                    if (output == 0) when(context.primaryOutputSlot(lane)).thenReturn(slot);
                    if (output == 1) when(context.secondaryOutputSlot(lane)).thenReturn(slot);
                    if (output == 2) when(context.tertiaryOutputSlot(lane)).thenReturn(slot);
                }
            }
            if (processorOwned) {
                processor = new PbRecipeProcessor(context, "deferred-factory-test");
                owners = (PbRecipeCompleter[]) field(processor, "recipeCompleters");
                batch = (PbFactoryOutputBatch) field(processor, "factoryOutputBatch");
            } else {
                processor = null;
                owners = new PbRecipeCompleter[lanes];
                for (int lane = 0; lane < lanes; lane++) owners[lane] = new PbRecipeCompleter(context);
                batch = new PbFactoryOutputBatch(context, owners);
                for (var owner : owners) owner.setFactoryOutputBatch(batch);
            }
        }

        void freeze(int lane, ItemStack item, long amount, long fluidAmount) {
            inputs[lane].setStack(new ItemStack(Items.HONEYCOMB));
            FluidStack fluid = fluidAmount == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, 1);
            owners[lane].freezeMyriadBatch(Map.of(item, amount), fluid, fluidAmount, inputs[lane].getStack(), 1);
        }

        void prepare(int lane, ItemStack item, long amount, long fluidAmount) {
            freeze(lane, item, amount, fluidAmount);
            assertTrue(owners[lane].flushCompletedBatch(lane));
        }

        void prepareReference(int lane, ItemStack template, long amount) {
            freeze(lane, template, amount, 0);
            owners[lane].getPendingOutputs().clear();
            owners[lane].getPendingOutputs().put(template, amount);
            assertTrue(owners[lane].flushCompletedBatch(lane));
        }

        void prepareOutputs(int lane, Map<ItemStack, Long> outputs) {
            inputs[lane].setStack(new ItemStack(Items.HONEYCOMB));
            owners[lane].freezeMyriadBatch(outputs, FluidStack.EMPTY, 0, inputs[lane].getStack(), 1);
            assertTrue(owners[lane].flushCompletedBatch(lane));
        }

        void prepareFluid(int lane, FluidStack fluid, long amount) {
            inputs[lane].setStack(new ItemStack(Items.HONEYCOMB));
            owners[lane].freezeMyriadBatch(Map.of(), fluid, amount, inputs[lane].getStack(), 1);
            assertTrue(owners[lane].flushCompletedBatch(lane));
        }

        static CentrifugeRecipe resourceRecipe(Item item) {
            var output = new ChancedOutput(Ingredient.of(item), 1, 1, 1);
            return new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
                @Override
                public FluidStack getFluidOutputs() {
                    return FluidStack.EMPTY;
                }
            };
        }

        void prepareResource(int lane, int amount, Completion completion) {
            prepareResource(lane, Items.IRON_INGOT, amount, completion);
        }

        void prepareResource(int lane, Item item, int amount, Completion completion) {
            when(context.productivityModifier()).thenReturn(1);
            inputs[lane].setStack(new ItemStack(Items.HONEYCOMB, amount));
            var plan = new PbCentrifugeOutputPlan(owners[lane], resourceRecipe(item), lane);
            var local = plan.prepare(amount, 91);
            inputs[lane].setStack(ItemStack.EMPTY);
            assertTrue(local.defer(() -> completion));
            assertThrows(IllegalStateException.class, local::commit);
        }

        PbRecipeProcessor restoreProcessor() {
            var level = mock(Level.class);
            when(level.registryAccess()).thenReturn(REGISTRIES);
            when(context.level()).thenReturn(level);
            when(context.canFunction()).thenReturn(true);
            when(context.processes()).thenReturn(owners.length);
            ListTag pending = new ListTag();
            for (int lane = 0; lane < owners.length; lane++) {
                CompoundTag entry = owners[lane].saveCommittedPending(REGISTRIES);
                if (entry == null) continue;
                entry.putInt("process", lane);
                pending.add(entry);
            }
            CompoundTag saved = new CompoundTag();
            saved.put("productivebeesgenesis_pb_committed_pending", pending);
            var processor = new PbRecipeProcessor(context, "factory-pending-test");
            processor.loadAdditional(saved, REGISTRIES);
            return processor;
        }
    }
}
