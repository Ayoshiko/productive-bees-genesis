package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实父类 ticker、直接 JDTE 委托和冻结前已取得的能力对象的行为断言。 */
final class MemberIsolationProbe {
	static void verify(ServerLevel level, JsonObject report) throws Exception {
		int tested = 0;
		for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
			String id = entry.getKey().location().toString();
			if (!id.startsWith("productivebeesgenesis:") || !(id.contains("mek_apiary") || id.contains("mek_centrifuge"))) continue;
			var pos = new BlockPos(2 + tested % 8, 100 + tested / 8, 2);
			level.setBlockAndUpdate(pos, entry.getValue().defaultBlockState());
			if (!(level.getBlockEntity(pos) instanceof TileEntityMekanism tile) || !(tile instanceof PbRecipeContext context)) continue;
			var host = (IAe2OutputHostBase) tile;
			var configuration = ((mekanism.common.tile.interfaces.ISideConfiguration) tile).getConfig();
			for (var transmission : java.util.List.of(mekanism.common.lib.transmitter.TransmissionType.ITEM, mekanism.common.lib.transmitter.TransmissionType.FLUID, mekanism.common.lib.transmitter.TransmissionType.ENERGY)) {
				var config = configuration.getConfig(transmission);
				var dataType = transmission == mekanism.common.lib.transmitter.TransmissionType.ENERGY ? mekanism.common.tile.component.config.DataType.INPUT : mekanism.common.tile.component.config.DataType.OUTPUT;
				for (var side : mekanism.api.RelativeSide.values()) config.setDataType(dataType, side);
			}
			tile.invalidateCapabilitiesFull();
			if (context.primaryOutputSlot(0) != null) context.primaryOutputSlot(0).setStack(new ItemStack(Items.GOLD_INGOT, 8));
			if (context.fluidOutputTank() != null) context.fluidOutputTank().setStack(new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 250));
			context.energyContainer().setEnergy(Math.min(100_000, context.energyContainer().getMaxEnergy()));
			tile.getComponent().getUpgradeSlot().setStack(new ItemStack(mekanism.common.registries.MekanismItems.SPEED_UPGRADE.get()));
			var itemHandlers = new ArrayList<net.neoforged.neoforge.items.IItemHandler>();
			var fluidHandlers = new ArrayList<net.neoforged.neoforge.fluids.capability.IFluidHandler>();
			var energyHandlers = new ArrayList<net.neoforged.neoforge.energy.IEnergyStorage>();
			var strictHandlers = new ArrayList<mekanism.api.energy.IStrictEnergyHandler>();
			for (var side : Direction.values()) {
				var items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side); if (items != null) itemHandlers.add(items);
				var fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side); if (fluids != null) fluidHandlers.add(fluids);
				var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side); if (energy != null) energyHandlers.add(energy);
				var strict = level.getCapability(mekanism.common.capabilities.Capabilities.STRICT_ENERGY.block(), pos, side); if (strict != null) strictHandlers.add(strict);
			}
			require(!itemHandlers.isEmpty() && !strictHandlers.isEmpty(), "Missing physical capabilities: " + id + " items=" + itemHandlers.size() + " FE=" + energyHandlers.size() + " strict=" + strictHandlers.size());
			TileEntityMekanism.tickServer(level, pos, tile.getBlockState(), tile);
			if (net.neoforged.fml.ModList.get().isLoaded("ae2")) {
				host.productivebeesgenesis$getAe2LifecycleHandler().prepareForLoad(host); host.productivebeesgenesis$getAe2LifecycleHandler().tryConnectNode(host);
				require(host.productivebeesgenesis$getAe2GridNode() != null, "Standalone AE2 node not created");
			}
			var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, new Origin(level.dimension().location().toString(), 1, 100, 1));
			MemberBinding.begin(tile, identity, UUID.randomUUID(), UUID.randomUUID());
			require(host.productivebeesgenesis$getAe2GridNode() == null, "Managed AE2 node survived freeze");
			int ticker = tile.ticker; long energy = context.energyContainer().getEnergy();
			var before = tile.saveWithFullMetadata(level.registryAccess());
			var accumulate = tile.getClass().getMethod("productivebeesgenesis$accumulateAcceleratedTicks", int.class);
			var flush = tile.getClass().getMethod("productivebeesgenesis$flushAcceleratedTicks");
			for (int i = 0; i < 256; i++) { TileEntityMekanism.tickServer(level, pos, tile.getBlockState(), tile); accumulate.invoke(tile, 256); flush.invoke(tile); }
			for (var handler : itemHandlers) {
				for (int slot = 0; slot < handler.getSlots(); slot++) {
					require(handler.extractItem(slot, 1, false).isEmpty() && handler.extractItem(slot, 1, true).isEmpty(), "Cached item capability extracted managed inventory");
					require(handler.insertItem(slot, new ItemStack(Items.GOLD_INGOT), false).getCount() == 1, "Cached item capability inserted into managed inventory");
					if (handler instanceof net.neoforged.neoforge.items.IItemHandlerModifiable mutable) mutable.setStackInSlot(slot, ItemStack.EMPTY);
				}
			}
			for (var handler : fluidHandlers) {
				require(handler.drain(100, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).isEmpty(), "Cached fluid capability drained managed tank");
				require(handler.fill(new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 100), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE) == 0, "Cached fluid capability filled managed tank");
			}
			for (var handler : energyHandlers) require(handler.extractEnergy(100, false) == 0 && handler.receiveEnergy(100, false) == 0, "Cached FE capability changed managed energy");
			for (var handler : strictHandlers) require(handler.extractEnergy(100, mekanism.api.Action.EXECUTE) == 0 && handler.insertEnergy(100, mekanism.api.Action.EXECUTE) == 100, "Cached strict capability changed managed energy");
			require(ticker == tile.ticker && energy == context.energyContainer().getEnergy() && tile.getComponent().getUpgrades(Upgrade.SPEED) == 0, "Managed parent components or energy executed: " + id);
			require(before.equals(tile.saveWithFullMetadata(level.registryAccess())), "Managed member state changed: " + id);
			require(host.productivebeesgenesis$getAe2StateHolder().getTickAccelTracker().getPendingVirtualTicks() == 0, "Managed acceleration credit accumulated");
			MemberBinding.release(tile, MemberBinding.read(tile));
			TileEntityMekanism.tickServer(level, pos, tile.getBlockState(), tile); require(tile.ticker == ticker + 1, "Standalone ticker did not resume");
			if (net.neoforged.fml.ModList.get().isLoaded("ae2")) require(host.productivebeesgenesis$getAe2GridNode() != null, "Returned member did not recreate AE2 node");
			tested++;
		}
		require(tested >= 10, "Insufficient machine variants tested"); report.addProperty("isolatedMachineVariants", tested);
		report.addProperty("managedParentAndJdteSuppressed", true); report.addProperty("cachedCapabilitiesBlocked", true);
	}
	private MemberIsolationProbe() { }
}
