package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.resource.*;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import cy.jdkdigital.productivebees.init.ModBlocks;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.util.BeeHelper;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.IContentsListener;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
			var valid = new CompoundTag();
			valid.putString("entity", "productivebees:configurable_bee");
			valid.putString("type", type.toString());
			bee.setBeeData(valid);
			var spec = new PbResourceCatalog.Spec(List.of(new ResourceProductionRecipe.Output(ResourceTypes.FE, 1000, 1)), "");
			when(host.account(tile)).thenThrow(new IllegalStateException("Preparation temporarily unavailable"));
			assertNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 0, type, null));
			assertEquals(BeeState.RESOURCE_PREPARATION_FAILED, bee.getState());
			assertEquals(ResourceProductionSnapshot.State.PREPARATION_FAILED,
					host.productionMonitor().snapshot(0).entries().getFirst().state());
			doReturn(account).when(host).account(tile);
			assertNotNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 0, type, null));
			assertEquals(ResourceProductionSnapshot.State.WAITING_WORK,
					host.productionMonitor().snapshot(0).entries().getFirst().state());
			when(account.canExtract(reference)).thenReturn(false);
			assertNull(ApiaryResourceProduction.prepare(tile, spec, upgrades, bee, 0, type, null));
			assertEquals(BeeState.WAITING_RESOURCE, bee.getState());
			assertEquals(ResourceProductionSnapshot.State.WAITING_RESOURCE,
					host.productionMonitor().snapshot(0).entries().getFirst().state());
		}
	}

	private static final class NativeBeeEnergy extends MachineEnergyContainer<TileEntityMekApiary> {
		boolean unreadable;

		NativeBeeEnergy(IContentsListener listener) {
			super(10_000, 10, automation -> true, automation -> true, null, listener);
		}

		@Override
		public long getEnergy() {
			if (unreadable) throw new IllegalStateException("Public payment receipt unavailable");
			return super.getEnergy();
		}

		long stored() {
			return ((BasicEnergyContainerAccessor) (Object) this).productivebeesgenesis$getStored();
		}
	}

	@Test void zeroChanceResourceCommitsLocalOutputAndRetainsFailedPayment() {
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
		var tile = new TileEntityMekApiary(net.minecraft.core.Holder.direct(block), net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var level = mock(net.minecraft.server.level.ServerLevel.class);
		when(level.registryAccess()).thenReturn(registries);
		when(level.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(42L));
		tile.setLevel(level);
		var upgrades = mock(ApiaryUpgradeHandler.class);
		when(upgrades.getProductivityMultiplier()).thenReturn(1.0F);
		when(upgrades.getStackProductionCount()).thenReturn(1);
		var snapshot = ApiaryBatchUpgradeSnapshot.capture(tile, upgrades);
		var bee = tile.getBeeSlots()[0];
		var beeData = new CompoundTag();
		beeData.putString("entity", "productivebees:configurable_bee");
		beeData.putString("type", "example:zero_chance");
		bee.setBeeData(beeData);
		var type = ResourceLocation.parse("example:zero_chance");
		var spec = new PbResourceCatalog.Spec(
				List.of(new ResourceProductionRecipe.Output(ResourceTypes.FE, 1000, 0)), "mixed_outputs_mechanical_only",
				List.of(new PbResourceCatalog.ItemOutput(new ItemStack(Items.HONEYCOMB), 1, 1, 1)));
		var mixed = mock(ApiaryMixedProduction.class);
		when(mixed.maximumCycles(anyLong(), any())).thenAnswer(call -> call.getArgument(0));
		when(mixed.sample(anyLong(), anyLong(), anyLong(), anyList())).thenAnswer(call -> new ApiaryRoutedOutput(
				tile, new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.HONEYCOMB)), FluidStack.EMPTY, 0), snapshot));
		var prepared = new ApiaryResourceProduction.Prepared(
				new ResourceRollPlan(spec.outputs(), 1, 0, 0), mixed, bee.getBeeData(), snapshot,
				tile.getFeederSlotManager().getFlowerCacheVersion(), com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.RECIPE_VERSION.get());
		var host = mock(ResourceHiveHost.class);
		var store = new ResourceAccounts(() -> {});
		UUID owner = UUID.randomUUID();
		var binding = new ResourceAccount.Binding(ResourceLocation.parse("minecraft:overworld"), 0,
				ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
		var account = store.create(owner, binding);
		var reference = account.reference();
		when(host.reference()).thenReturn(reference);
		when(host.account(tile)).thenReturn(account);
		when(host.productionMonitor()).thenReturn(new ResourceProductionMonitor());
		var energy = mock(mekanism.api.energy.IEnergyContainer.class);
		long[] available = {100};
		when(energy.getEnergy()).thenAnswer(call -> available[0]);
		when(energy.extract(anyLong(), any(), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
			long amount = call.getArgument(0);
			if (call.getArgument(1) == Action.EXECUTE) available[0] -= amount;
			return amount;
		});
		try (var lookup = mockStatic(ResourceHiveHost.class)) {
			lookup.when(() -> ResourceHiveHost.existing(tile)).thenReturn(host);
			assertEquals(BeeState.WORKING, ApiaryResourceProduction.advance(tile, level, bee, 0, type,
					mock(PbResourceCatalog.class), spec, 1, 1, false, 1, upgrades, energy, 1, 0, prepared, null, 0, snapshot));
			assertEquals(99, available[0]);
			assertEquals(1, tile.getOutputSlots().stream().mapToInt(slot -> slot.getCount()).sum());
			assertFalse(tile.productionState().suspended());
			assertNull(account.productionTicket());
			assertTrue(account.availableExact().isEmpty());

			when(energy.extract(anyLong(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL)))
					.thenThrow(new IllegalStateException("Payment outcome unavailable"));
			assertEquals(BeeState.WAITING_RESOURCE, ApiaryResourceProduction.advance(tile, level, bee, 0, type,
					mock(PbResourceCatalog.class), spec, 1, 1, false, 1, upgrades, energy, 1, 0, prepared, null, 0, snapshot));
			assertFalse(tile.productionState().suspended());
			assertTrue(account.laneSuspended(0));
			assertFalse(account.laneSuspended(1));
			var ticket = account.productionTicket();
			assertNotNull(ticket);
			var context = account.productionContext(ticket);
			assertEquals(bee.getBeeData(), context.getCompound("bee_data"));
			assertEquals(99, context.getLong("energy_before"));
			assertEquals(1, context.getLong("energy_cost"));
			assertTrue(context.getCompound("mixed_local").getString("items_snbt").contains("minecraft:honeycomb"));
			assertTrue(account.frozenProduction().isEmpty());
			var saved = store.save();
			var restoredStore = ResourceAccounts.load(saved, () -> {});
			assertFalse(restoredStore.hasInvalidData());
			var restored = restoredStore.find(reference.sourceId());
			assertEquals(ticket, restored.productionTicket());
			assertEquals(context, restored.productionContext(ticket));
			assertTrue(restored.laneSuspended(0));
			assertTrue(restored.canExtract(reference));
			assertEquals(saved, restoredStore.save());

			when(host.account(tile)).thenReturn(restored);
			doAnswer(call -> {
				long amount = call.getArgument(0);
				available[0] -= amount;
				return amount;
			}).when(energy).extract(anyLong(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
			var otherBee = tile.getBeeSlots()[1];
			otherBee.setBeeData(beeData.copy());
			var otherPrepared = new ApiaryResourceProduction.Prepared(prepared.plan(), mixed,
					otherBee.getBeeData(), snapshot, prepared.flowerVersion(), prepared.recipeVersion());
			assertEquals(BeeState.WORKING, ApiaryResourceProduction.advance(tile, level, otherBee, 1, type,
					mock(PbResourceCatalog.class), spec, 1, 1, false, 1, upgrades, energy, 1, 0, otherPrepared, null, 0, snapshot));
			assertEquals(98, available[0]);
			assertEquals(2, tile.getOutputSlots().stream().mapToInt(slot -> slot.getCount()).sum());
			assertTrue(restored.laneSuspended(0));
			assertFalse(restored.laneSuspended(1));
			assertFalse(tile.productionState().suspended());

			boolean[] failNotification = {false};
			NativeBeeEnergy[] nativeReference = new NativeBeeEnergy[1];
			var nativeEnergy = new NativeBeeEnergy(() -> {
				if (failNotification[0]) {
					failNotification[0] = false;
					nativeReference[0].unreadable = true;
					throw new IllegalStateException("Energy changed before notification failed");
				}
			});
			nativeReference[0] = nativeEnergy;
			nativeEnergy.setEnergy(100);
			failNotification[0] = true;
			assertEquals(BeeState.WORKING, ApiaryResourceProduction.advance(tile, level, otherBee, 1, type,
					mock(PbResourceCatalog.class), spec, 1, 1, false, 1, upgrades, nativeEnergy, 1, 0, otherPrepared, null, 0, snapshot));
			assertTrue(nativeEnergy.unreadable);
			assertEquals(99, nativeEnergy.stored());
			assertEquals(3, tile.getOutputSlots().stream().mapToInt(slot -> slot.getCount()).sum());
			assertFalse(restored.laneSuspended(1));
			assertEquals(0, otherBee.getTicksInHive());
			nativeEnergy.unreadable = false;
			assertEquals(BeeState.WORKING, ApiaryResourceProduction.advance(tile, level, otherBee, 1, type,
					mock(PbResourceCatalog.class), spec, 1, 1, false, 1, upgrades, nativeEnergy, 1, 0, otherPrepared, null, 0, snapshot));
			assertEquals(98, nativeEnergy.stored());
			assertEquals(4, tile.getOutputSlots().stream().mapToInt(slot -> slot.getCount()).sum());
			var finalStore = ResourceAccounts.load(restoredStore.save(), () -> {});
			assertFalse(finalStore.hasInvalidData());
			assertFalse(finalStore.find(reference.sourceId()).laneSuspended(1));
			assertTrue(finalStore.find(reference.sourceId()).laneSuspended(0));
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
		var upgraded = new TileEntityMekApiary(net.minecraft.core.Holder.direct(state.getBlock()), net.minecraft.core.BlockPos.ZERO, state);
		assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED, upgraded.nbtSerializer().applyUpgradeData(registries, upgrade));
		assertEquals(500, upgraded.getFluidTank().getFluidAmount());
	}

	@Test void isolatedResourceAccountDoesNotBlockOrdinaryFrozenOutputs() {
		var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
		var tile = new TileEntityMekApiary(net.minecraft.core.Holder.direct(state.getBlock()), net.minecraft.core.BlockPos.ZERO, state);
		var level = mock(ServerLevel.class);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		tile.setLevel(level);
		var processor = tile.getProduceProcessor();
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
		assertEquals(4, tile.getOutputSlots().stream().mapToInt(slot -> slot.getCount()).sum());
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
				net.minecraft.core.BlockPos.ZERO, null, upgrades, false, true, -1, null, null, 0, false, true);
		var resource = generator.prepare(slots, counts, group, type, outputs, null,
				net.minecraft.core.BlockPos.ZERO, null, upgrades, false, true, 6, null, null, 0, false, true);
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

	@Test
	void combBlockUpgradesPreserveBeeIdentityAndQuantityInBothProductionPaths() {
		var bee = new BeeSlot();
		bee.setBeeData(new CompoundTag());
		var slots = new BeeSlot[]{bee};
		var counts = new int[]{3};
		var group = new OrderedSlotIndex();
		group.reset(1);
		group.add(0);
		var upgrades = new ApiaryBatchUpgradeSnapshot(0, false, false, 2, false, true, false, false, false, false);
		var generator = new BeeProductionGenerator(this, new MultiFlowerProductionCache());

		for (var type : List.of(ResourceLocation.parse("productivebees:iron"),
				ResourceLocation.parse("productivebees:diamond"))) {
			var comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			comb.set(ModDataComponents.BEE_TYPE, type);
			var outputs = Map.of(comb,
					new ChancedOutput(
							Ingredient.EMPTY, 4, 4, 1));
			for (long rolls : new long[]{-1, 6}) {
				var generated = generator.prepare(slots, counts, group, type, outputs, null,
						BlockPos.ZERO, null, upgrades, false, true, rolls, null, null, 0, false, true);
				assertEquals(1, generated.items().size());
				var result = generated.items().getFirst();
				assertTrue(result.is(ModItems.CONFIGURABLE_COMB_BLOCK.get()));
				assertEquals(type, result.get(ModDataComponents.BEE_TYPE));
				assertEquals(24, result.getCount());
			}
			assertArrayEquals(new int[]{3}, counts);
			assertEquals(1, comb.getCount());
		}
	}

	@Test
	void combMappingsKeepProductionRunningWhenThePbHelperFails() {
		var inputs = List.of(new ItemStack(Items.HONEYCOMB, 7),
				new ItemStack(ModItems.HONEYCOMB_GHOSTLY.get(), 11),
				new ItemStack(ModItems.HONEYCOMB_MILKY.get(), 13),
				new ItemStack(ModItems.HONEYCOMB_POWDERY.get(), 17));
		var expected = List.of(new ItemStack(Blocks.HONEYCOMB_BLOCK, 7),
				new ItemStack(ModBlocks.COMB_GHOSTLY.get(), 11),
				new ItemStack(ModBlocks.COMB_MILKY.get(), 13),
				new ItemStack(ModBlocks.COMB_POWDERY.get(), 17));
		var converter = new CombBlockConverter();
		try (var mapping = mockStatic(BeeHelper.class)) {
			mapping.when(() -> BeeHelper.getCombBlockFromHoneyComb(any(ItemStack.class)))
					.thenThrow(new IllegalStateException("PB mapping failed"));
			var copied = converter.convertCombsToBlocks(inputs);
			var inPlace = new ArrayList<>(inputs);
			converter.convertCombsToBlocksInPlace(inPlace);
			for (int i = 0; i < expected.size(); i++) {
				assertTrue(ItemStack.matches(expected.get(i), copied.get(i)));
				assertTrue(ItemStack.matches(expected.get(i), inPlace.get(i)));
			}

			var unknown = mock(ItemStack.class);
			when(unknown.getItem()).thenReturn(mock(HoneycombItem.class));
			var mixed = new ArrayList<>(List.of(unknown, new ItemStack(Items.HONEYCOMB, 19)));
			converter.convertCombsToBlocksInPlace(mixed);
			assertSame(unknown, mixed.getFirst());
			assertTrue(ItemStack.matches(new ItemStack(Blocks.HONEYCOMB_BLOCK, 19), mixed.get(1)));

			var bee = new BeeSlot();
			bee.setBeeData(new CompoundTag());
			var group = new OrderedSlotIndex();
			group.reset(1);
			group.add(0);
			var upgrades = new ApiaryBatchUpgradeSnapshot(0, false, false, 1, false, true, false, false, false, false);
			var generator = new BeeProductionGenerator(this, new MultiFlowerProductionCache());
			var outputs = Map.of(new ItemStack(Items.HONEYCOMB),
					new ChancedOutput(Ingredient.EMPTY, 4, 4, 1));
			var produced = generator.prepare(new BeeSlot[]{bee}, new int[]{2}, group,
					ResourceLocation.parse("example:honeycomb"), outputs, null, BlockPos.ZERO, null,
					upgrades, false, true, -1, null, null, 0, false, true);
			assertTrue(ItemStack.matches(new ItemStack(Blocks.HONEYCOMB_BLOCK, 8), produced.items().getFirst()));
		}
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
