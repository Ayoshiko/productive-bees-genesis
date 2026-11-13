package com.ayoshiko.productivebeesgenesis.resource;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.account;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceLightningExperienceMinecraftTest {
	private static final ResourceKey XP = ResourceTypes.EXPERIENCE;
	private static final ResourceKey HV = ResourceTypes.HIGH_VOLTAGE;
	private static final ResourceKey EHV = ResourceTypes.EXTREME_HIGH_VOLTAGE;
	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

	private static ResourceAccount produce(ResourceAccounts store, Map<ResourceKey, Long> amounts) {
		var account = account(store);
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
		account.start(ticket); account.freeze(ticket, amounts); account.commit(ticket);
		return account;
	}
	private static ResourceScalarOutput view(ResourceAccount account, ResourceKey key) {
		var port = mock(ResourceOutputAccess.class);
		when(port.outputAllowed(any())).thenReturn(true);
		when(port.account()).thenReturn(account);
		when(port.reference()).thenReturn(account.reference());
		when(port.capacity()).thenReturn(Long.MAX_VALUE);
		return new ResourceScalarOutput(port, key);
	}

	@Test void missingUnknownAndInvalidWireTiersAreNeverDefaulted() {
		for (String fields : List.of("", "\"tier\":\"unknown\",", "\"tier\":\"HIGH_VOLTAGE\",")) {
			var json = JsonParser.parseString("{\"bee\":\"example:charged\",\"outputs\":[{\"kind\":\"lightning\","
					+ fields + "\"amount\":\"1\"}]}");
			assertTrue(ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, json).error().isPresent());
		}
		assertThrows(IllegalArgumentException.class, () -> ResourceKey.lightning("unknown"));
		assertFalse(ResourceTypes.declared(new ResourceKey(HV.kind(), 2, HV.payload())));
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
		try {
			buffer.writeByte(8).writeByte(2);
			assertThrows(IllegalArgumentException.class, () -> ResourceOutputCodecs.read(buffer));
		} finally { buffer.release(); }
	}

	@Test void voltageViewsAreIndependentAndPendingTransfersCannotBeSpentTwice() {
		var mods = mock(net.neoforged.fml.ModList.class);
		when(mods.isLoaded("ae2lt")).thenReturn(true);
		when(mods.isLoaded("experiencelib")).thenReturn(true);
		try (var modList = mockStatic(net.neoforged.fml.ModList.class)) {
			modList.when(net.neoforged.fml.ModList::get).thenReturn(mods);
			var store = new ResourceAccounts(() -> {});
			var account = produce(store, Map.of(XP, Long.MAX_VALUE, HV, 100L, EHV, 200L));
			var high = view(account, HV);
			assertEquals(13, high.extract(13, true));
			assertEquals(13, high.extract(13, false));
			assertEquals(200, view(account, EHV).stored());
			assertEquals(Long.MAX_VALUE, view(account, XP).extract(Long.MAX_VALUE, true));
			assertThrows(IllegalStateException.class, () -> account.transfer(account.reference(), HV, 17, ticket -> {
				assertEquals(0, high.extract(Long.MAX_VALUE, false));
				throw new IllegalStateException("receiver outcome unknown");
			}));
			assertEquals(0, high.stored());
			var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
			assertNotNull(restored.transferTicket());
			assertEquals(200L, restored.available().get(EHV));
			high.invalidate();
			assertEquals(0, high.extract(1, false));
		}
	}
}
