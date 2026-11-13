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

import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceSoulMinecraftTest {
	private static final ResourceKey SOUL = ResourceTypes.SOUL;
	private static void produce(ResourceAccount account, Map<ResourceKey, Long> amounts) {
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
		account.start(ticket);
		account.freeze(ticket, amounts);
		account.commit(ticket);
	}

	@Test void soulTransferIsSegmentedBeforeAnExternalIntCastAndKeepsLongRemainder() {
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
		long initial = (long) Integer.MAX_VALUE + 12345;
		produce(account, Map.of(SOUL, initial, ResourceTypes.FE, initial));
		var state = mock(ResourceHiveHost.class);
		when(state.account(host)).thenReturn(account);
		when(state.reference()).thenReturn(account.reference());
		var mods = mock(net.neoforged.fml.ModList.class);
		when(mods.isLoaded("industrialforegoingsouls")).thenReturn(true);
		try (var hosts = mockStatic(ResourceHiveHost.class);
				var modList = mockStatic(net.neoforged.fml.ModList.class)) {
			modList.when(net.neoforged.fml.ModList::get).thenReturn(mods);
			hosts.when(() -> ResourceHiveHost.supports(host)).thenReturn(true);
			hosts.when(() -> ResourceHiveHost.ready(host)).thenReturn(true);
			hosts.when(() -> ResourceHiveHost.existing(host)).thenReturn(state);
			assertEquals(Integer.MAX_VALUE, ResourceHostApi.transfer(host, SOUL, Long.MAX_VALUE, offer -> {
				assertEquals(Integer.MAX_VALUE, offer.amount());
				return (int) offer.amount();
			}));
			assertEquals(12345, account.available().get(SOUL));
			assertEquals(12345, ResourceHostApi.transfer(host, SOUL, Long.MAX_VALUE, offer -> (int) offer.amount()));
			assertFalse(account.available().containsKey(SOUL));
			assertEquals(initial, ResourceHostApi.transfer(host, ResourceTypes.FE, Long.MAX_VALUE, offer -> {
				assertEquals(initial, offer.amount());
				return offer.amount();
			}));
			assertTrue(account.available().isEmpty());
		}
	}
}
