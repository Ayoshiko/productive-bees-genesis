package com.ayoshiko.productivebeesgenesis.resource;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GenericResourceBridge;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.account;

@Tag("minecraft")
class ResourceGenericMinecraftTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

	@Test void nativeBudgetQueuesLaterInterfacesAndReleasesAnUnloadedHead() {
		var server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
		org.mockito.Mockito.when(server.isSameThread()).thenReturn(true);
		var workers = java.util.stream.IntStream.range(0, 600).mapToObj(i -> new Object()).toList();
		for (int i = 0; i < workers.size(); i++) assertEquals(i < 256, ResourceRuntime.claimNativeOutput(server, workers.get(i)));
		org.mockito.Mockito.when(server.getTickCount()).thenReturn(1);
		assertFalse(ResourceRuntime.claimNativeOutput(server, workers.getFirst()));
		ResourceRuntime.releaseNativeOutput(server, workers.get(256));
		assertTrue(ResourceRuntime.claimNativeOutput(server, workers.get(257)));
		for (int i = 258; i < 513; i++) assertTrue(ResourceRuntime.claimNativeOutput(server, workers.get(i)));
		assertFalse(ResourceRuntime.claimNativeOutput(server, workers.get(513)));
		org.mockito.Mockito.when(server.getTickCount()).thenReturn(2);
		assertFalse(ResourceRuntime.claimNativeOutput(server, workers.getFirst()));
		for (int i = 513; i < workers.size(); i++) assertTrue(ResourceRuntime.claimNativeOutput(server, workers.get(i)));
		assertTrue(ResourceRuntime.claimNativeOutput(server, workers.getFirst()));
		ResourceRuntime.stopped(new net.neoforged.neoforge.event.server.ServerStoppedEvent(server));
	}

	@Test void realComponentKeyAndLongAmountRoundTripThroughWrappedTemplate() {
		var item = new ItemStack(Items.DIAMOND);
		item.set(DataComponents.CUSTOM_NAME, Component.literal("精确资源模板"));
		var original = new GenericStack(AEItemKey.of(item), Long.MAX_VALUE);
		var stack = Ae2GenericResourceBridge.fromAeStack(REGISTRIES, original);
		assertEquals(original, Ae2GenericResourceBridge.toAeStack(REGISTRIES, stack));
		var wrapped = Ae2GenericResourceBridge.wrapForDisplay(REGISTRIES, stack);
		assertEquals(stack, Ae2GenericResourceBridge.fromWrappedItem(REGISTRIES, wrapped));
		assertEquals(1, wrapped.getCount());
		assertEquals(1, item.getCount());
		wrapped.setCount(2);
		assertThrows(IllegalArgumentException.class, () -> Ae2GenericResourceBridge.fromWrappedItem(REGISTRIES, wrapped));
	}

	@Test void unknownTypeWrongIdAndIgnoredFieldsNeverBecomeOtherResources() {
		var key = AEItemKey.of(Items.DIAMOND);
		var data = key.toTag(REGISTRIES);
		var unknown = ResourceKey.aeKey(ResourceLocation.parse("missing:kind"), key.getId(), data);
		assertNull(Ae2GenericResourceBridge.resolve(REGISTRIES, unknown));
		assertNull(Ae2GenericResourceBridge.resolve(REGISTRIES,
				ResourceKey.aeKey(key.getType().getId(), ResourceLocation.parse("minecraft:emerald"), data)));
		data.putString("ignored", "must not disappear");
		assertNull(Ae2GenericResourceBridge.resolve(REGISTRIES, ResourceKey.aeKey(key.getType().getId(), key.getId(), data)));
		var missing = new GenericStack(AEItemKey.of(appeng.core.definitions.AEItems.MISSING_CONTENT.stack()), 1);
		assertThrows(IllegalArgumentException.class, () -> Ae2GenericResourceBridge.fromAeStack(REGISTRIES, missing));
	}

	@Test void scalarAndGenericRecipesKeepIdentityAcrossJsonWireAndCombScaling() {
		var json = JsonParser.parseString("""
				{"bee":"example:resources","outputs":[{"kind":"air","amount":"1000"},
				{"kind":"emc","amount":"9007199254740993"},
				{"kind":"ae_key","type":"test_resources:digitalization","id":"test_resources:data_flow",
				"data":{"resource":"test_resources:data_flow"},"amount":"7"}]}
				""");
		var recipe = ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow();
		var encoded = ResourceProductionRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
		assertEquals(recipe, ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
		try {
			ResourceProductionRecipe.STREAM_CODEC.encode(buffer, recipe);
			assertEquals(recipe, ResourceProductionRecipe.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
		var comb = new ResourceCentrifugeRecipe(recipe.bee(), recipe.outputs(), 20);
		var plan = com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi.planCentrifuging(comb, 1, 0, 0);
		assertEquals(Map.of(ResourceTypes.AIR, 4000L, ResourceTypes.EMC, 36028797018963972L,
				recipe.outputs().get(2).key(), 28L), plan.sample(4));
	}

	@Test void absentResourceModsAndUnknownKeysPreserveAllBalances() {
		assertFalse(ResourceTypes.available(ResourceTypes.EMC));
		assertFalse(ResourceTypes.available(ResourceTypes.AIR));
		var payload = new CompoundTag();
		payload.putString("resource", "missing:flow");
		var key = ResourceKey.aeKey(ResourceLocation.parse("missing:type"), ResourceLocation.parse("missing:flow"), payload);
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var amounts = Map.of(key, Long.MAX_VALUE, ResourceTypes.AIR, 1000L, ResourceTypes.EMC, 9007199254740993L);
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
		account.start(ticket); account.freeze(ticket, amounts); account.commit(ticket);
		assertEquals(amounts, ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId()).available());
		assertNull(Ae2GenericResourceBridge.resolve(REGISTRIES, key));
		assertEquals(Long.MAX_VALUE, ResourceTypes.transferLimit(key));
		assertEquals(Long.MAX_VALUE, ResourceTypes.transferLimit(ResourceTypes.EMC));
	}

	@Test void malformedAndOversizedGenericIdentitiesAreRejectedWithoutExternalCodec() {
		var oversized = new CompoundTag();
		oversized.putString("content", "x".repeat(ResourceKey.MAX_PAYLOAD_BYTES));
		assertThrows(IllegalArgumentException.class, () -> ResourceKey.aeKey(
				ResourceLocation.parse("test:type"), ResourceLocation.parse("test:id"), oversized));
		for (var key : List.of(new ResourceKey(ResourceTypes.EMC.kind(), 2, new CompoundTag()),
				new ResourceKey(ResourceLocation.parse("productivebeesgenesis:ae_key"), 1, new CompoundTag()))) {
			assertFalse(ResourceTypes.declared(key));
		}
	}
}
