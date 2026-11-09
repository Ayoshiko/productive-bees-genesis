package com.ayoshiko.productivebeesgenesis.resource;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceDisplayMinecraftTest {
	@Test
	void healthyUnboundAttachmentIsDistinctFromInvalidSavedData() {
		var level = mock(ServerLevel.class);
		var host = mock(BlockEntity.class);
		var player = mock(ServerPlayer.class);
		var state = new ResourceHiveHost();
		when(host.getLevel()).thenReturn(level);
		when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
		when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(host);
		when(player.serverLevel()).thenReturn(level);
		when(player.canInteractWithBlock(BlockPos.ZERO, 4)).thenReturn(true);
		try (var hosts = mockStatic(ResourceHiveHost.class, CALLS_REAL_METHODS)) {
			hosts.when(() -> ResourceHiveHost.existing(host)).thenReturn(state);
			hosts.when(() -> ResourceHiveHost.canAccess(player, host)).thenReturn(true);
			assertEquals(ResourceOutputStatus.State.UNBOUND, ResourceOutputStatus.capture(host, player).state());
			var invalid = new CompoundTag();
			invalid.putString("version", "invalid version type");
			state.deserializeNBT(REGISTRIES, invalid);
			assertEquals(ResourceOutputStatus.State.UNAVAILABLE, ResourceOutputStatus.capture(host, player).state());
			assertEquals(invalid, state.serializeNBT(REGISTRIES));
		}
	}


	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	private static final ResourceLocation BEE = ResourceLocation.parse("example:display");
	private static ResourceRecipeView view(String id, long amount) {
		return new ResourceRecipeView(ResourceLocation.parse("example:" + id), BEE,
				List.of(new ResourceProductionRecipe.Output(ResourceTypes.FE, amount, .25)), 0);
	}
	@Test void wirePreservesFullKeysLongAmountsAndProbabilities() {
		var data = new CompoundTag(); data.putString("resource", "test_resources:echo");
		var key = ResourceKey.aeKey(ResourceLocation.parse("test_resources:digitalization"), ResourceLocation.parse("test_resources:echo"), data);
		var recipe = new ResourceRecipeView(BEE, BEE, List.of(new ResourceProductionRecipe.Output(key, Long.MAX_VALUE, .125)), 123);
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
		try {
			var page = new ResourceCatalogSync.Page(42, 0, 1, recipe);
			ResourceCatalogSync.Page.CODEC.encode(buffer, page);
			assertEquals(page, ResourceCatalogSync.Page.CODEC.decode(buffer));
		} finally { buffer.release(); }
	}
	@Test void catalogPublishesOnlyCompleteGenerationsAndClearsOnFailureOrLogout() {
		var buffer = new ResourceCatalogBuffer();
		buffer.accept(4, 0, 2, view("a", 1));
		assertTrue(buffer.current().isEmpty());
		buffer.accept(4, 1, 2, view("b", Long.MAX_VALUE));
		assertEquals(2, buffer.current().size());
		buffer.accept(3, 0, 0, null);
		assertEquals(2, buffer.current().size());
		buffer.accept(5, 0, 2, view("a", 9));
		assertTrue(buffer.current().isEmpty());
		buffer.accept(6, 0, -1, null);
		assertTrue(buffer.current().isEmpty());
		buffer.clear();
		buffer.accept(0, 0, 1, view("new_server", 7));
		assertEquals(7, buffer.current().getFirst().outputs().getFirst().amount());
	}
	@Test void jeiHistoryJsonPreservesNbtNumericTypesAndExactAmounts() {
		var identities = new java.util.ArrayList<>(List.of(ResourceTypes.FE, ResourceTypes.EXPERIENCE));
		for (var value : List.of(net.minecraft.nbt.IntTag.valueOf(1), net.minecraft.nbt.LongTag.valueOf(1L),
				net.minecraft.nbt.ByteTag.valueOf((byte) 1), net.minecraft.nbt.ShortTag.valueOf((short) 1),
				net.minecraft.nbt.FloatTag.valueOf(1F), net.minecraft.nbt.DoubleTag.valueOf(1D),
				new net.minecraft.nbt.LongArrayTag(new long[]{1L, Long.MAX_VALUE}))) {
			var data = new CompoundTag(); data.put("value", value);
			identities.add(ResourceKey.aeKey(ResourceLocation.parse("example:type"), ResourceLocation.parse("example:value"), data));
		}
		var codec = com.ayoshiko.productivebeesgenesis.client.jei.ResourceIngredient.CODEC;
		for (var identity : identities) {
			var original = new com.ayoshiko.productivebeesgenesis.client.jei.ResourceIngredient(identity, Long.MAX_VALUE, 4);
			var json = codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, original).getOrThrow();
			var disk = com.google.gson.JsonParser.parseString(json.toString());
			assertEquals(original, codec.parse(com.mojang.serialization.JsonOps.INSTANCE, disk).getOrThrow());
		}
	}
	@Test void invalidOrderDuplicateIdsAndOversizeCatalogNeverPublishPartialContents() {
		var buffer = new ResourceCatalogBuffer();
		assertThrows(IllegalArgumentException.class, () -> buffer.accept(1, 1, 2, view("b", 1)));
		buffer.accept(2, 0, 2, view("a", 1));
		assertThrows(IllegalArgumentException.class, () -> buffer.accept(2, 1, 2, view("a", 2)));
		assertTrue(buffer.current().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> buffer.accept(3, 0, ResourceCatalogBuffer.MAX_RECIPES + 1, view("a", 1)));
		assertThrows(IllegalArgumentException.class, () -> ResourceCatalogBuffer.checkBudget(java.util.Collections.nCopies(ResourceCatalogBuffer.MAX_RECIPES + 1, view("a", 1))));
	}
	@Test void malformedWireHeaderIsRejectedBeforeRecipeAllocation() {
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
		try {
			buffer.writeVarLong(1); buffer.writeVarInt(0); buffer.writeVarInt(Integer.MAX_VALUE);
			assertThrows(IllegalArgumentException.class, () -> ResourceCatalogSync.Page.CODEC.decode(buffer));
		} finally { buffer.release(); }
	}
	@Test void projectionIncludesDefaultsAndUsesTheSameFirstCentrifugeRecipeAsProduction() {
		var out = List.of(new ResourceProductionRecipe.Output(ResourceTypes.EXPERIENCE, 1, 1));
		var recipe = new ResourceProductionRecipe(BEE, out);
		var index = ResourceRecipeIndex.build(1, List.of(new RecipeHolder<>(ResourceLocation.parse("example:java_default"), recipe)), List.of(
				new RecipeHolder<>(ResourceLocation.parse("example:z"), new ResourceCentrifugeRecipe(BEE, out, 20)),
				new RecipeHolder<>(ResourceLocation.parse("example:a"), new ResourceCentrifugeRecipe(BEE, out, 40))));
		var views = ResourceRecipeView.collect(index);
		assertEquals(2, views.size());
		assertEquals(40, views.stream().filter(ResourceRecipeView::centrifuging).findFirst().orElseThrow().processingTime());
		assertTrue(views.stream().anyMatch(value -> value.id().getPath().equals("java_default")));
	}
	@Test void summaryKeepsLongPrecisionLimitsRowsAndDoesNotExposePendingOrOwner() {
		var account = ResourceAccountsMinecraftTest.account(new ResourceAccounts(() -> {}));
		var amounts = Map.of(ResourceTypes.FE, Long.MAX_VALUE, ResourceTypes.EXPERIENCE, 1L,
				ResourceTypes.HIGH_VOLTAGE, 2L, ResourceTypes.EXTREME_HIGH_VOLTAGE, 3L, ResourceTypes.SOUL, 4L);
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
		account.start(ticket); account.freeze(ticket, amounts); account.commit(ticket);
		var status = ResourceOutputStatus.summarize(account, true);
		assertEquals(5, status.totalKinds()); assertEquals(4, status.balances().size());
		assertEquals(status, ResourceOutputStatus.load(status.save()));
		assertEquals(amounts, account.available());
		assertFalse(status.save().toString().contains(account.owner().toString()));
		var transfer = account.beginTransfer(account.reference(), ResourceTypes.FE, 5);
		account.suspendTransfer(transfer, "private diagnostic");
		var paused = ResourceOutputStatus.summarize(account, false);
		assertEquals(ResourceOutputStatus.State.PARTIAL, paused.state());
		assertFalse(paused.save().toString().contains("private diagnostic"));
		assertEquals(5L, account.uncertainTransfer().get(ResourceTypes.FE));
	}
}
