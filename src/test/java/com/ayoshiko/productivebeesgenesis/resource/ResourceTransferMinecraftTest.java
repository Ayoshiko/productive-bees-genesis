package com.ayoshiko.productivebeesgenesis.resource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceTransferMinecraftTest {
	@Test void knownRefusalRestoresBalanceWithoutSuspension() {
		var account = account(new ResourceAccounts(() -> {}));
		produce(account, 100);
		assertEquals(0, account.transfer(account.reference(), HYDROGEN, 100, offer -> 0));
		assertEquals(100, account.available().get(HYDROGEN));
		assertTrue(account.canExtract(account.reference()));
	}


	@Test void oneUncertainResourceDoesNotBlockOtherResourcesAndKeepsExactRecovery() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 100);
		assertThrows(IllegalStateException.class, () -> account.transfer(account.reference(), HYDROGEN, 60,
				ticket -> { throw new IllegalStateException("unknown result"); }));
		var uncertain = account.transferTicket();
		assertFalse(account.canExtract(account.reference(), HYDROGEN));
		assertTrue(account.canExtract(account.reference(), ResourceTypes.FE));
		var production = account.reserve(account.reference(), 1, new net.minecraft.nbt.CompoundTag(), java.util.Map.of(ResourceTypes.FE, 90L));
		account.start(production);
		account.freeze(production, java.util.Map.of(ResourceTypes.FE, 90L));
		account.commit(production);
		assertEquals(70, account.transfer(account.reference(), ResourceTypes.FE, 70, offer -> 70));
		var loaded = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(loaded.hasInvalidData());
		var restored = loaded.find(account.reference().sourceId());
		assertEquals(60, restored.uncertainTransfer().get(HYDROGEN));
		assertEquals(40, restored.available().get(HYDROGEN));
		assertEquals(20, restored.available().get(ResourceTypes.FE));
		assertThrows(IllegalStateException.class, () -> restored.transfer(restored.reference(), HYDROGEN, 1, offer -> 0));
		assertEquals(20, restored.transfer(restored.reference(), ResourceTypes.FE, 20, offer -> 20));
		restored.resolveTransfer(OWNER, uncertain, 25);
		assertEquals(75, restored.available().get(HYDROGEN));
		assertTrue(restored.uncertainTransfer().isEmpty());
		assertThrows(IllegalStateException.class, () -> restored.resolveTransfer(OWNER, uncertain, 25));
	}

	@Test void exceptionAfterExternalAcceptanceIsFrozenAcrossSaveAndLoad() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 100);
		var accepted = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> account.transfer(account.reference(), HYDROGEN, 100, offer -> {
			accepted.set(13);
			throw new IllegalStateException("receiver failed after accepting");
		}));
		assertEquals(13, accepted.get());
		assertTrue(account.available().isEmpty());
		assertEquals(100, account.uncertainTransfer().get(HYDROGEN));
		assertFalse(account.canExtract(account.reference(), HYDROGEN));
		var restored = ResourceAccounts.load(store.save(), () -> {});
		var loaded = restored.find(account.reference().sourceId());
		assertEquals(100, loaded.uncertainTransfer().get(HYDROGEN));
		assertFalse(loaded.canExtract(loaded.reference(), HYDROGEN));
		loaded.resolveTransfer(OWNER, loaded.transferTicket(), 13);
		assertEquals(87, loaded.available().get(HYDROGEN));
	}

}
