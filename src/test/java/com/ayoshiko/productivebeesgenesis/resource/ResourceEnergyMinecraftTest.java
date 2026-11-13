package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceEnergyMinecraftTest {
	private static final ResourceKey FE = ResourceTypes.FE;

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
