package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class SmeltingBatchLifecycleMinecraftTest {
    private static CachedRecipe<?> recipe() {
        return mock(CachedRecipe.class, withSettings().extraInterfaces(ICachedRecipeBatchAccel.class));
    }

    @Test void fullTickReplacesCacheBeforeAccelerationAndChargesOneLedger() {
        var monitor = mock(RecipeCacheLookupMonitor.class);
        var old = recipe();
        var current = recipe();
        var energy = mock(MachineEnergyContainer.class);
        when(monitor.getCachedRecipe(0)).thenReturn(old);
        doAnswer(call -> {
            assertNotNull(BatchEnergyLedger.active());
            BatchEnergyLedger.active().add(3);
            return null;
        }).when(current).process();
        assertTrue(MekCentrifugeFactoryHelper.runSmeltingBatch(new RecipeCacheLookupMonitor<?>[]{monitor}, 4, energy, () -> {
            verify(monitor).unpause();
            BatchEnergyLedger.active().add(5);
            when(monitor.getCachedRecipe(0)).thenReturn(current);
            return true;
        }));
        verify(old, never()).process();
        verify(current, times(3)).process();
        verify((ICachedRecipeBatchAccel) current).productivebeesgenesis$startBatch(3);
        verify((ICachedRecipeBatchAccel) current).productivebeesgenesis$finishBatch();
        verify(energy, times(1)).extract(14, Action.EXECUTE, AutomationType.INTERNAL);
        assertNull(BatchEnergyLedger.active());
    }

    @Test void partialStartFailureClosesOnlyReachedSessionsAndStillChargesTheFullTick() {
        var first = recipe();
        var second = recipe();
        var third = recipe();
        var monitors = new RecipeCacheLookupMonitor<?>[3];
        for (int i = 0; i < monitors.length; i++) monitors[i] = mock(RecipeCacheLookupMonitor.class);
        doReturn(first).when(monitors[0]).getCachedRecipe(0);
        doReturn(second).when(monitors[1]).getCachedRecipe(1);
        doReturn(third).when(monitors[2]).getCachedRecipe(2);
        var original = new IllegalStateException("start after binding failed");
        doThrow(original).when((ICachedRecipeBatchAccel) second).productivebeesgenesis$startBatch(3);
        var energy = mock(MachineEnergyContainer.class);
        var actual = assertThrows(IllegalStateException.class, () -> MekCentrifugeFactoryHelper.runSmeltingBatch(monitors, 4, energy, () -> {
            BatchEnergyLedger.active().add(7);
            return true;
        }));
        assertSame(original, actual);
        verify((ICachedRecipeBatchAccel) first).productivebeesgenesis$finishBatch();
        verify((ICachedRecipeBatchAccel) second).productivebeesgenesis$finishBatch();
        verify((ICachedRecipeBatchAccel) third, never()).productivebeesgenesis$finishBatch();
        verify(energy).extract(7, Action.EXECUTE, AutomationType.INTERNAL);
        assertNull(BatchEnergyLedger.active());
    }

    @Test void processFailureRetainsRootCauseWhenCleanupAlsoFailsAndOtherLanesClose() {
        var first = recipe();
        var second = recipe();
        var monitors = new RecipeCacheLookupMonitor<?>[]{mock(RecipeCacheLookupMonitor.class), mock(RecipeCacheLookupMonitor.class)};
        doReturn(first).when(monitors[0]).getCachedRecipe(0);
        doReturn(second).when(monitors[1]).getCachedRecipe(1);
        var original = new IllegalStateException("processing failed");
        var cleanup = new IllegalStateException("first cleanup failed");
        doThrow(original).when(first).process();
        doThrow(cleanup).when((ICachedRecipeBatchAccel) first).productivebeesgenesis$finishBatch();
        var energy = mock(MachineEnergyContainer.class);
        var actual = assertThrows(IllegalStateException.class, () -> MekCentrifugeFactoryHelper.runSmeltingBatch(monitors, 4, energy, () -> {
            BatchEnergyLedger.active().add(11);
            return true;
        }));
        assertSame(original, actual);
        assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
        verify((ICachedRecipeBatchAccel) second).productivebeesgenesis$finishBatch();
        verify(energy).extract(11, Action.EXECUTE, AutomationType.INTERNAL);
        assertNull(BatchEnergyLedger.active());
    }
}
