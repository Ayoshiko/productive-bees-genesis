package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceSourceMinecraftTest {
	private static final ResourceKey SOURCE = ResourceTypes.SOURCE;
	private static ResourceScalarOutput output(ResourceAccount account, ResourceKey key) {
		var port = mock(ResourceOutputAccess.class);
		when(port.outputAllowed(any())).thenReturn(true);
		when(port.account()).thenReturn(account);
		when(port.reference()).thenReturn(account.reference());
		when(port.capacity()).thenReturn(Long.MAX_VALUE);
		return new ResourceScalarOutput(port, key);
	}

	private static void produce(ResourceAccount account, Map<ResourceKey, Long> amounts) {
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
		account.start(ticket);
		account.freeze(ticket, amounts);
		account.commit(ticket);
	}

	@Test void missingArsPreservesRawBalancesAndBlocksNativeExtraction() {
		assertFalse(net.neoforged.fml.ModList.get().isLoaded("ars_nouveau"));
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var unknown = new ResourceKey(SOURCE.kind(), 7, SOURCE.payload());
		produce(account, Map.of(SOURCE, Long.MAX_VALUE, unknown, 731L, ResourceTypes.FE, 100L));
		var source = output(account, SOURCE);
		var before = store.save();
		assertFalse(ResourceTypes.available(SOURCE));
		assertFalse(source.canExtract());
		assertEquals(0, source.stored());
		assertEquals(0, source.extract(Long.MAX_VALUE, true));
		assertEquals(0, source.extract(Long.MAX_VALUE, false));
		assertEquals(before, store.save());
		assertEquals(100, output(account, ResourceTypes.FE).extract(100, false));
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(Map.of(SOURCE, Long.MAX_VALUE, unknown, 731L), restored.available());
	}

	@Test void hostApiDoesNotOfferMissingOrUnknownResourcesToExternalReceivers() {
		assertFalse(ResourceTypes.available(SOURCE));
		var level = mock(ServerLevel.class);
		var server = mock(MinecraftServer.class);
		var chunks = mock(ServerChunkCache.class);
		var host = mock(BlockEntity.class);
		when(host.getLevel()).thenReturn(level);
		when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
		when(level.getServer()).thenReturn(server);
		when(server.isSameThread()).thenReturn(true);
		when(level.getChunkSource()).thenReturn(chunks);
		when(chunks.hasChunk(0, 0)).thenReturn(true);
		when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(host);
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var unknown = new ResourceKey(SOURCE.kind(), 9, SOURCE.payload());
		produce(account, Map.of(SOURCE, 100L, unknown, 200L, ResourceTypes.FE, 300L));
		var state = mock(ResourceHiveHost.class);
		when(state.account(host)).thenReturn(account);
		when(state.reference()).thenReturn(account.reference());
		try (var hosts = mockStatic(ResourceHiveHost.class)) {
			hosts.when(() -> ResourceHiveHost.supports(host)).thenReturn(true);
			hosts.when(() -> ResourceHiveHost.ready(host)).thenReturn(true);
			hosts.when(() -> ResourceHiveHost.existing(host)).thenReturn(state);
			var before = store.save();
			for (var key : List.of(SOURCE, unknown)) {
				assertEquals(0, ResourceHostApi.transfer(host, key, Long.MAX_VALUE, offer -> {
					fail("Unavailable resource reached external receiver");
					return offer.amount();
				}));
			}
			assertEquals(before, store.save());
			assertEquals(13, ResourceHostApi.transfer(host, ResourceTypes.FE, 100, offer -> 13));
			assertEquals(Map.of(SOURCE, 100L, unknown, 200L, ResourceTypes.FE, 287L), account.available());
		}
	}
}
