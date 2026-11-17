package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.OutputHelper;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class SmeltingBatchLifecycleMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @BeforeAll
    static void configs() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
    }

    private static CachedRecipe<?> recipe() {
        return mock(CachedRecipe.class, withSettings().extraInterfaces(ICachedRecipeBatchAccel.class));
    }

    private static PbRecipeProcessor processor(int lanes, MachineEnergyContainer<?> energy) {
        PbRecipeContext context = mock(PbRecipeContext.class);
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(REGISTRIES);
        when(context.level()).thenReturn(level);
        when(context.processes()).thenReturn(lanes);
        when(context.productivityModifier()).thenReturn(1);
        doReturn(energy).when(context).energyContainer();
        return new PbRecipeProcessor(context, "smelting-lifecycle-test");
    }

    @Test
    void failedUnknownCompletionChargesOnlyExecutedTicksAndIsolatesItsLane() {
        var fixture = new RealCacheFixture(100, 3, 1);
        fixture.cache.failure = new IllegalStateException("completed output then failed");
        assertTrue(fixture.run(6));
        assertEquals(1, fixture.cache.produced);
        assertEquals(70, fixture.energy);
        assertEquals(30, fixture.charged);
        assertFalse(fixture.processor.canProcessSmeltingLane(0));
        assertTrue(fixture.processor.smeltingStatus().getFirst().failure().contains("completed output then failed"));
        assertNull(BatchEnergyLedger.active());
        assertTrue(((ICachedRecipeBatchAccel) fixture.cache).productivebeesgenesis$isBatchExhausted());
    }

    @Test
    void energyLimitedBatchContinuesAcrossCompletedCyclesWhileItCanStillPay() {
        var fixture = new RealCacheFixture(60, 3, 1);
        fixture.run(10);
        assertEquals(2, fixture.cache.produced);
        assertEquals(0, fixture.energy);
        assertEquals(60, fixture.charged);
        assertEquals(0, fixture.cache.progress());
    }

    @Test
    void lastAffordableSmallerParallelTickUsesTheRemainingEnergy() {
        var fixture = new RealCacheFixture(50, 10, 2);
        fixture.run(6);
        assertEquals(0, fixture.cache.produced);
        assertEquals(0, fixture.energy);
        assertEquals(50, fixture.charged);
        assertEquals(3, fixture.cache.progress());
    }

    private static final class RealCacheFixture {
        final MachineEnergyContainer<?> container = mock(MachineEnergyContainer.class);
        final RecipeCacheLookupMonitor<?> monitor = mock(RecipeCacheLookupMonitor.class);
        final RealCache cache = new RealCache();
        final PbRecipeProcessor processor = processor(1, container);
        long energy;
        long charged;

        RealCacheFixture(long available, int requiredTicks, int operations) {
            energy = available;
            when(container.getEnergy()).thenAnswer(call -> energy);
            when(container.extract(anyLong(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
                long requested = call.getArgument(0);
                long used = Math.min(energy, requested);
                energy -= used;
                charged += used;
                return used;
            });
            cache.setEnergyRequirements(() -> 10, container);
            cache.setRequiredTicks(() -> requiredTicks);
            cache.setBaselineMaxOperations(() -> operations);
            ((ICachedRecipeBatchAccel) cache).productivebeesgenesis$enableMarginalEnergyPricing();
            doReturn(cache).when(monitor).getCachedRecipe(0);
        }

        boolean run(int ticks) {
            return MekCentrifugeFactoryHelper.runSmeltingBatch(new RecipeCacheLookupMonitor<?>[]{monitor},
                    ticks, container, () -> {
                        cache.process();
                        return true;
                    }, processor);
        }
    }

    private static class RealCache extends CachedRecipe<ItemStackToItemStackRecipe> {
        int produced;
        RuntimeException failure;

        RealCache() {
            super(mock(ItemStackToItemStackRecipe.class), () -> false);
        }

        @Override
        public boolean isInputValid() {
            return true;
        }

        @Override
        protected void finishProcessing(int operations) {
            produced += operations;
            if (failure != null) throw failure;
        }

        int progress() {
            return getOperatingTicks();
        }
    }

    @Test
    void fullTickReplacesCacheBeforeAccelerationAndChargesOneLedger() {
        var monitor = mock(RecipeCacheLookupMonitor.class);
        var old = recipe();
        var current = recipe();
        var energy = mock(MachineEnergyContainer.class);
        when(energy.getEnergy()).thenReturn(100L);
        when(energy.extract(14, Action.EXECUTE, AutomationType.INTERNAL)).thenReturn(14L);
        doAnswer(call -> {
            assertNotNull(BatchEnergyLedger.active());
            BatchEnergyLedger.active().add(3);
            return null;
        }).when(current).process();
        assertTrue(MekCentrifugeFactoryHelper.runSmeltingBatch(new RecipeCacheLookupMonitor<?>[]{monitor}, 4, energy, () -> {
            verify(monitor).unpause();
            BatchEnergyLedger.active().add(5);
            doReturn(current).when(monitor).getCachedRecipe(0);
            return true;
        }, processor(1, energy)));
        verify(old, never()).process();
        verify(current, times(3)).process();
        verify((ICachedRecipeBatchAccel) current).productivebeesgenesis$startBatch(3);
        verify((ICachedRecipeBatchAccel) current).productivebeesgenesis$finishBatch();
        verify(energy, times(1)).extract(14, Action.EXECUTE, AutomationType.INTERNAL);
        assertNull(BatchEnergyLedger.active());
    }

    @Test
    void startFailureKeepsHealthyLanesAndSettlesTheirActualEnergy() {
        var first = recipe();
        var second = recipe();
        var third = recipe();
        var monitors = new RecipeCacheLookupMonitor<?>[3];
        for (int i = 0; i < monitors.length; i++) {
            monitors[i] = mock(RecipeCacheLookupMonitor.class);
        }
        doReturn(first).when(monitors[0]).getCachedRecipe(0);
        doReturn(second).when(monitors[1]).getCachedRecipe(1);
        doReturn(third).when(monitors[2]).getCachedRecipe(2);
        doThrow(new IllegalStateException("start after binding failed"))
                .when((ICachedRecipeBatchAccel) second).productivebeesgenesis$startBatch(3);
        doAnswer(call -> {
            BatchEnergyLedger.active().add(2);
            return null;
        }).when(first).process();
        doAnswer(call -> {
            BatchEnergyLedger.active().add(3);
            return null;
        }).when(third).process();
        var energy = mock(MachineEnergyContainer.class);
        when(energy.getEnergy()).thenReturn(100L);
        when(energy.extract(22, Action.EXECUTE, AutomationType.INTERNAL)).thenReturn(22L);
        assertTrue(MekCentrifugeFactoryHelper.runSmeltingBatch(monitors, 4, energy, () -> {
            BatchEnergyLedger.active().add(7);
            return true;
        }, processor(3, energy)));
        verify(first, times(3)).process();
        verify(second, never()).process();
        verify(third, times(3)).process();
        for (CachedRecipe<?> current : List.of(first, second, third)) {
            verify((ICachedRecipeBatchAccel) current).productivebeesgenesis$finishBatch();
        }
        verify(energy).extract(22, Action.EXECUTE, AutomationType.INTERNAL);
        assertNull(BatchEnergyLedger.active());
    }

    @Test
    void processAndCleanupFailuresKeepRootCauseWhileHealthyLanesContinue() {
        var first = recipe();
        var second = recipe();
        var monitors = new RecipeCacheLookupMonitor<?>[]{
                mock(RecipeCacheLookupMonitor.class), mock(RecipeCacheLookupMonitor.class)};
        doReturn(first).when(monitors[0]).getCachedRecipe(0);
        doReturn(second).when(monitors[1]).getCachedRecipe(1);
        doAnswer(call -> {
            BatchEnergyLedger.active().add(4);
            throw new IllegalStateException("processing failed");
        }).when(first).process();
        doThrow(new IllegalStateException("first cleanup failed"))
                .when((ICachedRecipeBatchAccel) first).productivebeesgenesis$finishBatch();
        doAnswer(call -> {
            BatchEnergyLedger.active().add(3);
            return null;
        }).when(second).process();
        var energy = mock(MachineEnergyContainer.class);
        when(energy.getEnergy()).thenReturn(100L);
        when(energy.extract(24, Action.EXECUTE, AutomationType.INTERNAL)).thenReturn(24L);
        var processor = processor(2, energy);
        assertTrue(MekCentrifugeFactoryHelper.runSmeltingBatch(monitors, 4, energy, () -> {
            BatchEnergyLedger.active().add(11);
            return true;
        }, processor));
        verify(second, times(3)).process();
        verify((ICachedRecipeBatchAccel) second).productivebeesgenesis$finishBatch();
        verify(energy).extract(24, Action.EXECUTE, AutomationType.INTERNAL);
        assertEquals(1, processor.smeltingStatus().size());
        assertTrue(processor.smeltingStatus().getFirst().failure().contains("processing failed"));
        assertNull(BatchEnergyLedger.active());
    }

    private static final class NativeEnergy extends MachineEnergyContainer<TileEntityMekCentrifuge> {
        boolean unreadable;

        NativeEnergy(IContentsListener listener) {
            super(10_000, 10, automation -> true, automation -> true, null, listener);
        }

        @Override
        public long getEnergy() {
            if (unreadable) throw new IllegalStateException("public energy receipt unavailable");
            return super.getEnergy();
        }

        long stored() {
            return ((BasicEnergyContainerAccessor) (Object) this).productivebeesgenesis$getStored();
        }
    }

    private static final class NativeFixture {
        final IFactoryPbDelegateAccess context = mock(IFactoryPbDelegateAccess.class);
        final InputInventorySlot[] inputs = new InputInventorySlot[2];
        final OutputInventorySlot[] outputs = new OutputInventorySlot[2];
        final RecipeCacheLookupMonitor<?>[] monitors = new RecipeCacheLookupMonitor<?>[2];
        final CentrifugeSmeltingCachedRecipe[] caches = new CentrifugeSmeltingCachedRecipe[2];
        final boolean[] inputFailure = new boolean[2];
        final boolean[] outputFailure = new boolean[2];
        final boolean[] inputUnreadable = new boolean[2];
        final boolean[] outputUnreadable = new boolean[2];
        final boolean[] completionFailure = new boolean[2];
        final boolean[] energyFailure = new boolean[1];
        final int[] progress = new int[2];
        final NativeEnergy energy;
        final PbRecipeProcessor processor;

        NativeFixture() {
            NativeEnergy[] reference = new NativeEnergy[1];
            energy = new NativeEnergy(() -> {
                if (energyFailure[0]) {
                    energyFailure[0] = false;
                    reference[0].unreadable = true;
                    throw new IllegalStateException("energy changed before notification failed");
                }
            });
            reference[0] = energy;
            energy.setEnergy(1_000);
            Level level = mock(Level.class);
            when(level.registryAccess()).thenReturn(REGISTRIES);
            when(context.level()).thenReturn(level);
            when(context.processes()).thenReturn(2);
            when(context.productivityModifier()).thenReturn(1);
            doReturn(energy).when(context).energyContainer();
            processor = new PbRecipeProcessor(context, "native-smelting-test");
            when(context.productivebeesgenesis$getPbProcessor()).thenReturn(processor);
            var selected = mock(ItemStackToItemStackRecipe.class);
            when(selected.getInput()).thenReturn(IngredientCreatorAccess.item().from(Items.IRON_ORE));
            when(selected.getOutput(any())).thenAnswer(call -> new ItemStack(Items.IRON_INGOT));
            for (int lane = 0; lane < inputs.length; lane++) {
                int index = lane;
                inputs[lane] = spy(InputInventorySlot.at(stack -> true, () -> {
                    if (inputFailure[index]) {
                        inputFailure[index] = false;
                        inputUnreadable[index] = true;
                        throw new IllegalStateException("input changed before notification failed");
                    }
                }, 0, 0));
                outputs[lane] = spy(OutputInventorySlot.at(() -> {
                    if (outputFailure[index]) {
                        outputFailure[index] = false;
                        outputUnreadable[index] = true;
                        throw new IllegalStateException("output changed before notification failed");
                    }
                }, 0, 0));
                inputs[lane].setStack(new ItemStack(Items.IRON_ORE, 10));
                doAnswer(call -> {
                    if (inputUnreadable[index]) throw new IllegalStateException("public input receipt unavailable");
                    return call.callRealMethod();
                }).when(inputs[lane]).getStack();
                doAnswer(call -> {
                    if (outputUnreadable[index]) throw new IllegalStateException("public output receipt unavailable");
                    return call.callRealMethod();
                }).when(outputs[lane]).getStack();
                when(context.inputSlot(lane)).thenReturn(inputs[lane]);
                when(context.primaryOutputSlot(lane)).thenReturn(outputs[lane]);
                when(context.secondaryOutputSlot(lane)).thenReturn(OutputInventorySlot.at(null, 0, 0));
                when(context.tertiaryOutputSlot(lane)).thenReturn(OutputInventorySlot.at(null, 0, 0));
                caches[lane] = new CentrifugeSmeltingCachedRecipe(selected, () -> false,
                        InputHelper.getInputHandler(inputs[lane], CachedRecipe.OperationTracker.RecipeError.NOT_ENOUGH_INPUT),
                        OutputHelper.getOutputHandler(outputs[lane], CachedRecipe.OperationTracker.RecipeError.NOT_ENOUGH_OUTPUT_SPACE),
                        processor, context, lane, ticks -> progress[index] = ticks);
                caches[lane].setCanHolderFunction(() -> processor.canProcessSmeltingLane(index))
                        .setEnergyRequirements(() -> 10, energy)
                        .setRequiredTicks(() -> 3)
                        .setBaselineMaxOperations(() -> 1)
                        .setOperatingTicksChanged(ticks -> progress[index] = ticks)
                        .setOnFinish(() -> {
                            if (completionFailure[index]) {
                                completionFailure[index] = false;
                                throw new IllegalStateException("completion save notification failed");
                            }
                        });
                ((ICachedRecipeBatchAccel) (Object) caches[lane]).productivebeesgenesis$enableMarginalEnergyPricing();
                monitors[lane] = mock(RecipeCacheLookupMonitor.class);
                doReturn(caches[lane]).when(monitors[lane]).getCachedRecipe(lane);
            }
        }

        boolean run(int ticks) {
            return MekCentrifugeFactoryHelper.runSmeltingBatch(monitors, ticks, energy, () -> {
                for (int lane = 0; lane < caches.length; lane++) {
                    int index = lane;
                    MekCentrifugeFactoryHelper.processSmeltingMonitor(processor, lane, () -> {
                        caches[index].process();
                        return true;
                    });
                }
                return true;
            }, processor);
        }

        int count(IInventorySlot slot) {
            return ((BasicInventorySlotAccessor) slot).productivebeesgenesis$getCurrent().getCount();
        }
    }

    @Test
    void completedNotificationFailureKeepsFullBatchOutputAndEnergy() {
        var fixture = new NativeFixture();
        fixture.completionFailure[0] = true;
        assertTrue(fixture.run(6));
        for (int lane = 0; lane < 2; lane++) {
            assertEquals(8, fixture.count(fixture.inputs[lane]));
            assertEquals(2, fixture.count(fixture.outputs[lane]));
            assertEquals(0, fixture.progress[lane]);
        }
        assertEquals(880, fixture.energy.stored());
        assertTrue(fixture.processor.smeltingStatus().isEmpty());
    }

    @Test
    void inputAndOutputNotificationsUseActualLocalStateWithoutWaitingForPublicReads() {
        var fixture = new NativeFixture();
        fixture.inputFailure[0] = true;
        fixture.outputFailure[1] = true;
        assertTrue(fixture.run(3));
        for (int lane = 0; lane < 2; lane++) {
            assertEquals(9, fixture.count(fixture.inputs[lane]));
            assertEquals(1, fixture.count(fixture.outputs[lane]));
        }
        assertEquals(940, fixture.energy.stored());
        assertTrue(fixture.processor.smeltingStatus().isEmpty());
        fixture.inputUnreadable[0] = false;
        fixture.outputUnreadable[1] = false;
        fixture.run(3);
        assertEquals(8, fixture.count(fixture.inputs[0]));
        assertEquals(8, fixture.count(fixture.inputs[1]));
        assertEquals(2, fixture.count(fixture.outputs[0]));
        assertEquals(2, fixture.count(fixture.outputs[1]));
        assertEquals(880, fixture.energy.stored());
    }

    @Test
    void energyNotificationFailureSettlesOneActualDebitAndNextBatchContinues() {
        var fixture = new NativeFixture();
        fixture.energyFailure[0] = true;
        fixture.run(3);
        assertEquals(940, fixture.energy.stored());
        assertTrue(fixture.processor.smeltingStatus().isEmpty());
        fixture.energy.unreadable = false;
        fixture.run(3);
        assertEquals(880, fixture.energy.stored());
        assertEquals(2, fixture.count(fixture.outputs[0]));
        assertEquals(2, fixture.count(fixture.outputs[1]));
    }

    @Test
    void ordinaryEnergyNotificationFailureKeepsPaidProgressAndHealthyLane() {
        var fixture = new NativeFixture();
        fixture.energyFailure[0] = true;
        fixture.caches[0].process();
        assertEquals(990, fixture.energy.stored());
        assertEquals(1, fixture.progress[0]);
        assertTrue(fixture.processor.smeltingStatus().isEmpty());
        fixture.energy.unreadable = false;
        fixture.caches[1].process();
        assertEquals(980, fixture.energy.stored());
        assertEquals(1, fixture.progress[1]);
    }

    @Test
    void knownOutputRemainderReloadDeliversOnceWhileHealthyLaneUsesItsBudget() {
        var fixture = new NativeFixture();
        doThrow(new IllegalStateException("output insertion unavailable")).doCallRealMethod()
                .when(fixture.outputs[0]).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
        fixture.run(6);
        assertEquals(9, fixture.count(fixture.inputs[0]));
        assertEquals(0, fixture.count(fixture.outputs[0]));
        assertEquals(2, fixture.count(fixture.outputs[1]));
        assertEquals(910, fixture.energy.stored());
        assertFalse(fixture.processor.canProcessSmeltingLane(0));
        assertTrue(fixture.processor.canProcessSmeltingLane(1));
        assertFalse(fixture.processor.smeltingOutputLocked(1));
        CompoundTag saved = new CompoundTag();
        fixture.processor.saveAdditional(saved, REGISTRIES);
        var request = fixture.processor.smeltingStatus().getFirst().request();
        var restored = new PbRecipeProcessor(fixture.context, "smelting-reload-test");
        restored.loadAdditional(saved, REGISTRIES);
        assertEquals(request, restored.smeltingStatus().getFirst().request());
        assertTrue(restored.prepareSmeltingLane(0));
        assertEquals(1, fixture.count(fixture.outputs[0]));
        assertEquals(9, fixture.count(fixture.inputs[0]));
        assertEquals(910, fixture.energy.stored());
        assertTrue(restored.prepareSmeltingLane(0));
        assertEquals(1, fixture.count(fixture.outputs[0]));
        assertTrue(restored.smeltingStatus().isEmpty());
    }

    @Test
    void knownInputRemainderLocksOnlyItsSourceAndReloadConsumesItOnce() {
        var fixture = new NativeFixture();
        doThrow(new IllegalStateException("input debit unavailable")).doCallRealMethod()
                .when(fixture.inputs[0]).shrinkStack(1, Action.EXECUTE);
        fixture.run(6);
        assertEquals(10, fixture.count(fixture.inputs[0]));
        assertEquals(0, fixture.count(fixture.outputs[0]));
        assertEquals(2, fixture.count(fixture.outputs[1]));
        assertTrue(fixture.processor.smeltingInputLocked(0));
        assertFalse(fixture.processor.smeltingInputLocked(1));
        assertArrayEquals(new Integer[]{1}, fixture.processor.sortableSmeltingInputs(new Integer[]{0, 1}, lane -> lane));
        CompoundTag saved = new CompoundTag();
        fixture.processor.saveAdditional(saved, REGISTRIES);
        var restored = new PbRecipeProcessor(fixture.context, "smelting-input-reload-test");
        restored.loadAdditional(saved, REGISTRIES);
        assertTrue(restored.prepareSmeltingLane(0));
        assertEquals(9, fixture.count(fixture.inputs[0]));
        assertEquals(1, fixture.count(fixture.outputs[0]));
        assertTrue(restored.prepareSmeltingLane(0));
        assertEquals(9, fixture.count(fixture.inputs[0]));
        Integer[] healthy = {0, 1};
        assertSame(healthy, restored.sortableSmeltingInputs(healthy, lane -> lane));
        assertEquals(910, fixture.energy.stored());
    }

    @Test
    void sharedKnownEnergyRemainderKeepsHealthyOutputsAvailable() {
        var fixture = new NativeFixture();
        fixture.outputs[1].setStack(new ItemStack(Items.IRON_INGOT, 5));
        fixture.processor.smeltingWork().retainEnergy(10, false, new IllegalStateException("energy read unavailable"));
        assertFalse(fixture.processor.smeltingInputLocked(1));
        assertFalse(fixture.processor.smeltingOutputLocked(1));
        Integer[] healthy = {0, 1};
        assertSame(healthy, fixture.processor.sortableSmeltingInputs(healthy, lane -> lane));
        assertTrue(fixture.processor.prepareSmeltingLane(0));
        assertEquals(990, fixture.energy.stored());
        assertEquals(5, fixture.outputs[1].extractItem(5, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
        assertTrue(fixture.processor.smeltingStatus().isEmpty());
    }

    @Test
    void actualFactorySortingKeepsRetainedSourcesAndBalancesHealthyInputs() throws ReflectiveOperationException {
        var blocks = new java.util.ArrayList<net.minecraft.world.level.block.Block>();
        blocks.add(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get());
        ModBlocks.ME_FACTORIES.values().stream().findFirst().ifPresent(block -> blocks.add(block.get()));
        ModBlocks.EME_FACTORIES.values().stream().findFirst().ifPresent(block -> blocks.add(block.get()));
        var selected = mock(ItemStackToItemStackRecipe.class);
        when(selected.getInput()).thenReturn(IngredientCreatorAccess.item().from(Items.IRON_ORE));
        when(selected.getOutput(any())).thenAnswer(call -> new ItemStack(Items.IRON_INGOT));
        when(selected.test(any(ItemStack.class))).thenReturn(true);
        for (var block : blocks) {
            var tile = assertInstanceOf(mekanism.common.tile.base.TileEntityMekanism.class,
                    ((net.minecraft.world.level.block.EntityBlock) block).newBlockEntity(
                            net.minecraft.core.BlockPos.ZERO, block.defaultBlockState()));
            var host = assertInstanceOf(IFactoryPbDelegateAccess.class, tile);
            Level level = mock(Level.class);
            when(level.registryAccess()).thenReturn(REGISTRIES);
            when(level.getRecipeManager()).thenReturn(mock(net.minecraft.world.item.crafting.RecipeManager.class));
            tile.setLevel(level);
            int lanes = host.processes();
            Class<?> owner = tile.getClass();
            java.lang.reflect.Method sort = null;
            while (sort == null) {
                try {
                    sort = owner.getDeclaredMethod("sortInventory");
                } catch (NoSuchMethodException absentOnSubclass) {
                    owner = owner.getSuperclass();
                }
            }
            var monitorField = owner.getDeclaredField("recipeCacheLookupMonitors");
            monitorField.setAccessible(true);
            var monitors = (RecipeCacheLookupMonitor<?>[]) monitorField.get(tile);
            for (int lane = 0; lane < lanes; lane++) {
                ((mekanism.common.inventory.slot.BasicInventorySlot) host.inputSlot(lane))
                        .setStackUnchecked(new ItemStack(Items.IRON_ORE, lane == 0 ? 7 : lane == 1 ? 40 : 1));
                host.primaryOutputSlot(lane).setStack(new ItemStack(Items.IRON_INGOT));
                var cache = ((mekanism.common.recipe.lookup.IRecipeLookupHandler<ItemStackToItemStackRecipe>) tile)
                        .createNewCachedRecipe(selected, lane);
                var monitor = monitors[lane];
                ((com.ayoshiko.productivebeesgenesis.mixin.accessor.RecipeCacheLookupMonitorAccessor) monitor)
                        .productivebeesgenesis$setCachedRecipe(cache);
            }
            var processor = host.productivebeesgenesis$getPbProcessor();
            processor.smeltingWork().retain(0, new ItemStack(Items.IRON_ORE), 1,
                    new ItemStack(Items.IRON_INGOT), 1, false, "input", new IllegalStateException("retained paid source"));
            int totalBefore = 0;
            for (int lane = 0; lane < lanes; lane++) {
                totalBefore += host.inputSlot(lane).getCount();
            }
            sort.setAccessible(true);
            sort.invoke(tile);
            assertEquals(7, host.inputSlot(0).getCount());
            assertEquals(0, host.inputSlot(0).extractItem(7, Action.EXECUTE, AutomationType.MANUAL).getCount());
            assertEquals(1, host.inputSlot(0).insertItem(new ItemStack(Items.IRON_ORE), Action.EXECUTE,
                    AutomationType.EXTERNAL).getCount());
            int totalAfter = 0;
            int smallest = Integer.MAX_VALUE;
            int largest = 0;
            for (int lane = 0; lane < lanes; lane++) {
                int count = host.inputSlot(lane).getCount();
                totalAfter += count;
                if (lane > 0) {
                    smallest = Math.min(smallest, count);
                    largest = Math.max(largest, count);
                }
            }
            assertEquals(totalBefore, totalAfter);
            assertTrue(largest - smallest <= 1);
            assertTrue(host.inputSlot(1).getCount() < 40);
            assertTrue(processor.prepareSmeltingLane(0));
            assertEquals(6, host.inputSlot(0).getCount());
            assertEquals(2, host.primaryOutputSlot(0).getCount());
            processor.smeltingWork().retain(0, ItemStack.EMPTY, 0, new ItemStack(Items.IRON_INGOT),
                    1, true, "output", new IllegalStateException("unresolved primary output"));
            assertTrue(host.primaryOutputSlot(0).extractItem(1, Action.EXECUTE, AutomationType.EXTERNAL).isEmpty());
            assertEquals(1, host.primaryOutputSlot(1).extractItem(1, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
        }
    }
}
