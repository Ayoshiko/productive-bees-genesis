package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
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

        Fixture(PbRecipeContext context) {
            this(context, 2);
        }

        Fixture(PbRecipeContext context, int lanes) {
            this.context = context;
            owners = new PbRecipeCompleter[lanes];
            inputs = new BasicInventorySlot[lanes];
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
                owners[lane] = new PbRecipeCompleter(context);
            }
            batch = new PbFactoryOutputBatch(context, owners);
            for (var owner : owners) owner.setFactoryOutputBatch(batch);
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
