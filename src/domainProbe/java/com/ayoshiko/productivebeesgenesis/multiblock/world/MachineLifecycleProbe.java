package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineRegion;
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
			check(server.getTickCount() - started < 2400, "Lifecycle timeout phase=" + phase + " state=" + (fixture == null ? null : fixture.core().status()));
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
					identity = fixture.core().machineId(); phase++;
				}
				case 1 -> {
					if (!fixture.core().formed() || !noise.core().formed()) return;
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
					until = server.getTickCount() + 30; phase++;
				}
				case 7 -> {
					check(!level.hasChunk(missing.x, missing.z) && !fixture.core().formed(), "Service force-loaded missing chunk");
					if (server.getTickCount() < until) return;
					force(level, fixture, true); phase++;
				}
				case 8 -> {
					if (!fixture.core().formed()) return;
					check(!MachineWorldService.active(level, oldBinding), "Reload revived old binding");
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
					force(level, fixture, true); phase++;
				}
				case 10 -> {
					if (!(level.getBlockEntity(fixture.pos()) instanceof MachineControllerEntity reloaded) || !reloaded.formed()) return;
					check(reloaded != fixture.core() && reloaded.machineId().equals(identity) && owner.equals(reloaded.ownerId()), "Controller identity/owner not restored");
					check(!MachineWorldService.active(level, oldBinding), "Controller reload revived old binding");
					fixture = new MachineProbeFixture(fixture.template(), fixture.pos(), fixture.facing(), reloaded);
					report.addProperty("controllerUnloadRecreatesHandleAndKeepsOwner", true);
					noise = MachineProbeFixture.place(level, fixture.template(), new BlockPos(1088, 128, 1008), Direction.NORTH, UUID.randomUUID());
					noise.core().loadWithComponents(reloaded.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
					check(!reloaded.formed() && reloaded.status() == MachineDirectory.State.RECOVERY && noise.core().status() == MachineDirectory.State.RECOVERY, "Replayed controller did not isolate both");
					noise.remove(level); force(level, noise, false); noise = null; phase++;
				}
				case 11 -> {
					if (!fixture.core().formed()) return;
					report.addProperty("duplicateControllerReplayAndRecovery", true);
					var core = fixture.core(); var valid = core.saveWithFullMetadata(level.registryAccess());
					for (String field : new String[]{"machine", "owner", "generation", "layout"}) {
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
					report.addProperty("cleanup", true); report.addProperty("passed", true); finish(event);
				}
			}
		} catch (Exception failure) {
			report.addProperty("passed", false); report.addProperty("failure", failure.toString());
			com.mojang.logging.LogUtils.getLogger().error("MACHINE_LIFECYCLE_PROBE_FAILED", failure); finish(event);
		}
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
		if (enabled()) { report.addProperty("normalShutdown", true); write(); fixture = noise = null; oldBinding = null; oldPart = null; }
	}
	private static void write() {
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception failure) { throw new IllegalStateException("Cannot write lifecycle report", failure); }
	}
	static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineLifecycleProbe() { }
}
