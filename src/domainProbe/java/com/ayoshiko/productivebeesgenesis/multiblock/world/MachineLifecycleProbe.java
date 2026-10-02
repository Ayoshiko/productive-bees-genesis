package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineRegion;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 使用真实区块票据释放／重载，不以手工发送 Unload 事件替代生命周期。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineLifecycleProbe {
	private static final TicketType<ChunkPos> CORE_TICKET = TicketType.create("pbg_machine_probe", Comparator.comparingLong(ChunkPos::toLong));
	private static final JsonObject report = new JsonObject();
	private static MachineProbeFixture fixture, noise;
	private static MachineDirectory.Binding oldBinding;
	private static MachinePartEntity oldPart;
	private static ChunkPos missing;
	private static UUID identity, owner;
	private static MachineAssets heldAssets;
	private static MachineWorkService.Access retainedAccess;
	private static BlockCapabilityCache<IItemHandler, Direction> inputCache;
	private static IItemHandler inputPort, outputPort;
	private static IFluidHandler inputFluid, outputFluid;
	private static IEnergyStorage energyPort;
	private static com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork retainedWork;
	private static int phase, started, until, rounds, unloadEvents, maxSteps;
	private static boolean enabled() { return "lifecycle".equals(System.getProperty("pbg.multiblock.mode")); }
	@SubscribeEvent public static void unloaded(ChunkEvent.Unload event) {
		if (enabled() && event.getChunk().getPos().equals(missing)) unloadEvents++;
	}
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		if (!enabled() || phase == 99 || event.getServer().getTickCount() < 40) return;
		var server = event.getServer(); var level = server.overworld();
		try {
			if (started == 0) started = server.getTickCount();
			check(server.getTickCount() - started < 4800, "Lifecycle timeout phase=" + phase + " state=" + (fixture == null ? null : fixture.core().status()));
			var budget = NetworkTickService.budget(server);
			if (budget != null) {
				check(budget.attempts() <= ModConfig.SERVER.beeNetwork.totalSteps.get(), "Shared budget exceeded");
				maxSteps = Math.max(maxSteps, budget.used(NetworkTickService.Service.STRUCTURES.ordinal()));
				check(maxSteps <= 32, "Structure budget exceeded");
			}
			switch (phase) {
				case 0 -> {
					var template = CombinedApiaryDefinition.DEFINITION.candidates().getLast(); owner = UUID.randomUUID();
					fixture = MachineProbeFixture.place(level, template, new BlockPos(1008, 128, 1008), Direction.NORTH, owner);
					noise = MachineProbeFixture.place(level, template, new BlockPos(1088, 128, 1008), Direction.NORTH, UUID.randomUUID());
					identity = fixture.core().machineId();
					var inputPos = portPos(StructureRole.INPUT_PORT);
					inputCache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, inputPos, level.getBlockState(inputPos).getValue(MachinePartBlock.FACING));
					check(inputCache.getCapability() == null, "Unformed machine exposed a material capability"); phase++;
				}
				case 1 -> {
					if (!fixture.core().formed() || !noise.core().formed()) return;
					if (RuntimeProductPolicies.peek(level) == null) return;
					ports(level);
					heldAssets = fixture.core().assets; retainedWork = heldAssets.work(); retainedAccess = MachineWorkService.access(fixture.core()).orElseThrow();
					oldBinding = fixture.core().handle.binding().orElseThrow();
					level.setBlock(air(), Blocks.STONE.defaultBlockState(), 2);
					check(!fixture.core().formed() && !MachineWorldService.active(level, oldBinding), "Silent mutation retained binding"); phase++;
				}
				case 2 -> {
					if (fixture.core().status() != MachineDirectory.State.UNFORMED) return;
					level.setBlock(air(), Blocks.AIR.defaultBlockState(), 2); phase++;
				}
				case 3 -> {
					if (!fixture.core().formed()) return;
					report.addProperty("silentAirMutationAndRepair", true);
					// 故意绕过 LevelChunk，验证补漏审计而不是重复验证 Mixin。
					var pos = air(); level.getChunkAt(pos).getSection(level.getSectionIndex(pos.getY())).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, Blocks.STONE.defaultBlockState());
					phase++;
				}
				case 4 -> {
					MachineWorldService.request(noise.core());
					if (fixture.core().status() != MachineDirectory.State.UNFORMED) return;
					report.addProperty("auditProgressesWhileAnotherScanKeepsQueueBusy", true);
					level.setBlock(air(), Blocks.AIR.defaultBlockState(), 2);
					noise.remove(level); force(level, noise, false); noise = null; phase++;
				}
				case 5 -> {
					if (!fixture.core().formed()) return;
					oldBinding = fixture.core().handle.binding().orElseThrow();
					var partPos = fixture.world(new BlockPos(2, 1, fixture.template().geometry().size().depth() - 1));
					oldPart = (MachinePartEntity) level.getBlockEntity(partPos); check(oldPart.bound(), "Part not initially bound");
					missing = new ChunkPos(partPos); unloadEvents = 0;
					var controllerChunk = new ChunkPos(fixture.pos());
					check(!missing.equals(controllerChunk), "Fixture must cross a chunk boundary");
					level.getChunkSource().addRegionTicket(CORE_TICKET, controllerChunk, 0, controllerChunk);
					force(level, fixture, false); phase++;
				}
				case 6 -> {
					if (level.hasChunk(missing.x, missing.z)) return;
					check(!fixture.core().formed() && !MachineWorldService.active(level, oldBinding) && !oldPart.bound(), "Unload retained old machine/part");
					check(fixture.core().getBlockState().getValue(MachineControllerBlock.STATUS) == MachineVisualState.WAITING, "Missing chunk did not project waiting state");
					report.addProperty("waitingStateProjectedWithoutChunkLoad", true);
					until = server.getTickCount() + 30; phase++;
				}
				case 7 -> {
					check(!level.hasChunk(missing.x, missing.z) && !fixture.core().formed(), "Service force-loaded missing chunk");
					check(MachineWorkService.view(fixture.core()).isEmpty() && !MachineWorkService.commit(retainedAccess, retainedWork.receiveEnergy(1)), "Unloaded region allowed asset access");
					check(heldAssets.work() == retainedWork, "Partial unload changed held assets");
					check(energyPort.receiveEnergy(1, false) == 0 && outputPort.extractItem(5, 1, false).isEmpty() && inputFluid.fill(honey(1), IFluidHandler.FluidAction.EXECUTE) == 0, "Cached port changed unloaded assets");
					if (server.getTickCount() < until) return;
					force(level, fixture, true); phase++;
				}
				case 8 -> {
					if (!fixture.core().formed()) return;
					check(!MachineWorldService.active(level, oldBinding), "Reload revived old binding");
					check(!MachineWorkService.commit(retainedAccess, retainedWork.receiveEnergy(1)), "Reformation revived an old asset operation");
					check(energyPort.receiveEnergy(1, false) == 0 && inputPort.getSlots() == 0 && outputFluid.drain(1, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "Old port capability revived after formation");
					check(inputCache.getCapability() != null && inputCache.getCapability() != inputPort, "Capability cache did not refresh after formation");
					check(!oldPart.isRemoved() || !oldPart.bound(), "Reload revived removed part");
					check(fixture.core().machineId().equals(identity), "Partial unload changed identity");
					if (++rounds < 2) { phase = 5; return; }
					report.addProperty("twoRealPartialChunkAvailabilityLossesAndReloads", true);
					report.addProperty("partialPhysicalUnloadEvents", unloadEvents);
					report.addProperty("missingChunkNotForceLoadedAndOldBindingRevoked", true);
					oldBinding = fixture.core().handle.binding().orElseThrow();
					force(level, fixture, false); var controllerChunk = new ChunkPos(fixture.pos());
					level.getChunkSource().removeRegionTicket(CORE_TICKET, controllerChunk, 0, controllerChunk); phase++;
				}
				case 9 -> {
					if (!fixture.core().isRemoved() || MachineWorldService.tracked(server) != 0) return;
					check(!MachineWorldService.active(level, oldBinding), "Controller unload retained binding");
					check(oldPart.isRemoved() && !oldPart.bound(), "Full unload retained old part");
					check(fixture.core().assets == null && MachineWorkService.view(fixture.core()).isEmpty() && heldAssets.work() == retainedWork, "Controller unload lost assets or retained live access");
					force(level, fixture, true); phase++;
				}
				case 10 -> {
					if (!(level.getBlockEntity(fixture.pos()) instanceof MachineControllerEntity reloaded) || !reloaded.formed()) return;
					check(reloaded != fixture.core() && reloaded.machineId().equals(identity) && owner.equals(reloaded.ownerId()), "Controller identity/owner not restored");
					check(!MachineWorldService.active(level, oldBinding), "Controller reload revived old binding");
					fixture = new MachineProbeFixture(fixture.template(), fixture.pos(), fixture.facing(), reloaded);
					report.addProperty("controllerUnloadRecreatesHandleAndKeepsOwner", true);
					check(MachineWorkService.view(reloaded).orElseThrow() == retainedWork, "Reload replaced the sole asset root");
					report.addProperty("assetAccessClosesAndRootSurvivesRealUnload", true);
					noise = MachineProbeFixture.place(level, fixture.template(), new BlockPos(1088, 128, 1008), Direction.NORTH, UUID.randomUUID());
					noise.core().loadWithComponents(reloaded.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
					check(!reloaded.formed() && reloaded.status() == MachineDirectory.State.RECOVERY && noise.core().status() == MachineDirectory.State.RECOVERY, "Replayed controller did not isolate both");
					noise.remove(level); force(level, noise, false); noise = null; phase++;
				}
				case 11 -> {
					if (!fixture.core().formed()) return;
					report.addProperty("duplicateControllerReplayAndRecovery", true);
					check(MachineWorkService.view(fixture.core()).orElseThrow() == retainedWork, "Duplicate replay changed asset root");
					var core = fixture.core(); var valid = core.saveWithFullMetadata(level.registryAccess());
					for (String field : new String[]{"machine", "owner", "generation", "layout", "storageVersion", "assetReferenced"}) {
						var broken = valid.copy(); broken.remove(field); core.loadWithComponents(broken, level.registryAccess());
						check(!core.formed() && core.status() == MachineDirectory.State.RECOVERY, "Missing identity field accepted: " + field);
						core.initializeOwner(UUID.randomUUID()); check(!core.readyIdentity(), "Malformed identity adopted by placer");
					}
					var wrongType = valid.copy(); wrongType.putString("generation", "1"); core.loadWithComponents(wrongType, level.registryAccess());
					check(core.status() == MachineDirectory.State.RECOVERY, "Wrong generation NBT type accepted");
					core.loadWithComponents(valid, level.registryAccess()); phase++;
				}
				case 12 -> {
					if (!fixture.core().formed()) return;
					report.addProperty("malformedIdentityFailsClosed", true);
					fixture.remove(level); force(level, fixture, false);
					check(MachineWorldService.tracked(server) == 0, "Removed controller retained by service");
					check(fixture.core().assets == null && heldAssets.work() == retainedWork && heldAssets.work().energy() == 123, "Breaking controller discarded assets");
					report.addProperty("removedControllerKeepsAssetsWithoutAccess", true);
					report.addProperty("portCachesStayRevokedAcrossUnloadAndReformation", true);
					report.addProperty("cleanup", true); report.addProperty("passed", true); finish(event);
				}
			}
		} catch (Exception failure) {
			report.addProperty("passed", false); report.addProperty("failure", failure.toString());
			com.mojang.logging.LogUtils.getLogger().error("MACHINE_LIFECYCLE_PROBE_FAILED", failure); finish(event);
		}
	}

	private static BlockPos portPos(StructureRole role) {
		var local = fixture.template().features().entrySet().stream().filter(entry -> entry.getValue().roles().contains(role)).findFirst().orElseThrow().getKey();
		return fixture.world(local);
	}
	private static FluidStack honey(int amount) { return new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), amount); }
	private static void ports(ServerLevel level) {
		for (var role : new StructureRole[]{StructureRole.ENERGY_PORT, StructureRole.INPUT_PORT, StructureRole.OUTPUT_PORT, StructureRole.INTERFACE}) {
			var pos = portPos(role); var front = level.getBlockState(pos).getValue(MachinePartBlock.FACING);
			for (Direction side : Direction.values()) {
				check((level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null) == (role == StructureRole.ENERGY_PORT && side == front), "Wrong energy face/role");
				check((level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null) == ((role == StructureRole.INPUT_PORT || role == StructureRole.OUTPUT_PORT) && side == front), "Wrong item face/role");
				check((level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null) == ((role == StructureRole.INPUT_PORT || role == StructureRole.OUTPUT_PORT) && side == front), "Wrong fluid face/role");
			}
			check(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) == null && level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) == null, "Unsided port bypassed exterior face");
		}
		inputPort = inputCache.getCapability(); check(inputPort != null, "Formation did not invalidate the cached null capability");
		var outputPos = portPos(StructureRole.OUTPUT_PORT); var energyPos = portPos(StructureRole.ENERGY_PORT); var inputPos = portPos(StructureRole.INPUT_PORT);
		outputPort = level.getCapability(Capabilities.ItemHandler.BLOCK, outputPos, level.getBlockState(outputPos).getValue(MachinePartBlock.FACING));
		inputFluid = level.getCapability(Capabilities.FluidHandler.BLOCK, inputPos, level.getBlockState(inputPos).getValue(MachinePartBlock.FACING));
		outputFluid = level.getCapability(Capabilities.FluidHandler.BLOCK, outputPos, level.getBlockState(outputPos).getValue(MachinePartBlock.FACING));
		energyPort = level.getCapability(Capabilities.EnergyStorage.BLOCK, energyPos, level.getBlockState(energyPos).getValue(MachinePartBlock.FACING));
		var original = fixture.core().assets.work();
		check(energyPort.receiveEnergy(Integer.MAX_VALUE, true) == 1_000_000 && fixture.core().assets.work() == original, "Simulated FE transfer changed assets");
		check(energyPort.receiveEnergy(123, false) == 123 && energyPort.getEnergyStored() == 123 && energyPort.extractEnergy(123, false) == 0, "Energy port direction or balance differs");
		var recipe = com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.getBeeProductionRecipe(level, net.minecraft.resources.ResourceLocation.parse("productivebees:iron"));
		var standard = recipe.value().getRecipeOutputs().keySet().iterator().next().copyWithCount(20);
		check(inputPort.isItemValid(5, standard), "Fixture bee product is not admitted");
		var named = standard.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("port variant"));
		var snapshot = fixture.core().assets.work();
		var simulatedRemainder = inputPort.insertItem(5, standard, true);
		check(fixture.core().assets.work() == snapshot, "Simulated item transfer changed assets");
		check(simulatedRemainder.isEmpty(), "Simulated item acceptance differs from capacity");
		check(inputPort.insertItem(5, standard, false).isEmpty() && standard.getCount() == 20, "Input port did not return exact remainder");
		check(outputPort.getStackInSlot(0).isEmpty() && outputPort.getStackInSlot(5).getCount() == 20, "Item transfer used the wrong physical slot");
		check(inputPort.insertItem(5, named, false).getCount() == 20 && inputPort.insertItem(6, named, false).isEmpty(), "Component variants merged or valid variant rejected");
		var returned = outputPort.getStackInSlot(5); returned.setCount(1); check(outputPort.getStackInSlot(5).getCount() == 20, "Projection mutated authority");
		check(inputPort.extractItem(5, 20, false).isEmpty() && outputPort.insertItem(5, standard, false).getCount() == 20, "Material role direction bypassed");
		check(inputPort.insertItem(7, new ItemStack(Items.BARRIER), false).getCount() == 1, "Non-product entered the product buffer");
		var pearls = new ItemStack(Items.ENDER_PEARL, 64);
		check(inputPort.insertItem(7, pearls, false).getCount() == 48 && outputPort.getStackInSlot(7).getCount() == 16, "Actual item stack limit ignored");
		snapshot = fixture.core().assets.work(); check(outputPort.extractItem(5, 5, true).getCount() == 5 && fixture.core().assets.work() == snapshot, "Simulated extraction changed assets");
		check(outputPort.extractItem(5, 64, false).getCount() == 20 && outputPort.extractItem(6, 64, false).getCount() == 20
				&& outputPort.extractItem(7, 64, false).getCount() == 16, "Item amount was lost or duplicated");
		snapshot = fixture.core().assets.work();
		check(inputFluid.fill(honey(100_000), IFluidHandler.FluidAction.SIMULATE) == 64_000 && fixture.core().assets.work() == snapshot, "Simulated fluid fill changed assets");
		check(inputFluid.fill(honey(100_000), IFluidHandler.FluidAction.EXECUTE) == 64_000
				&& inputFluid.fill(honey(1), IFluidHandler.FluidAction.EXECUTE) == 0, "Finite tank capacity ignored");
		check(inputFluid.drain(1, IFluidHandler.FluidAction.EXECUTE).isEmpty() && outputFluid.fill(honey(1), IFluidHandler.FluidAction.EXECUTE) == 0, "Fluid role direction bypassed");
		snapshot = fixture.core().assets.work();
		check(outputFluid.drain(honey(20_000), IFluidHandler.FluidAction.SIMULATE).getAmount() == 20_000 && fixture.core().assets.work() == snapshot, "Simulated fluid drain changed assets");
		check(outputFluid.drain(honey(10_000), IFluidHandler.FluidAction.EXECUTE).getAmount() == 10_000
				&& outputFluid.drain(100_000, IFluidHandler.FluidAction.EXECUTE).getAmount() == 54_000, "Fluid amount was lost or duplicated");
		check(fixture.core().assets.work().energy() == 123 && fixture.core().assets.work().buffer().items().stream().allMatch(cell -> cell.key() == null)
				&& fixture.core().assets.work().buffer().fluids().stream().allMatch(cell -> cell.key() == null), "Port fixture left unaccounted material");
		report.addProperty("rolePortsFaceComponentsLimitsAndSimulation", true);
	}
	private static BlockPos air() { return fixture.world(new BlockPos(1, 2, 1)); }
	static void force(ServerLevel level, MachineProbeFixture value, boolean forced) {
		for (var section : MachineRegion.at(value.template().geometry(), value.pos(), value.facing()).sections()) {
			level.setChunkForced(section.x(), section.z(), forced);
			if (forced) level.getChunk(section.x(), section.z());
		}
	}
	private static void finish(ServerTickEvent.Post event) {
		phase = 99; report.addProperty("mode", "lifecycle"); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
		report.addProperty("maxStructureSteps", maxSteps); report.addProperty("elapsedTicks", event.getServer().getTickCount() - started);
		write(); event.getServer().halt(false);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (enabled()) { report.addProperty("normalShutdown", true); write(); fixture = noise = null; oldBinding = null; oldPart = null; heldAssets = null; retainedWork = null; retainedAccess = null; inputCache = null; inputPort = outputPort = null; inputFluid = outputFluid = null; energyPort = null; }
	}
	private static void write() {
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception failure) { throw new IllegalStateException("Cannot write lifecycle report", failure); }
	}
	static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineLifecycleProbe() { }
}
