package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceChemicalOutputMinecraftTest {
	private ResourceChemicalOutput output(ResourceAccount account) {
		var port = mock(ResourceOutputAccess.class);
		when(port.account()).thenReturn(account);
		when(port.reference()).thenAnswer(ignored -> account.reference());
		when(port.capacity()).thenReturn(Long.MAX_VALUE);
		when(port.outputAllowed(ResourceNativeType.CHEMICAL)).thenReturn(true);
		return new ResourceChemicalOutput(port);
	}

	@Test void longExtractionIsExactAndSimulationOrReturnedStackMutationCannotSpendResources() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		long amount = (long) Integer.MAX_VALUE + 12345;
		produce(account, amount);
		var output = output(account);
		var before = store.save();
		var simulated = output.extractChemical(0, amount - 5, Action.SIMULATE);
		assertEquals(amount - 5, simulated.getAmount());
		simulated.setAmount(1);
		output.getChemicalInTank(0).setAmount(0);
		assertEquals(before, store.save());
		assertEquals(amount - 5, output.extractChemical(0, amount - 5, Action.EXECUTE).getAmount());
		assertEquals(5, account.available().get(HYDROGEN));
		assertEquals(5, output.extractChemical(Long.MAX_VALUE, Action.EXECUTE).getAmount());
		assertTrue(account.available().isEmpty());
		assertEquals(1, output.getChemicalTanks());
		assertTrue(output.getChemicalInTank(0).isEmpty());
	}

	@Test void insertsAndDirectSettersCannotMintOrEraseAccountResources() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 100);
		var output = output(account);
		var stack = new ChemicalStack(MekanismAPI.CHEMICAL_REGISTRY.get(ResourceLocation.parse("mekanism:hydrogen")).getAsHolder(), 75);
		var before = store.save();
		assertFalse(output.isValid(0, stack));
		assertEquals(75, output.insertChemical(0, stack, Action.EXECUTE).getAmount());
		assertEquals(75, output.insertChemical(stack, Action.SIMULATE).getAmount());
		output.setChemicalInTank(0, stack);
		output.setChemicalInTank(0, ChemicalStack.EMPTY);
		assertEquals(before, store.save());
	}

	@Test void suspensionAndInvalidationBlockCachedHandlers() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 100);
		var output = output(account);
		assertEquals(100, output.getChemicalInTank(0).getAmount());
		var ticket = account.beginTransfer(account.reference(), HYDROGEN, 25);
		assertTrue(output.extractChemical(0, 25, Action.EXECUTE).isEmpty());
		account.suspendTransfer(ticket, "Unknown receiver");
		assertTrue(output.extractChemical(0, 25, Action.SIMULATE).isEmpty());
		account.resolveTransfer(OWNER, ticket, 0);
		output.invalidate();
		assertTrue(output.extractChemical(0, 100, Action.EXECUTE).isEmpty());
		assertEquals(100, account.available().get(HYDROGEN));
	}

	@Test void unresolvedKindsAndChemicalVersionsRemainSavedAndAreNeverExposedAsAnotherResource() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var unknown = new ResourceKey(HYDROGEN.kind(), 9, HYDROGEN.payload());
		var missing = ResourceKey.chemical(ResourceLocation.parse("absent:chemical"));
		var outputs = Map.of(HYDROGEN, 100L, unknown, 30L, missing, 20L);
		var ticket = account.reserve(account.reference(), 1, new CompoundTag(), outputs);
		account.start(ticket);
		account.freeze(ticket, outputs);
		account.commit(ticket);
		var output = output(account);
		assertEquals(1, output.getChemicalTanks());
		assertEquals(100, output.extractChemical(0, 1000, Action.EXECUTE).getAmount());
		assertEquals(Map.of(unknown, 30L, missing, 20L), account.available());
	}

	@Test void invalidMachineNodePayloadKeepsItsOriginalTagType() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var host = new ResourceHiveHost();
		var raw = new CompoundTag();
		raw.putInt("version", 1); raw.putUUID("source", java.util.UUID.randomUUID());
		raw.putLong("generation", 1); raw.putLong("capacity", 1000);
		raw.put("me_node", StringTag.valueOf("unparsed-node-data"));
		host.deserializeNBT(provider, raw);
		assertEquals(raw, host.serializeNBT(provider));
		assertEquals("invalid_host_data", host.status());
	}
}
