package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceAirMinecraftTest {
	@Test void uncertainNativeAirSurvivesRestartAndRestoresOnlyTheVerifiedRemainder() {
		for (boolean throwsAfterAccepting : new boolean[]{false, true}) {
			var store = new ResourceAccounts(() -> {});
			var account = account(store);
			var amounts = Map.of(ResourceTypes.AIR, 9007199254740993L);
			var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
			account.start(ticket); account.freeze(ticket, amounts); account.commit(ticket);
			var received = new AtomicInteger();
			assertThrows(IllegalStateException.class, () -> account.transfer(account.reference(), ResourceTypes.AIR, 100,
					offer -> ResourceAirTransfer.deliver(0, 100, received::get, amount -> {
						received.addAndGet(13);
						if (throwsAfterAccepting) throw new IllegalStateException("after acceptance");
					})));
			assertEquals(100, account.uncertainTransfer().get(ResourceTypes.AIR));
			assertEquals(9007199254740893L, account.available().get(ResourceTypes.AIR));
			var loaded = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
			assertFalse(loaded.canExtract(loaded.reference(), ResourceTypes.AIR));
			assertEquals(100, loaded.uncertainTransfer().get(ResourceTypes.AIR));
			loaded.resolveTransfer(OWNER, loaded.transferTicket(), received.get());
			assertEquals(9007199254740980L, loaded.available().get(ResourceTypes.AIR));
			assertTrue(loaded.canExtract(loaded.reference(), ResourceTypes.AIR));
		}
	}
}
