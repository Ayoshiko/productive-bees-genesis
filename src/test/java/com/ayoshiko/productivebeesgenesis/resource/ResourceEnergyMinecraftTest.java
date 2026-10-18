package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceEnergyMinecraftTest {
	private static final ResourceLocation BEE = ResourceLocation.parse("example:energy");
	private static final ResourceKey FE = ResourceTypes.FE;

	private static String json(String energyType, String amount) {
		return "{\"bee\":\"example:energy\",\"outputs\":[{\"kind\":\"energy\",\"energy_type\":\""
				+ energyType + "\",\"amount\":" + amount + "}]}";
	}

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

	@Test void feDefinitionKeepsLongMaxThroughJsonAndWireAlongsideChemical() {
		var recipe = ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
				JsonParser.parseString(json("FE", "\"9223372036854775807\""))).getOrThrow();
		assertEquals(new ResourceProductionRecipe.Output(FE, Long.MAX_VALUE, 1), recipe.outputs().getFirst());
		var encoded = ResourceProductionRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
		assertEquals(recipe, ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
		var chemical = new ResourceProductionRecipe.Output(ResourceLocation.parse("mekanism:hydrogen"), 100, 1);
		var mixed = new ResourceProductionRecipe(BEE, List.of(recipe.outputs().getFirst(), chemical));
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		try {
			ResourceProductionRecipe.STREAM_CODEC.encode(buffer, mixed);
			assertEquals(mixed, ResourceProductionRecipe.STREAM_CODEC.decode(buffer));
			buffer.clear();
			buffer.writeByte(99);
			assertThrows(IllegalArgumentException.class, () -> ResourceOutputCodecs.read(buffer));
		} finally { buffer.release(); }
		assertEquals(ResourceLocation.parse("mekanism:hydrogen"), chemical.chemical());
	}

	@Test void otherEnergyTypesUnknownSchemasAndNumericAmountsCannotBecomeFe() {
		for (var type : List.of("GTEU", "fe", "unknown")) {
			assertTrue(ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
					JsonParser.parseString(json(type, "\"100\""))).error().isPresent());
		}
		assertTrue(ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
				JsonParser.parseString(json("FE", "9007199254740993"))).error().isPresent());
		assertThrows(IllegalArgumentException.class, () ->
				new ResourceProductionRecipe.Output(new ResourceKey(FE.kind(), 2, FE.payload()), 100, 1));
		assertTrue(ResourceTypes.available(FE));
	}

	@Test void productivityAndCombPortionsPreserveTypedResourceIdentity() {
		var recipe = new ResourceCentrifugeRecipe(BEE, List.of(
				new ResourceProductionRecipe.Output(FE, 3_000_000_007L, 1),
				new ResourceProductionRecipe.Output(ResourceLocation.parse("mekanism:hydrogen"), 10, 1)), 20);
		var plan = ResourceProductionApi.planCentrifuging(recipe, 2, 0, 0);
		assertEquals(Map.of(FE, 24_000_000_056L, HYDROGEN, 80L), plan.sample(4));
		assertEquals(plan.maximum(4), plan.sample(4));
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
