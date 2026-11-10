package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt()))
                .thenAnswer(call -> call.getArgument(1));
        when(fixture.context.productivebeesgenesis$pushGeneratedFluidToAe(any(), anyLong()))
                .thenAnswer(call -> call.getArgument(1));
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3, 100);
        fixture.prepare(1, named("same"), 7, 250);
        verify(fixture.context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt());
        assertTrue(fixture.inputs[0].isEmpty());
        assertTrue(fixture.inputs[1].isEmpty());
        fixture.batch.finish();
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10));
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
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt())).thenReturn(5);
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3, 0);
        fixture.prepare(1, named("same"), 7, 0);
        fixture.batch.finish();
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10));
        assertEquals(0, pending(fixture.owners[0]));
        assertEquals(5, pending(fixture.owners[1]));
        var saved = fixture.owners[1].saveCommittedPending(REGISTRIES);
        var restored = new PbRecipeCompleter(fixture.context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertEquals(5, pending(restored));
        assertEquals(0, restored.getPendingInputShrink());
    }

    @Test
    void segmentedReceiptsDebitSourcesInOrderAndStopOnPartialOrZeroAcceptance() {
        int maximum = Integer.MAX_VALUE;
        long total = 3L * maximum + 7;
        long[] amounts = {maximum - 5L, maximum, maximum, 12};
        for (int finalReceipt : new int[]{0, maximum - 2}) {
            var fixture = new Fixture(mock(PbRecipeContext.class), amounts.length);
            List<Integer> requests = new ArrayList<>();
            long[] received = {0L};
            when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt()))
                    .thenAnswer(call -> {
                        long held = 0;
                        for (var owner : fixture.owners) held += pending(owner);
                        assertEquals(total, received[0] + held);
                        int requested = call.getArgument(1);
                        requests.add(requested);
                        int accepted = requests.size() <= 2 ? requested : finalReceipt;
                        received[0] += accepted;
                        return accepted;
                    });
            fixture.batch.begin();
            for (int lane = 0; lane < amounts.length; lane++) {
                fixture.prepare(lane, named("same"), amounts[lane], 0);
            }
            fixture.batch.finish();
            assertEquals(List.of(maximum, maximum, maximum), requests);
            verify(fixture.context, times(3)).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(maximum));
            long[] expected = finalReceipt == 0
                    ? new long[]{0, 0, maximum - 5L, 12}
                    : new long[]{0, 0, 0, 9};
            long remaining = 0;
            for (int lane = 0; lane < amounts.length; lane++) {
                assertEquals(expected[lane], pending(fixture.owners[lane]));
                assertEquals(0, fixture.owners[lane].getPendingInputShrink());
                assertTrue(fixture.inputs[lane].isEmpty());
                remaining += pending(fixture.owners[lane]);
            }
            assertEquals(2L * maximum + finalReceipt, received[0]);
            assertEquals(total, received[0] + remaining);
        }
    }

    @Test
    void distinctComponentsKeepSeparateReceipts() {
        var fixture = new Fixture(mock(PbRecipeContext.class));
        List<String> names = new ArrayList<>();
        when(fixture.context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt())).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            names.add(stack.getHoverName().getString());
            return call.getArgument(1);
        });
        fixture.batch.begin();
        fixture.prepare(0, named("first"), 3, 0);
        fixture.prepare(1, named("second"), 7, 0);
        fixture.batch.finish();
        assertEquals(List.of("first", "second"), names);
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(3));
        verify(fixture.context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(7));
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
        when(machine.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt()))
                .thenThrow(new IllegalStateException("receiver accepted an unknown prefix"));
        fixture.batch.begin();
        fixture.prepare(0, named("same"), 3, 0);
        fixture.prepare(1, named("same"), 7, 0);
        fixture.batch.finish();
        assertTrue(custody.blocked());
        assertEquals(10, custody.amount());
        assertEquals(0, pending(fixture.owners[0]) + pending(fixture.owners[1]));
        var restored = new OutputCustody(() -> {});
        restored.load(REGISTRIES, custody.save(REGISTRIES));
        restored.resolve(restored.sequence(), 4);
        assertEquals(6, restored.recoveredItem().getCount());
        assertEquals("same", restored.recoveredItem().getHoverName().getString());
        fixture.batch.begin();
        fixture.batch.finish();
        verify(machine).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(10));
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
        when(machine.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt()))
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
            }).when(machine).productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt());
            fixture.batch.begin();
            fixture.inputs[0].setStack(new ItemStack(Items.HONEYCOMB));
            fixture.owners[0].accumulatePbRecipeOutputsBatch(recipe, 0, 1, 1);
            fixture.owners[0].freezeCompletedBatch(fixture.inputs[0].getStack());
            assertTrue(fixture.owners[0].flushCompletedBatch(0));
            fixture.batch.finish();
            assertFalse(fixture.owners[0].hasPaidPendingOutputs());
            conversion.verify(() -> RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(eq(level), anyMap()), times(3));
            verify(machine).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(1));
        }
    }

    private static void verifyNoMoreInteractionsWithDirectOutputs(PbRecipeContext context) {
        verify(context, times(1)).productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt());
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

        void prepare(int lane, ItemStack item, long amount, long fluidAmount) {
            inputs[lane].setStack(new ItemStack(Items.HONEYCOMB));
            FluidStack fluid = fluidAmount == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, 1);
            owners[lane].freezeMyriadBatch(Map.of(item, amount), fluid, fluidAmount, inputs[lane].getStack(), 1);
            assertTrue(owners[lane].flushCompletedBatch(lane));
        }
    }
}
