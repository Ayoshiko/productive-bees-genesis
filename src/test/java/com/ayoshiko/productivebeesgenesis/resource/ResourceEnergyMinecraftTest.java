package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.common.config.MekanismConfig;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceEnergyMinecraftTest {
	private static final ResourceKey FE = ResourceTypes.FE;

    @BeforeAll
    static void loadConfigs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unloadConfigs() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

	private static ResourceEnergyOutput output(ResourceAccount account) {
		var port = mock(ResourceOutputAccess.class);
		when(port.account()).thenReturn(account);
		when(port.reference()).thenReturn(account.reference());
		when(port.capacity()).thenReturn(Long.MAX_VALUE);
		when(port.outputAllowed(ResourceNativeType.ENERGY)).thenReturn(true);
		return new ResourceEnergyOutput(port);
	}

	private static void produce(ResourceAccount account, Map<ResourceKey, Long> amounts) {
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
		account.start(ticket);
		account.freeze(ticket, amounts);
		account.commit(ticket);
	}

	@Test void nativeIntSegmentsKeepLongRemainderAndSimulationCannotSpend() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		long amount = (long) Integer.MAX_VALUE + 12345;
		produce(account, Map.of(FE, amount));
		var output = output(account);
		var before = store.save();
		assertEquals(Integer.MAX_VALUE, output.getEnergyStored());
		assertEquals(Integer.MAX_VALUE, output.getMaxEnergyStored());
		assertEquals(Integer.MAX_VALUE, output.extractEnergy(Integer.MAX_VALUE, true));
		assertEquals(0, output.receiveEnergy(Integer.MAX_VALUE, false));
		assertFalse(output.canReceive());
		assertEquals(before, store.save());
		assertEquals(Integer.MAX_VALUE, output.extractEnergy(Integer.MAX_VALUE, false));
		assertEquals(12345, output.getEnergyStored());
		assertEquals(12345, output.extractEnergy(Integer.MAX_VALUE, false));
		assertTrue(account.available().isEmpty());
		assertEquals(0, output.extractEnergy(-1, false));
	}

    @Test
    void strictOutputKeepsPaidRemaindersAcrossReloadAndRebinding() {
        double previous = MekanismConfig.general.forgeConversionRate.get();
        try {
            MekanismConfig.general.forgeConversionRate.set(2.5);
            var store = new ResourceAccounts(() -> {});
            var account = account(store);
            produce(account, Map.of(FE, 1L));
            var output = strictOutput(account);
            var before = store.save();
            assertEquals(1, output.extractEnergy(0, 1, Action.SIMULATE));
            assertEquals(before, store.save());
            assertEquals(1, output.extractEnergy(0, 1, Action.EXECUTE));
            assertTrue(account.available().isEmpty());
            assertEquals(0, new BigDecimal("1.5").compareTo(account.retainedEnergyJoules));
            var restoredStore = ResourceAccounts.load(store.save(), () -> {});
            assertFalse(restoredStore.hasInvalidData());
            var restored = restoredStore.find(account.reference().sourceId());
            var replacement = strictOutput(restored);
            assertEquals(1, replacement.extractEnergy(0, 1, Action.EXECUTE));
            assertEquals(0, new BigDecimal("0.5").compareTo(restored.retainedEnergyJoules));
            var binding = restored.binding();
            var retired = restored.retire(restored.reference(), binding);
            assertFalse(restoredStore.retireEmpty(retired));
            restored.claim(OWNER, retired, binding);
            produce(restored, Map.of(FE, 1L));
            assertEquals(3, strictOutput(restored).extractEnergy(0, Long.MAX_VALUE, Action.EXECUTE));
            assertTrue(restored.available().isEmpty());
            assertEquals(0, restored.retainedEnergyJoules.signum());
        } finally {
            MekanismConfig.general.forgeConversionRate.set(previous);
        }
    }

    @Test
    void strictOutputDoesNotSpendSubJouleBalancesAndCustomRatiosSupplySmallRequests() {
        double previous = MekanismConfig.general.forgeConversionRate.get();
        try {
            MekanismConfig.general.forgeConversionRate.set(0.1);
            var store = new ResourceAccounts(() -> {});
            var account = account(store);
            produce(account, Map.of(FE, 1L));
            var output = strictOutput(account);
            var before = store.save();
            assertEquals(0, output.extractEnergy(0, Long.MAX_VALUE, Action.EXECUTE));
            assertEquals(before, store.save());
            produce(account, Map.of(FE, 9L));
            assertEquals(1, output.extractEnergy(0, Long.MAX_VALUE, Action.EXECUTE));
            assertTrue(account.available().isEmpty());
            MekanismConfig.general.forgeConversionRate.set(Math.nextUp(2.5));
            produce(account, Map.of(FE, 2L));
            assertEquals(5, output.extractEnergy(0, 5, Action.EXECUTE));
            BigDecimal expected = BigDecimal.valueOf(Math.nextUp(2.5)).multiply(BigDecimal.valueOf(2))
                    .subtract(BigDecimal.valueOf(5));
            assertEquals(0, expected.compareTo(account.retainedEnergyJoules));
            var restoredStore = ResourceAccounts.load(store.save(), () -> {});
            assertFalse(restoredStore.hasInvalidData());
            assertEquals(0, expected.compareTo(restoredStore.find(account.reference().sourceId()).retainedEnergyJoules));
        } finally {
            MekanismConfig.general.forgeConversionRate.set(previous);
        }
    }

    @Test
    void malformedSavedEnergyRemainderPreservesTheOriginalAccountData() {
        var store = new ResourceAccounts(() -> {});
        account(store);
        var saved = store.save();
        saved.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
                .putString("fe_output_joules", "1E-100000000");
        var restored = ResourceAccounts.load(saved, () -> {});
        assertTrue(restored.hasInvalidData());
        assertEquals(saved, restored.save());
    }

    @Test
    void strictOutputPreservesExactLargeEnergyAcrossSegmentedExtraction() {
        double previous = MekanismConfig.general.forgeConversionRate.get();
        try {
            MekanismConfig.general.forgeConversionRate.set(2.5);
            var store = new ResourceAccounts(() -> {});
            var account = account(store);
            produce(account, Map.of(FE, Long.MAX_VALUE));
            var output = strictOutput(account);
            for (long request : new long[]{9_007_199_254_740_993L, Long.MAX_VALUE}) {
                long beforeFe = account.available().get(FE);
                BigDecimal beforeJoules = account.retainedEnergyJoules;
                var beforeSave = store.save();
                long simulated = output.extractEnergy(0, request, Action.SIMULATE);
                assertEquals(beforeSave, store.save());
                long deliveredJ = output.extractEnergy(0, request, Action.EXECUTE);
                long spentFe = beforeFe - account.available().getOrDefault(FE, 0L);
                assertEquals(simulated, deliveredJ);
                assertTrue(deliveredJ <= request);
                assertEquals(0, BigDecimal.valueOf(spentFe).multiply(new BigDecimal("2.5"))
                        .add(beforeJoules).compareTo(BigDecimal.valueOf(deliveredJ).add(account.retainedEnergyJoules)));
            }
        } finally {
            MekanismConfig.general.forgeConversionRate.set(previous);
        }
    }

    private static ResourceStrictEnergyOutput strictOutput(ResourceAccount account) {
        var port = mock(ResourceOutputAccess.class);
        when(port.account()).thenReturn(account);
        when(port.reference()).thenReturn(account.reference());
        when(port.capacity()).thenReturn(Long.MAX_VALUE);
        when(port.outputAllowed(ResourceNativeType.ENERGY)).thenReturn(true);
        return new ResourceStrictEnergyOutput(port);
    }

	@Test void unknownEnergyAndChemicalsStayInTheirOwnAccounts() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var unknown = new ResourceKey(FE.kind(), 7, FE.payload());
		var otherPayload = FE.payload();
		otherPayload.putString("energy_type", "GTEU");
		var other = new ResourceKey(FE.kind(), 1, otherPayload);
		produce(account, Map.of(FE, 100L, HYDROGEN, 200L, unknown, 300L, other, 400L));
		assertEquals(100, output(account).extractEnergy(1000, false));
		assertEquals(Map.of(HYDROGEN, 200L, unknown, 300L, other, 400L), account.available());
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(account.available(), restored.available());
		assertEquals(0, output(restored).extractEnergy(1000, false));
	}

	@Test void uncertainMeTransferAndInvalidatedNativeHandleCannotDoubleSpend() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, Map.of(FE, 100L));
		var output = output(account);
		var ticket = account.beginTransfer(account.reference(), FE, 100);
		assertFalse(output.canExtract());
		assertEquals(0, output.extractEnergy(100, true));
		assertEquals(0, output.extractEnergy(100, false));
		account.suspendTransfer(ticket, "Unknown FE receiver");
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(0, output(restored).extractEnergy(100, false));
		restored.resolveTransfer(OWNER, restored.transferTicket(), 13);
		assertEquals(87, output(restored).extractEnergy(100, false));
		account.resolveTransfer(OWNER, ticket, 13);
		output.invalidate();
		assertEquals(0, output.extractEnergy(100, false));
		assertEquals(87, account.available().get(FE));
	}
}
