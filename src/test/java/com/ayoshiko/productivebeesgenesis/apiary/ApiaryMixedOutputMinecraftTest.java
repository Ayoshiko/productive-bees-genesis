package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.resource.*;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ApiaryMixedOutputMinecraftTest {
	@org.junit.jupiter.api.BeforeAll static void loadConfigs() {
		net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}
	@org.junit.jupiter.api.AfterAll static void unloadConfigs() {
		net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}
	private static IInventorySlot slot(ItemStack initial) {
		var slot = mock(IInventorySlot.class);
		var value = new ItemStack[]{initial.copy()};
		when(slot.getStack()).thenAnswer(call -> value[0]);
		when(slot.getLimit(any())).thenReturn(64);
		when(slot.isItemValid(any())).thenReturn(true);
		doAnswer(call -> { value[0] = call.getArgument(0); return null; }).when(slot).setStack(any());
		return slot;
	}

	private static IExtendedFluidTank tank(int stored, int capacity) {
		var tank = mock(IExtendedFluidTank.class);
		var value = new FluidStack[]{stored == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, stored)};
		when(tank.getFluid()).thenAnswer(call -> value[0]);
		when(tank.getCapacity()).thenReturn(capacity);
		when(tank.insert(any(), eq(Action.SIMULATE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
			FluidStack request = call.getArgument(0);
			return request.copyWithAmount(Math.max(0, request.getAmount() - capacity + value[0].getAmount()));
		});
		doAnswer(call -> { value[0] = call.getArgument(0); return null; }).when(tank).setStack(any());
		return tank;
	}

	@Test void directResourcePreparationAcceptsBothBeeFormatsAndRejectsOtherEntities() {
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
		var tile = new TileEntityMekApiary(net.minecraft.core.Holder.direct(block), net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
		tile.setLevel(mock(net.minecraft.server.level.ServerLevel.class));
		var upgrades = mock(ApiaryUpgradeHandler.class);
		when(upgrades.getProductivityMultiplier()).thenReturn(1.0F);
		when(upgrades.getStackProductionCount()).thenReturn(1);
		var host = mock(ResourceHiveHost.class);
		var account = mock(ResourceAccount.class);
		var reference = new ResourceAccount.Reference(UUID.randomUUID(), 1);
		when(host.reference()).thenReturn(reference);
		when(host.productionMonitor()).thenReturn(new com.ayoshiko.productivebeesgenesis.resource.ResourceProductionMonitor());
		when(host.account(tile)).thenReturn(account);
		when(account.canExtract(reference)).thenReturn(true);
		when(host.fitsExact(eq(account), anyMap())).thenReturn(true);
		var bee = new BeeSlot();
		var type = ResourceLocation.parse("example:charged");

		try (var lookup = mockStatic(ResourceHiveHost.class)) {
			lookup.when(() -> ResourceHiveHost.ensureMechanical(tile)).thenReturn(host);
			for (var key : List.of(ResourceTypes.FE, ResourceTypes.HIGH_VOLTAGE, ResourceTypes.EXTREME_HIGH_VOLTAGE)) {
				var spec = new PbResourceCatalog.Spec(List.of(new ResourceProductionRecipe.Output(key, 1000, 1)), "");
				for (var field : List.of("id", "entity")) {
					var data = new CompoundTag();
					data.putString(field, "productivebees:configurable_bee");
					data.putString("type", type.toString());
					bee.setBeeData(data);
					assertNotNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 0, type, null), key + " via " + field);
					when(account.laneSuspended(0)).thenReturn(true);
					assertNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 0, type, null));
					assertNotNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 1, type, null));
					when(account.laneSuspended(0)).thenReturn(false);
				}
				var invalid = new CompoundTag();
				invalid.putString("entity", "minecraft:pig");
				invalid.putString("type", type.toString());
				bee.setBeeData(invalid);
				assertNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 0, type, null));
			}
		}
	}

	@Test void loadingTheSameHoneySnapshotTwiceDoesNotAddAnotherInventory() {
		var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
		var tile = new TileEntityMekApiary(net.minecraft.core.Holder.direct(state.getBlock()), net.minecraft.core.BlockPos.ZERO, state);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		tile.getFluidTank().setStack(new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 500));
		var saved = new CompoundTag();
		tile.nbtSerializer().saveApiaryState(saved, registries);
		tile.nbtSerializer().loadApiaryState(saved, registries);
		tile.nbtSerializer().loadApiaryState(saved, registries);
		assertEquals(500, tile.getFluidTank().getFluidAmount());
		var upgrade = tile.getUpgradeData(registries);
		assertTrue(tile.nbtSerializer().applyUpgradeData(registries, upgrade));
		assertEquals(500, tile.getFluidTank().getFluidAmount());
	}

	@Test void isolatedResourceAccountDoesNotBlockOrdinaryFrozenOutputs() {
		var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
		var tile = new TileEntityMekApiary(net.minecraft.core.Holder.direct(state.getBlock()), net.minecraft.core.BlockPos.ZERO, state);
		var processor = new BeeProduceProcessor(mock(ApiaryUpgradeHandler.class), tile);
		var host = mock(ResourceHiveHost.class);
		var reference = new ResourceAccount.Reference(UUID.randomUUID(), 1);
		var account = mock(ResourceAccount.class);
		when(host.reference()).thenReturn(reference);
		when(host.account(tile)).thenReturn(account);
		when(account.canExtract(reference)).thenReturn(false);
		processor.production.freeze(new BeeProduceProcessor.Generated(
				List.of(new ItemStack(Items.HONEYCOMB, 4)), FluidStack.EMPTY, 0));
		var upgrades = new ApiaryBatchUpgradeSnapshot(0, false, false, 2, false, false, false, false, false, false);

		try (var hostLookup = mockStatic(ResourceHiveHost.class)) {
			hostLookup.when(() -> ResourceHiveHost.existing(tile)).thenReturn(host);
			assertTrue(processor.flushFrozen(upgrades));
		}

		assertFalse(processor.production.hasFrozen());
		verify(account, never()).canExtract(reference);
	}

	@Test void wholeBatchMergesExactComponentsAndKeepsPreparationReadOnly() {
		var named = new ItemStack(Items.IRON_INGOT, 50);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("精确组件"));
		var slots = List.of(slot(new ItemStack(Items.IRON_INGOT, 63)), slot(ItemStack.EMPTY), slot(named));
		var tank = tank(249, 1000);
		var plan = ApiaryLocalOutputPlan.prepare(slots, tank,
				List.of(new ItemStack(Items.IRON_INGOT, 2), named.copyWithCount(14)), new FluidStack(Fluids.WATER, 250));
		assertNotNull(plan);
		assertEquals(63, slots.get(0).getStack().getCount());
		assertTrue(slots.get(1).getStack().isEmpty());
		assertEquals(249, tank.getFluid().getAmount());
		plan.commit();
		assertEquals(64, slots.get(0).getStack().getCount());
		assertEquals(1, slots.get(1).getStack().getCount());
		assertEquals(64, slots.get(2).getStack().getCount());
		assertEquals(named.get(DataComponents.CUSTOM_NAME), slots.get(2).getStack().get(DataComponents.CUSTOM_NAME));
		assertEquals(499, tank.getFluid().getAmount());
		assertThrows(IllegalStateException.class, plan::commit);
	}

	@Test void insufficientSpaceForEitherProductLeavesBothInventoriesUnchanged() {
		var slots = List.of(slot(new ItemStack(Items.IRON_INGOT, 63)));
		var tank = tank(900, 1000);
		assertNull(ApiaryLocalOutputPlan.prepare(slots, tank, List.of(new ItemStack(Items.IRON_INGOT, 2)), FluidStack.EMPTY));
		assertNull(ApiaryLocalOutputPlan.prepare(slots, tank, List.of(new ItemStack(Items.IRON_INGOT)), new FluidStack(Fluids.WATER, 101)));
		assertEquals(63, slots.getFirst().getStack().getCount());
		assertEquals(900, tank.getFluid().getAmount());
		verify(slots.getFirst(), never()).setStack(any());
		verify(tank, never()).setStack(any());
	}

	@Test void stalePlanDoesNotOverwriteAnInterveningInventoryChange() {
		var slot = slot(ItemStack.EMPTY);
		var plan = ApiaryLocalOutputPlan.prepare(List.of(slot), tank(0, 1000), List.of(new ItemStack(Items.IRON_INGOT)), FluidStack.EMPTY);
		slot.setStack(new ItemStack(Items.DIAMOND));
		assertThrows(IllegalStateException.class, plan::commit);
		assertTrue(slot.getStack().is(Items.DIAMOND));
	}

	@Test void fractionalRoundsAreSharedByGuaranteedItemsAndResources() {
		var output = new ResourceProductionRecipe.Output(ResourceTypes.FE, 100, 1);
		for (long seed : new long[]{3, 79, 1212}) {
			var plan = new ResourceRollPlan(List.of(output), 1.5, 2, seed);
			var sampled = plan.sampleWithRolls(256);
			assertEquals(sampled.rolls() * 300, sampled.amounts().get(ResourceTypes.FE));
			assertEquals(plan.sample(256), sampled.amounts());
			var items = new java.util.ArrayList<ItemStack>();
			BeeProduceBatchSampler.sampleInto(items, Map.of(new ItemStack(Items.IRON_INGOT),
					new cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput(
							net.minecraft.world.item.crafting.Ingredient.EMPTY, 1, 1, 1)), (int) sampled.rolls(), 1, 0, 2);
			assertEquals(sampled.rolls() * 3, items.getFirst().getCount());
		}
	}

	@Test void sharedGenerationPreservesPendingWorkAndMatchesOrdinaryItemRules() {
		var bee = new BeeSlot();
		bee.setBeeData(new CompoundTag());
		bee.setTicksInHive(7);
		var slots = new BeeSlot[]{bee};
		var counts = new int[]{3};
		var group = new OrderedSlotIndex();
		group.reset(1); group.add(0);
		var upgrades = new ApiaryBatchUpgradeSnapshot(0, false, false, 2, false, false, false, false, false, false);
		var outputs = Map.of(new ItemStack(Items.IRON_NUGGET),
				new cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput(
						net.minecraft.world.item.crafting.Ingredient.EMPTY, 10, 10, 1));
		var type = ResourceLocation.parse("example:mixed");
		var generator = new BeeProductionGenerator(this, new MultiFlowerProductionCache());
		var ordinary = generator.prepare(slots, counts, group, type, outputs, null,
				net.minecraft.core.BlockPos.ZERO, null, upgrades, false, true, -1, null, null, 0);
		var resource = generator.prepare(slots, counts, group, type, outputs, null,
				net.minecraft.core.BlockPos.ZERO, null, upgrades, false, true, 6, null, null, 0);
		assertArrayEquals(new int[]{3}, counts);
		assertEquals(7, bee.getTicksInHive());
		assertEquals(60, ordinary.items().stream().mapToInt(ItemStack::getCount).sum());
		assertEquals(ordinary.items().size(), resource.items().size());
		for (int i = 0; i < ordinary.items().size(); i++) {
			assertTrue(ItemStack.matches(ordinary.items().get(i), resource.items().get(i)));
			assertNotSame(ordinary.items().get(i), resource.items().get(i));
		}
		resource.items().getFirst().setCount(1);
		assertEquals(60, ordinary.items().getFirst().getCount());
		assertEquals(1, outputs.keySet().iterator().next().getCount());
	}

	@Test void outputFailureRetainsFrozenLocalSnapshotsAcrossSaveAndLoad() {
		var target = slot(ItemStack.EMPTY);
		var plan = ApiaryLocalOutputPlan.prepare(List.of(target), tank(0, 1000), List.of(new ItemStack(Items.DIAMOND, 7)), FluidStack.EMPTY);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var context = new CompoundTag();
		context.put("mixed_local", plan.context(registries));
		var store = new ResourceAccounts(() -> {});
		var account = store.create(UUID.randomUUID(), new ResourceAccount.Binding(
				ResourceLocation.parse("minecraft:overworld"), 1, ResourceLocation.parse("productivebeesgenesis:mek_apiary")));
		var ticket = account.reserve(account.reference(), 1, context, Map.of(ResourceTypes.FE, 100L));
		account.start(ticket); account.freeze(ticket, Map.of(ResourceTypes.FE, 100L));
		doThrow(new IllegalStateException("receiver callback")).when(target).setStack(any());
		assertThrows(IllegalStateException.class, plan::commit);
		account.suspendProduction(ticket, "local output uncertain");
		var saved = store.save();
		var loaded = ResourceAccounts.load(saved, () -> {}).find(account.reference().sourceId());
		assertTrue(loaded.available().isEmpty());
		assertFalse(loaded.canExtract(loaded.reference()));
		assertEquals(context, saved.getList("accounts", 10).getCompound(0).getCompound("production").getCompound("context"));
		assertThrows(IllegalStateException.class, plan::commit);
	}
}
