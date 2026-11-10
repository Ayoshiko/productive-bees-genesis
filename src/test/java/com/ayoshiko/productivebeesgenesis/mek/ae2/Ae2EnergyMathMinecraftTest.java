package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.config.MekanismConfig;
import mekanism.common.util.UnitDisplayUtils.EnergyUnit;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2EnergyMathMinecraftTest {
    @BeforeAll
    static void loadConfigs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unloadConfigs() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void exactRequestArithmeticMatchesDecimalReferenceAtLongAndRatioBoundaries() {
        long[] shortfalls = {0, 1, 2, 3, 5, 99, 100, (1L << 53) + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        double[] conversions = {1.0, 1.5, 2.5, 4.0, 12345.5, 0.1, 0.0001, Math.nextUp(2.5), Double.MAX_VALUE};
        BigDecimal[] retained = {BigDecimal.ZERO, new BigDecimal("0.5"), new BigDecimal("0.75")};
        for (long shortfall : shortfalls) {
            for (double conversion : conversions) {
                for (BigDecimal fraction : retained) {
                    BigDecimal available = BigDecimal.valueOf(shortfall).subtract(fraction);
                    long expected = available.signum() <= 0 ? 0L
                            : available.divide(BigDecimal.valueOf(conversion), 0, java.math.RoundingMode.DOWN)
                                    .min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
                    assertEquals(expected, Ae2EnergyMath.requestedFe(shortfall, fraction, conversion),
                            () -> shortfall + " J, retained=" + fraction + ", ratio=" + conversion);
                }
            }
        }
        assertThrows(IllegalStateException.class, () -> Ae2EnergyMath.requestedFe(1, BigDecimal.ZERO, 0));
        assertThrows(IllegalStateException.class, () -> Ae2EnergyMath.requestedFe(1, BigDecimal.ZERO, Double.NaN));
    }

    @Test
    void capacityAndBatchShortfallRemainInJoules() {
        assertEquals(9_000, Ae2EnergyMath.remainingCapacity(1_000, 10_000));
        assertEquals(Long.MAX_VALUE, Ae2EnergyMath.remainingCapacity(0, Long.MAX_VALUE));
        assertEquals(5, Ae2EnergyMath.requiredShortfall(Long.MAX_VALUE - 5, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(25, Ae2EnergyMath.requiredShortfall(75, 100, 925));
        assertEquals(0, Ae2EnergyMath.requiredShortfall(150, 100, 850));
    }

    @Test
    void nativeBridgeUsesRuntimeMekanismRatioWithoutChargingJoulesAsFe() {
        for (double conversion : new double[]{2.5, 4.0}) {
            try (var fixture = new Fixture(conversion, false)) {
                when(fixture.nativeEnergy.extractAEPower(anyDouble(), eq(Actionable.MODULATE), eq(PowerMultiplier.ONE)))
                        .thenAnswer(call -> call.getArgument(0));
                assertEquals(conversion, EnergyUnit.FORGE_ENERGY.getConversion());
                assertEquals(1_000, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
                assertEquals(1_000, fixture.energy);
                verify(fixture.nativeEnergy).extractAEPower(eq(1_000 / conversion / 2),
                        eq(Actionable.MODULATE), eq(PowerMultiplier.ONE));
                assertFalse(fixture.holder.getEnergyTransfer().isPresent());
            }
        }
    }

    @Test
    void unknownNativeExtractionStopsFallbackAndSurvivesSaveUntilReconciled() {
        try (var fixture = new Fixture(2.5, true)) {
            double[] sourceAe = {500};
            when(fixture.nativeEnergy.extractAEPower(eq(200D), eq(Actionable.MODULATE), eq(PowerMultiplier.ONE)))
                    .thenAnswer(call -> {
                        sourceAe[0] -= 80;
                        throw new IllegalStateException("source consumed 80 AE before failure");
                    });
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            var transfer = fixture.holder.getEnergyTransfer();
            assertTrue(transfer.requiresReconciliation());
            assertEquals("ae_native", transfer.source());
            assertEquals(400, transfer.requestedFe());
            assertEquals(0, fixture.energy);
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            verify(fixture.nativeEnergy, times(1)).extractAEPower(anyDouble(), any(), any());
            assertEquals(420, sourceAe[0]);
            verify(fixture.grid, never()).getService(any());

            var saved = new CompoundTag();
            fixture.holder.savePendingItems(saved);
            var restored = new Ae2OutputStateHolder();
            restored.loadPendingItems(saved);
            assertTrue(restored.getEnergyTransfer().requiresReconciliation());
            assertEquals(transfer.request(), restored.getEnergyTransfer().request());
            restored.getEnergyTransfer().resolve(transfer.request(), BigDecimal.valueOf(160));
            assertEquals(400, restored.getEnergyTransfer().knownJoules());
            fixture.holder = restored;
            when(fixture.host.productivebeesgenesis$getAe2StateHolder()).thenReturn(restored);
            fixture.gridLookup.when(() -> Ae2GridNodeManager.getCachedGrid(fixture.host)).thenReturn(null);
            assertEquals(400, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(400, fixture.energy);
            assertFalse(restored.getEnergyTransfer().isPresent());
        }
    }

    @Test
    void knownFirstSourceIsCreditedBeforeSecondSourceBecomesUnknown() {
        try (var fixture = new Fixture(2.5, true);
             var bridge = mockStatic(Ae2EnergyBridge.class)) {
            when(fixture.host.productivebeesgenesis$getPreferAppliedFluxOverAeEnergy()).thenReturn(true);
            bridge.when(() -> Ae2EnergyBridge.extractAppliedFluxFe(fixture.grid, 400, Actionable.MODULATE, null))
                    .thenReturn(100L);
            bridge.when(() -> Ae2EnergyBridge.extractAeEnergyAsFe(fixture.grid, 300, Actionable.MODULATE))
                    .thenThrow(new IllegalStateException("second source partial failure"));

            assertEquals(250, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(250, fixture.energy);
            assertTrue(fixture.holder.getEnergyTransfer().requiresReconciliation());
            assertEquals("ae_native", fixture.holder.getEnergyTransfer().source());
            assertEquals(300, fixture.holder.getEnergyTransfer().requestedFe());
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            bridge.verify(() -> Ae2EnergyBridge.extractAppliedFluxFe(fixture.grid, 400, Actionable.MODULATE, null));
            bridge.verify(() -> Ae2EnergyBridge.extractAeEnergyAsFe(fixture.grid, 300, Actionable.MODULATE));
            bridge.verifyNoMoreInteractions();
        }
    }

    @Test
    void localMutationThenThrowDoesNotReplayCreditAndFractionalJoulesAreRetained() {
        try (var fixture = new Fixture(2.5, false)) {
            when(fixture.nativeEnergy.extractAEPower(anyDouble(), eq(Actionable.MODULATE), eq(PowerMultiplier.ONE)))
                    .thenReturn(0.1);
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            var transfer = fixture.holder.getEnergyTransfer();
            assertEquals(0, new BigDecimal("0.50").compareTo(new BigDecimal(transfer.retainedJoules())));
            var saved = new CompoundTag();
            fixture.holder.savePendingItems(saved);
            var restored = new Ae2OutputStateHolder();
            restored.loadPendingItems(saved);
            assertEquals(transfer.retainedJoules(), restored.getEnergyTransfer().retainedJoules());

            transfer.beginExtract("applied_flux_fe", 1, 2.5);
            transfer.extracted(1L);
            assertEquals(3, transfer.knownJoules());
            doAnswer(call -> {
                fixture.energy = call.getArgument(0);
                throw new IllegalStateException("container changed before failure");
            }).when(fixture.container).setEnergy(anyLong());
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(3, fixture.energy);
            assertEquals("credit", transfer.phase());
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(3, fixture.energy);
            var interrupted = new CompoundTag();
            fixture.holder.savePendingItems(interrupted);
            restored.loadPendingItems(interrupted);
            restored.getEnergyTransfer().resolve(transfer.request(), BigDecimal.valueOf(3));
            assertFalse(restored.getEnergyTransfer().isPresent());
            verify(fixture.nativeEnergy, times(1)).extractAEPower(anyDouble(), any(), any());
        }
    }

    @Test
    void invalidReceiptsRemainUnknownAndSmallCapacityDoesNotConsumeSource() {
        try (var fixture = new Fixture(2.5, false)) {
            fixture.maximum = 1;
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            verifyNoInteractions(fixture.nativeEnergy);
        }
        try (var fixture = new Fixture(2.5, false)) {
            when(fixture.nativeEnergy.extractAEPower(anyDouble(), eq(Actionable.MODULATE), eq(PowerMultiplier.ONE)))
                    .thenReturn(Double.NaN);
            Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0);
            assertTrue(fixture.holder.getEnergyTransfer().requiresReconciliation());
            assertEquals(0, fixture.energy);
        }
        var transfer = new Ae2EnergyTransfer();
        transfer.beginExtract("applied_flux_fe", 400, 2.5);
        assertThrows(IllegalStateException.class, () -> transfer.extracted(-1L));
        assertThrows(IllegalStateException.class, () -> transfer.extracted(401L));
        assertTrue(transfer.requiresReconciliation());
    }

    @Test
    void fullBatteryDoesNotBackOffBeforeConsumptionAndRefillsEveryBatch() {
        try (var fixture = new Fixture(2.5, false)) {
            fixture.energy = fixture.maximum;
            when(fixture.nativeEnergy.extractAEPower(anyDouble(), eq(Actionable.MODULATE), eq(PowerMultiplier.ONE)))
                    .thenAnswer(call -> call.getArgument(0));
            long consumed = 0;
            long injected = 0;
            for (int tick = 0; tick < 600; tick++) {
                assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 1024, 0));
                assertEquals(0, fixture.holder.getPushState().getEnergyBackoff().getBackoffExponent());
                fixture.energy -= 200;
                consumed += 200;
                injected += Ae2EnergyInjector.injectEnergy(fixture.host, 1024, 0);
                assertEquals(fixture.maximum, fixture.energy);
            }
            assertEquals(consumed, injected);
            verify(fixture.nativeEnergy, times(600)).extractAEPower(eq(40D),
                    eq(Actionable.MODULATE), eq(PowerMultiplier.ONE));
            assertFalse(fixture.holder.getEnergyTransfer().isPresent());
        }
    }

    @Test
    void satisfiedBatchAndSubFeCapacityDoNotScheduleFailureBackoff() {
        try (var fixture = new Fixture(2.5, false)) {
            fixture.energy = 500;
            when(fixture.host.productivebeesgenesis$usesSmeltingEnergyBudget()).thenReturn(true);
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 1024, 500));
            assertEquals(0, fixture.holder.getPushState().getEnergyBackoff().getBackoffExponent());
            fixture.energy = 999;
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(0, fixture.holder.getPushState().getEnergyBackoff().getBackoffExponent());
            fixture.gridLookup.verifyNoInteractions();
            verifyNoInteractions(fixture.nativeEnergy);
        }
    }

    @Test
    void positiveFractionalReceiptKeepsSupplyingUntilAnIntegerJouleCanBeCredited() {
        try (var fixture = new Fixture(2.5, false)) {
            when(fixture.nativeEnergy.extractAEPower(anyDouble(), eq(Actionable.MODULATE), eq(PowerMultiplier.ONE)))
                    .thenReturn(0.1D);
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(0, fixture.holder.getPushState().getEnergyBackoff().getBackoffExponent());
            assertEquals(1, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(1, fixture.energy);
            assertFalse(fixture.holder.getEnergyTransfer().isPresent());
            verify(fixture.nativeEnergy, times(2)).extractAEPower(anyDouble(), any(), any());
        }
    }

    @Test
    void realEmptySourceStillBacksOffWithoutCreatingOrLosingEnergy() {
        try (var fixture = new Fixture(2.5, false)) {
            assertEquals(0, Ae2EnergyInjector.injectEnergy(fixture.host, 0, 0));
            assertEquals(1, fixture.holder.getPushState().getEnergyBackoff().getBackoffExponent());
            assertTrue(fixture.holder.getPushState().getEnergyBackoff().getBackoffEndNanos() > 0);
            assertEquals(0, fixture.energy);
            assertFalse(fixture.holder.getEnergyTransfer().isPresent());
            verify(fixture.nativeEnergy).extractAEPower(eq(200D),
                    eq(Actionable.MODULATE), eq(PowerMultiplier.ONE));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final IAe2OutputHostBase host = mock(IAe2OutputHostBase.class);
        final MachineEnergyContainer<?> container = mock(MachineEnergyContainer.class);
        final IGrid grid = mock(IGrid.class);
        final IEnergyService nativeEnergy = mock(IEnergyService.class);
        Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
        long energy;
        long maximum = 1_000;
        final double previousConversion = MekanismConfig.general.forgeConversionRate.get();
        final MockedStatic<Ae2IntegrationLoader> ae = mockStatic(Ae2IntegrationLoader.class);
        final MockedStatic<AppliedFluxIntegrationLoader> flux = mockStatic(AppliedFluxIntegrationLoader.class);
        final MockedStatic<Ae2GridNodeManager> gridLookup = mockStatic(Ae2GridNodeManager.class);

        Fixture(double conversion, boolean fluxAvailable) {
            MekanismConfig.general.forgeConversionRate.set(conversion);
            ae.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(true);
            flux.when(AppliedFluxIntegrationLoader::isAppliedFluxLoaded).thenReturn(fluxAvailable);
            gridLookup.when(() -> Ae2GridNodeManager.getCachedGrid(host)).thenReturn(grid);
            doReturn(container).when(host).productivebeesgenesis$getAe2EnergySource();
            when(host.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
            when(host.productivebeesgenesis$isAeNativeEnergyInputEnabled()).thenReturn(true);
            when(grid.getEnergyService()).thenReturn(nativeEnergy);
            when(container.getEnergy()).thenAnswer(call -> energy);
            when(container.getMaxEnergy()).thenAnswer(call -> maximum);
            doAnswer(call -> {
                energy = call.getArgument(0);
                return null;
            }).when(container).setEnergy(anyLong());
        }

        @Override
        public void close() {
            gridLookup.close();
            flux.close();
            ae.close();
            MekanismConfig.general.forgeConversionRate.set(previousConversion);
        }
    }
}
