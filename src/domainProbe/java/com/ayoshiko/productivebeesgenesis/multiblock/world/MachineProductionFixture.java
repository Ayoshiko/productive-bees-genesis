package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeProgressPlan;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.*;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.mojang.authlib.GameProfile;
import java.util.*;
import mekanism.api.Upgrade;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** 仅以正式菜单交换和朝外端口投入、取回；物理参考机只提供能力对照。 */
final class MachineProductionFixture {
	final MachineProbeFixture structure;
	final MachineProductionLedger ledger;
	final ServerLevel level;
	private final ServerPlayer player;
	private final MachineMenu menu;
	private final IItemHandler input, output;
	private final IFluidHandler fluidIn, fluidOut;
	private final IEnergyStorage energy;
	private TileEntityMekApiary hive;
	private TileEntityMekCentrifuge centrifuge;

	MachineProductionFixture(ServerLevel level, MachineProbeFixture structure, MachineProductionLedger ledger) {
		this.level = level; this.structure = structure; this.ledger = ledger;
		player = FakePlayerFactory.get(level, new GameProfile(structure.core().ownerId(), "ProductionOwner"));
		new PlayerInventorySyncProbe(player); player.setPos(structure.pos().getCenter());
		menu = new MachineMenu(87, player.getInventory(), structure.core(), UUID.randomUUID()); player.containerMenu = menu;
		var in = port(StructureRole.INPUT_PORT); var out = port(StructureRole.OUTPUT_PORT); var power = port(StructureRole.ENERGY_PORT);
		input = level.getCapability(Capabilities.ItemHandler.BLOCK, in, face(in));
		output = level.getCapability(Capabilities.ItemHandler.BLOCK, out, face(out));
		fluidIn = level.getCapability(Capabilities.FluidHandler.BLOCK, in, face(in));
		fluidOut = level.getCapability(Capabilities.FluidHandler.BLOCK, out, face(out));
		energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, power, face(power));
		check(input != null && output != null && fluidIn != null && fluidOut != null && energy != null, "Missing real ports");
	}
	CombinedMachineWork work() { return structure.core().assets.work(); }
	private BlockPos port(StructureRole role) {
		return structure.world(structure.template().features().entrySet().stream()
				.filter(e -> e.getValue().roles().contains(role)).findFirst().orElseThrow().getKey());
	}
	private net.minecraft.core.Direction face(BlockPos pos) { return level.getBlockState(pos).getValue(MachinePartBlock.FACING); }
	void seed(boolean upgraded) {
		if (upgraded) {
			for (int slot = 0; slot < 4; slot++) install(slot, 2);
			for (boolean apiary : new boolean[]{true, false}) {
				install(MachineUpgrades.slot(apiary, PbUpgradeType.PRODUCTIVITY), 1);
				install(MachineUpgrades.slot(apiary, PbUpgradeType.TIME), 1);
			}
			install(MachineUpgrades.slot(false, PbUpgradeType.STABILITY), 1);
		}
		for (int slot = 0; slot < work().beeSlots(); slot++) {
			var data = new CompoundTag(); data.putString("entity", "productivebees:configurable_bee"); data.putString("type", "productivebees:iron");
			data.putUUID("UUID", UUID.randomUUID());
			var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any");
			genes.putString("bee_productivity", "productivity.normal");
			var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); data.put("neoforge:attachments", attachments);
			var cage = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get());
			cage.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
			exchange(MachineExchange.Action.CAGE_IN, slot, cage, 1);
			check(player.getInventory().items.getFirst().get(DataComponents.CUSTOM_DATA) == null, "Input cage kept its bee");
			exchange(MachineExchange.Action.FEED_IN, slot, new ItemStack(Items.IRON_BLOCK), 1);
		}
		var comb = ProductKeyCodec.item(work().bee(0).plan().sourceOutput(), 48, level.registryAccess());
		put(0, comb); refuel(); ledger.baseline(work()); reference();
	}
	void install(int slot, int count) { exchange(MachineExchange.Action.UPGRADE_IN, slot, MachineUpgradeProfiles.unit(slot).copyWithCount(count), count); }
	private void exchange(MachineExchange.Action action, int slot, ItemStack stack, int count) {
		player.getInventory().items.set(0, stack);
		var result = MachineExchange.exchange(menu, player, action, slot, 0, count, false);
		check(result.moved() == count, "Real exchange failed: " + action + "/" + slot + " " + result);
	}
	void refuel() { ledger.supply(energy.receiveEnergy(Integer.MAX_VALUE, false)); }
	void put(int slot, ItemStack stack) {
		var rest = input.insertItem(slot, stack, false); int count = stack.getCount() - rest.getCount();
		check(count > 0, "Fixture input rejected"); ledger.transfer(ProductKeyCodec.item(stack, level.registryAccess()), count);
	}
	void drain(int itemLimit, int fluidLimit) {
		for (int slot = 0; slot < output.getSlots(); slot++) {
			var stack = output.extractItem(slot, itemLimit, false);
			if (!stack.isEmpty()) ledger.transfer(ProductKeyCodec.item(stack, level.registryAccess()), -stack.getCount());
		}
		var fluid = fluidOut.drain(fluidLimit, IFluidHandler.FluidAction.EXECUTE);
		if (!fluid.isEmpty()) ledger.transfer(ProductKeyCodec.fluid(fluid, level.registryAccess()), -fluid.getAmount());
	}
	void fill() {
		drain(64, Integer.MAX_VALUE);
		var policy = new ProductPolicyRegistry(RuntimeProductPolicies.peek(level));
		var filler = CentrifugeRecipeIndex.get(ResourceLocation.parse("productivebees:iron")).value().getRecipeOutputs().keySet().stream()
				.filter(stack -> input.isItemValid(0, stack)).filter(stack -> {
					try {
						StaticCentrifugeAdapter.compile(level, policy, ProductKeyCodec.item(stack, level.registryAccess()), 0, MachineUpgradeProfiles.centrifuge(work().upgrades()));
						return false;
					} catch (IllegalArgumentException unsupported) { return true; }
				}).findFirst().orElseThrow(() -> new IllegalStateException("No admitted non-recipe filler"));
		for (int slot = 0; slot < input.getSlots(); slot++) put(slot, filler.copyWithCount(64));
		var honey = new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 64_000);
		int accepted = fluidIn.fill(honey, IFluidHandler.FluidAction.EXECUTE);
		check(accepted == 64_000, "Honey fixture did not fill all tanks");
		ledger.transfer(ProductKeyCodec.fluid(honey, level.registryAccess()), accepted); ledger.baseline(work());
	}
	boolean held() {
		return work().bees().size() == 6 && work().bees().stream().allMatch(bee -> !bee.frozen().isZero() && bee.progress() == 0)
				&& work().centrifuges().size() == 3 && work().centrifuges().values().stream().allMatch(delivery -> delivery.job().sampled() && !delivery.complete());
	}
	boolean stopAtBoundary() {
		for (var bee : work().bees()) if (bee.progress() == 0 && work().feeding().get(bee.slot()).count() > 0)
			exchange(MachineExchange.Action.FEED_OUT, bee.slot(), ItemStack.EMPTY, 1);
		return work().feeding().stream().allMatch(slot -> slot.count() == 0);
	}
	void returnAssets() {
		check(work().centrifuges().isEmpty(), "Return while centrifuges still own inputs");
		for (var bee : List.copyOf(work().bees())) {
			check(bee.progress() == 0 && bee.drained(), "Return before bee boundary");
			exchange(MachineExchange.Action.CAGE_OUT, bee.slot(), new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()), 1);
			check(com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection.contents(player.getInventory().items.getFirst())
					.equals(bee.originalSlot().copy().getCompound("entity_data")), "Returned bee NBT changed");
		}
		for (int slot = 0; slot < MachineUpgrades.SLOTS; slot++) {
			int count = work().upgrades().count(slot);
			if (count > 0) {
				exchange(MachineExchange.Action.UPGRADE_OUT, slot, ItemStack.EMPTY, count);
				check(ItemStack.matches(player.getInventory().items.getFirst(), MachineUpgradeProfiles.unit(slot).copyWithCount(count)), "Returned upgrades changed");
			}
		}
		ledger.baseline(work());
		check(work().bees().isEmpty() && work().buffer().items().stream().allMatch(cell -> cell.key() == null)
				&& work().buffer().fluids().stream().allMatch(cell -> cell.key() == null), "Final inventory not drained");
	}
	void reference() {
		var pos = structure.pos().south(24);
		level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true); level.getChunkAt(pos);
		level.setBlockAndUpdate(pos, ModBlocks.MEK_APIARY.get().defaultBlockState());
		hive = (TileEntityMekApiary) level.getBlockEntity(pos); hive.setOwnerUUID(player.getUUID()); hive.setControlType(RedstoneControl.HIGH);
		hive.setDirectEjectEnabled(false); hive.setDirectAeOutputEnabled(false); hive.setDirectContainerOutputEnabled(false);
		hive.setCentrifugePriorityEnabled(false); hive.setFeederConversionEnabled(false);
		level.setBlockAndUpdate(pos.east(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
		centrifuge = (TileEntityMekCentrifuge) level.getBlockEntity(pos.east()); centrifuge.setOwnerUUID(player.getUUID()); centrifuge.setControlType(RedstoneControl.HIGH);
		var upgrades = work().upgrades();
		for (int slot = 0; slot < MachineUpgrades.SLOTS; slot++) {
			int count = upgrades.count(slot); if (count == 0) continue;
			if (slot < 4) {
				var component = slot < 2 ? hive.getComponent() : centrifuge.getComponent();
				var type = slot % 2 == 0 ? Upgrade.SPEED : Upgrade.ENERGY;
				int delta = count - component.getUpgrades(type); check(delta >= 0, "Unexpected reference native upgrade");
				if (delta > 0) component.addUpgrades(type, delta);
			} else {
				var type = MachineUpgrades.pbType(slot); boolean apiary = MachineUpgrades.apiarySlot(slot);
				int delta = count - (apiary ? hive.getPbUpgradeCount(type) : centrifuge.getPbUpgradeInstalledCount(type));
				check(delta >= 0, "Unexpected reference PB upgrade");
				if (delta > 0) check((apiary ? hive.installPbUpgradeBulk(type, delta) : centrifuge.installPbUpgradeBulk(type, delta)) == delta, "Reference PB installation failed");
			}
		}
		compareReference();
	}
	void observe() {
		ledger.observe(work());
		for (var bee : work().bees()) if (bee.progress() == 1) {
			int ticks = BeeProgressPlan.cycleTicks(0, ModConfig.SERVER.apiaryProcessingTime.get(), hive.getApiaryUpgradeHandler().getTimeMultiplier(), false);
			check(bee.plan().cycleTicks() == ticks && bee.plan().energyPerTick() == hive.energyContainer().getEnergyPerTick()
					&& bee.plan().productionMultiplier() == hive.getApiaryUpgradeHandler().getProductivityMultiplier(), "Scheduled bee differs from physical reference");
		}
		for (var delivery : work().centrifuges().values()) if (delivery.job().progress() == 1) {
			var plan = delivery.job().plan(); int base = CentrifugeRecipeIndex.get(ResourceLocation.parse("productivebees:iron")).value().getProcessingTime();
			if (base <= 0) base = centrifuge.baseTicksRequired();
			check(plan.cycleTicks() == centrifuge.getTicksForBase(base) && plan.unitEnergyPerTick() == centrifuge.energyContainer().getEnergyPerTick()
					&& plan.maxParallel() == centrifuge.getOperationsPerTick() * centrifuge.productivityParallelModifier()
					&& plan.productivity() == centrifuge.productivityModifier() && plan.stability() == centrifuge.stabilityBonus(), "Scheduled job differs from physical reference");
		}
	}
	void compareReference() {
		var profile = MachineUpgradeProfiles.apiary(work().upgrades());
		check(profile.time() == hive.getApiaryUpgradeHandler().getTimeMultiplier()
				&& profile.productivity() == hive.getApiaryUpgradeHandler().getProductivityMultiplier()
				&& profile.energy() == hive.energyContainer().getEnergyPerTick(), "Apiary differs from physical reference");
		int ticks = BeeProgressPlan.cycleTicks(0, ModConfig.SERVER.apiaryProcessingTime.get(), hive.getApiaryUpgradeHandler().getTimeMultiplier(), false);
		check(ticks > 1, "Fixture bee period too short");
		var policy = new ProductPolicyRegistry(RuntimeProductPolicies.peek(level));
		var plan = StaticCentrifugeAdapter.compile(level, policy, work().bee(0).plan().sourceOutput(), work().upgrades().revision(),
				MachineUpgradeProfiles.centrifuge(work().upgrades()));
		var recipe = CentrifugeRecipeIndex.get(ResourceLocation.parse("productivebees:iron")).value();
		int base = recipe.getProcessingTime(); if (base <= 0) base = centrifuge.baseTicksRequired();
		check(plan.cycleTicks() == centrifuge.getTicksForBase(base) && plan.cycleTicks() > 1
				&& plan.unitEnergyPerTick() == centrifuge.energyContainer().getEnergyPerTick()
				&& plan.maxParallel() == centrifuge.getOperationsPerTick() * centrifuge.productivityParallelModifier()
				&& plan.productivity() == centrifuge.productivityModifier() && plan.stability() == centrifuge.stabilityBonus(),
				"Centrifuge differs from physical reference");
		var expected = new ArrayList<com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeRecipePlan.Output>();
		recipe.getRecipeOutputs().forEach((stack, amount) -> expected.add(new com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeRecipePlan.Output(
				ProductKeyCodec.item(stack, level.registryAccess()), amount.min(), amount.max(), amount.chance())));
		var fluid = recipe.getFluidOutputs();
		if (!fluid.isEmpty()) expected.add(new com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeRecipePlan.Output(
				ProductKeyCodec.fluid(fluid, level.registryAccess()), fluid.getAmount(), fluid.getAmount(), 1));
		check(new HashSet<>(expected).equals(new HashSet<>(plan.outputs())), "Reference recipe output amounts/chances differ");
	}
}
