package com.ayoshiko.productivebeesgenesis.network;

import appeng.api.config.Actionable;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2PendingItemBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2PlayerTransferMinecraftTest {
	@BeforeAll
	static void setup() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void shutdown() {
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	private static AEItemKey select(MachineMenuAccessMinecraftTest.Fixture fixture, long amount) {
		PayloadRateLimiter.clear(fixture.player.getUUID());
		fixture.player.connection = mock(ServerGamePacketListenerImpl.class);
		AEItemKey key = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
		var filter = fixture.tile.productivebeesgenesis$getAe2StateHolder().getOrCreateInputFilter();
		filter.setDirectEntryFingerprintAt(0, Ae2ItemFingerprint.encode(key, fixture.registries));
		filter.resolveDirectKey(0, key);
		filter.setDirectAmountAt(0, amount);
		return key;
	}

	@Test
	void rightClickWithZeroAllowanceDoesNotModulateStock() {
		try (var fixture = new MachineMenuAccessMinecraftTest.Fixture();
				var grid = mockStatic(Ae2GridNodeManager.class)) {
			var key = select(fixture, 0);
			var holder = fixture.tile.productivebeesgenesis$getAe2StateHolder();
			var storage = mock(MEStorage.class);
			grid.when(() -> Ae2GridNodeManager.getCachedStorage(holder, fixture.tile)).thenReturn(mock(IStorageService.class));
			grid.when(() -> Ae2GridNodeManager.getCachedMeStorage(holder, fixture.tile)).thenReturn(storage);
			when(storage.extract(eq(key), eq(Long.MAX_VALUE), eq(Actionable.SIMULATE), any())).thenReturn(100L);
			Ae2PayloadHandlers.handleAeInputOutputSlot(
					new AeInputOutputSlotPayload(BlockPos.ZERO, 0, true, false), fixture.context);
			verify(storage, never()).extract(any(), anyLong(), eq(Actionable.MODULATE), any());
			assertTrue(fixture.menu.getCarried().isEmpty());
			assertFalse(holder.getPendingItemBuffer().isBlocked());
		}
	}

	@Test
	void insertionWithUnknownPrefixRetainsPlayerRequestAndNeverRestoresTheWholeCursor() {
		try (var fixture = new MachineMenuAccessMinecraftTest.Fixture();
				var grid = mockStatic(Ae2GridNodeManager.class)) {
			var key = select(fixture, 64);
			var holder = fixture.tile.productivebeesgenesis$getAe2StateHolder();
			var storage = mock(MEStorage.class);
			grid.when(() -> Ae2GridNodeManager.getCachedStorage(holder, fixture.tile)).thenReturn(mock(IStorageService.class));
			grid.when(() -> Ae2GridNodeManager.getCachedMeStorage(holder, fixture.tile)).thenReturn(storage);
			fixture.menu.setCarried(key.toStack(10));
			long[] accepted = {0};
			when(storage.insert(eq(key), eq(10L), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
				accepted[0] = 3;
				throw new IllegalStateException("accepted three before failure");
			});
			var payload = new AeInputOutputSlotPayload(BlockPos.ZERO, 0, false, false);
			Ae2PayloadHandlers.handleAeInputOutputSlot(payload, fixture.context);
			assertEquals(3, accepted[0]);
			assertTrue(fixture.menu.getCarried().isEmpty());
			assertTrue(holder.getPendingItemBuffer().isBlocked());
			var transfer = holder.getPendingItemBuffer().transfer();
			assertEquals(10, transfer.offeredAmount());
			assertTrue(transfer.destination().contains(fixture.player.getUUID().toString()));
			PayloadRateLimiter.clear(fixture.player.getUUID());
			Ae2PayloadHandlers.handleAeInputOutputSlot(payload, fixture.context);
			verify(storage, times(1)).insert(eq(key), eq(10L), eq(Actionable.MODULATE), any());
			var saved = new CompoundTag();
			holder.getPendingItemBuffer().save(saved);
			var recovered = new Ae2PendingItemBuffer();
			recovered.load(saved);
			assertEquals(transfer.destination(), recovered.transfer().destination());
			recovered.transfer().resolve(recovered.transfer().request(), 3, recovered, 0);
			assertEquals(7, recovered.getTotalAmount());
		}
	}
}
