package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
class ResourceSoulMinecraftTest {
	private static final ResourceKey SOUL = ResourceTypes.SOUL;
	private static final ResourceLocation BEE = ResourceLocation.parse("example:soul");

	private static ResourceScalarOutput output(ResourceAccount account, ResourceKey key) {
		var port = mock(ResourceOutputAccess.class);
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

	@Test void soulDefinitionKeepsLongMaxThroughJsonAndFourTypeWire() {
		var json = JsonParser.parseString("""
				{"bee":"example:soul","outputs":[{"kind":"soul","amount":"9223372036854775807"}]}
				""");
		var recipe = ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(new ResourceProductionRecipe.Output(SOUL, Long.MAX_VALUE, 1), recipe.outputs().getFirst());
		var encoded = ResourceProductionRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
		assertEquals(recipe, ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
		var mixed = new ResourceProductionRecipe(BEE, List.of(recipe.outputs().getFirst(),
				new ResourceProductionRecipe.Output(ResourceTypes.FE, 91, 1),
				new ResourceProductionRecipe.Output(ResourceTypes.SOURCE, 23, 1),
				new ResourceProductionRecipe.Output(HYDROGEN, 17, 1)));
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		try {
			ResourceProductionRecipe.STREAM_CODEC.encode(buffer, mixed);
			assertEquals(mixed, ResourceProductionRecipe.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
	}

	@Test void wrongVersionOrPayloadCannotBeReinterpretedAsSoul() {
		var payload = new CompoundTag();
		payload.putString("entity", "minecraft:warden");
		for (var key : List.of(new ResourceKey(SOUL.kind(), 2, SOUL.payload()),
				new ResourceKey(SOUL.kind(), 1, payload))) {
			assertFalse(ResourceTypes.declared(key));
			assertFalse(ResourceTypes.available(key));
			assertThrows(IllegalArgumentException.class, () -> new ResourceProductionRecipe.Output(key, 100, 1));
		}
		assertTrue(ResourceTypes.declared(SOUL));
		assertEquals(new CompoundTag(), SOUL.payload());
	}

	@Test void fourResourceTypesKeepTheirOwnUnitsThroughCombPortions() {
		var recipe = new ResourceCentrifugeRecipe(BEE, List.of(
				new ResourceProductionRecipe.Output(SOUL, 3_000_000_007L, 1),
				new ResourceProductionRecipe.Output(ResourceTypes.FE, 13, 1),
				new ResourceProductionRecipe.Output(ResourceTypes.SOURCE, 23, 1),
				new ResourceProductionRecipe.Output(HYDROGEN, 17, 1)), 20);
		var plan = ResourceProductionApi.planCentrifuging(recipe, 2, 0, 0);
		assertEquals(Map.of(SOUL, 24_000_000_056L, ResourceTypes.FE, 104L, ResourceTypes.SOURCE, 184L, HYDROGEN, 136L), plan.sample(4));
		assertEquals(plan.maximum(4), plan.sample(4));
	}

	@Test void missingSoulsModPreservesRawBalancesAndBlocksNativeExtraction() {
		assertFalse(net.neoforged.fml.ModList.get().isLoaded("industrialforegoingsouls"));
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var unknown = new ResourceKey(SOUL.kind(), 7, SOUL.payload());
		produce(account, Map.of(SOUL, Long.MAX_VALUE, unknown, 731L, ResourceTypes.FE, 100L));
		var source = output(account, SOUL);
		var before = store.save();
		assertFalse(ResourceTypes.available(SOUL));
		assertFalse(source.canExtract());
		assertEquals(0, source.stored());
		assertEquals(0, source.extract(Long.MAX_VALUE, true));
		assertEquals(0, source.extract(Long.MAX_VALUE, false));
		assertEquals(before, store.save());
		assertEquals(100, output(account, ResourceTypes.FE).extract(100, false));
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(Map.of(SOUL, Long.MAX_VALUE, unknown, 731L), restored.available());
	}

	@Test void hostApiDoesNotOfferMissingOrUnknownResourcesToExternalReceivers() {
		assertFalse(ResourceTypes.available(SOUL));
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
		var unknown = new ResourceKey(SOUL.kind(), 9, SOUL.payload());
		produce(account, Map.of(SOUL, 100L, unknown, 200L, ResourceTypes.FE, 300L));
		var state = mock(ResourceHiveHost.class);
		when(state.account(host)).thenReturn(account);
		when(state.reference()).thenReturn(account.reference());
		try (var hosts = mockStatic(ResourceHiveHost.class)) {
			hosts.when(() -> ResourceHiveHost.supports(host)).thenReturn(true);
			hosts.when(() -> ResourceHiveHost.ready(host)).thenReturn(true);
			hosts.when(() -> ResourceHiveHost.existing(host)).thenReturn(state);
			var before = store.save();
			for (var key : List.of(SOUL, unknown)) {
				assertEquals(0, ResourceHostApi.transfer(host, key, Long.MAX_VALUE, offer -> {
					fail("Unavailable resource reached external receiver");
					return offer.amount();
				}));
			}
			assertEquals(before, store.save());
			assertEquals(13, ResourceHostApi.transfer(host, ResourceTypes.FE, 100, offer -> 13));
			assertEquals(Map.of(SOUL, 100L, unknown, 200L, ResourceTypes.FE, 287L), account.available());
		}
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
