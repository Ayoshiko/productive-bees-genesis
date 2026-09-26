package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineFormationProbe {
	private static final List<MachineProbeFixture> fixtures = new ArrayList<>();
	private static final JsonObject report = new JsonObject();
	private static int phase, started, maxSteps;
	private static long longest;
	private static MachineDirectory.Binding oldBinding;
	private static List<MachineDirectory.Binding> stableBindings;
	private static List<net.minecraft.nbt.CompoundTag> stableVisuals;
	private static int stableSince;
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.multiblock.enabled") || System.getProperty("pbg.multiblock.mode") != null || phase == 99) return;
		var server = event.getServer(); var level = server.overworld();
		if (server.getTickCount() < 40) return;
		try {
			if (started == 0) started = server.getTickCount();
			check(server.getTickCount() - started < 6000, "Formation probe timeout at " + phase + ": " + fixtures.stream().map(f -> f.core().status()).toList());
			var budget = NetworkTickService.budget(server);
			if (budget != null) {
				check(budget.attempts() <= ModConfig.SERVER.beeNetwork.totalSteps.get(), "Shared tick budget exceeded");
				maxSteps = Math.max(maxSteps, budget.used(NetworkTickService.Service.STRUCTURES.ordinal()));
				longest = Math.max(longest, budget.longestStepNanos(NetworkTickService.Service.STRUCTURES.ordinal()));
				check(maxSteps <= 32, "Structure service budget exceeded");
			}
			switch (phase) {
				case 0 -> {
					int index = 0;
					for (var template : CombinedApiaryDefinition.DEFINITION.candidates()) for (var facing : Direction.Plane.HORIZONTAL) {
						fixtures.add(MachineProbeFixture.place(level, template, new BlockPos(200 + index++ * 24, 128, 200), facing, UUID.randomUUID()));
					}
					phase++;
				}
				case 1 -> {
					if (!fixtures.stream().allMatch(f -> f.core().formed())) return;
					for (var f : fixtures) {
						check(f.core().getBlockState().getValue(MachinePartBlock.FORMED), "Formed blockstate missing");
						for (var local : f.template().features().keySet()) if (!f.world(local).equals(f.pos())) {
							check(level.getBlockEntity(f.world(local)) instanceof MachinePartEntity part && part.bound(), "Part missing current binding");
						}
						check(level.getBlockEntity(f.world(BlockPos.ZERO)) == null, "Frame acquired a ticker/entity");
					}
					var activeCore = fixtures.getFirst().core();
					var crossThreadRejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
						try { MachineWorldService.request(activeCore); return false; }
						catch (IllegalStateException expected) { return true; }
					}).join();
					check(crossThreadRejected && activeCore.formed(), "Off-thread request changed the machine");
					report.addProperty("crossThreadRequestRejected", true);
					report.addProperty("allLayoutsFourDirectionsAndParts", true);
					report.addProperty("formedLayoutCount", CombinedApiaryDefinition.DEFINITION.candidates().size());
					report.addProperty("formedMachineCount", fixtures.size());
					stableBindings = fixtures.stream().map(f -> f.core().handle.binding().orElseThrow()).toList();
					stableVisuals = fixtures.stream().map(f -> f.core().getUpdateTag(level.registryAccess())).toList();
					for (int i=0;i<fixtures.size();i++) {
						var frame = com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineVisualSnapshot.decode(stableVisuals.get(i)).orElseThrow();
						check(frame.template().orElseThrow().equals(fixtures.get(i).template()) && frame.facing() == fixtures.get(i).facing(), "Published wrong visual geometry");
						var bytes = new java.io.ByteArrayOutputStream(); net.minecraft.nbt.NbtIo.write(stableVisuals.get(i), new java.io.DataOutputStream(bytes));
						check(bytes.size() <= 256 && !stableVisuals.get(i).contains("owner"), "Visual tag leaked authority or exceeded byte limit");
						check(!fixtures.get(i).core().saveWithFullMetadata(level.registryAccess()).contains("revision"), "Transient visual revision was saved");
					}
					stableSince = server.getTickCount(); phase = 20;
				}
				case 20 -> {
					for (int i = 0; i < fixtures.size(); i++) {
						var core = fixtures.get(i).core();
						core.publishState();
						check(core.getUpdateTag(level.registryAccess()).equals(stableVisuals.get(i)), "Unchanged machine republished visual state");
						check(core.formed() && core.handle.binding().orElse(null) == stableBindings.get(i)
								&& core.getBlockState().getValue(MachinePartBlock.FORMED), "Read-only audit interrupted an intact machine");
					}
					if (server.getTickCount() - stableSince < 500) return;
					check(fixtures.stream().allMatch(f -> f.core().auditedAt > stableSince), "Stable audit never progressed");
					report.addProperty("readOnlyAuditsKeepAllBindingsFor500Ticks", true); stableBindings = null;
					report.addProperty("boundedVisualFramesRemainStableFor500Ticks", true); stableVisuals = null;
					var f = fixtures.getFirst(); oldBinding = f.core().handle.binding().orElseThrow();
					level.removeBlock(f.world(BlockPos.ZERO), false);
					check(!f.core().formed() && !MachineWorldService.active(level, oldBinding), "Removal left a live binding"); phase = 2;
				}
				case 2 -> {
					var f = fixtures.getFirst(); if (f.core().status() != MachineDirectory.State.UNFORMED) return;
					level.setBlockAndUpdate(f.world(BlockPos.ZERO), MachineContent.block(StructureRole.FRAME).defaultBlockState()); phase++;
				}
				case 3 -> {
					var f = fixtures.getFirst(); if (!f.core().formed()) return;
					check(!MachineWorldService.active(level, oldBinding), "Rebuild revived old binding");
					level.setBlockAndUpdate(f.world(new BlockPos(1, 2, 1)), Blocks.STONE.defaultBlockState());
					check(!f.core().formed(), "Foreign block in air did not invalidate"); phase++;
				}
				case 4 -> {
					var f = fixtures.getFirst(); if (f.core().status() != MachineDirectory.State.UNFORMED) return;
					level.removeBlock(f.world(new BlockPos(1, 2, 1)), false); phase++;
				}
				case 5 -> {
					var f = fixtures.getFirst(); if (!f.core().formed()) return;
					report.addProperty("removalAirObstructionAndTwoRebuilds", true);
					var port = f.world(new BlockPos(0, 1, 2));
					level.setBlockAndUpdate(port, level.getBlockState(port).setValue(MachinePartBlock.FACING, Direction.EAST));
					check(!f.core().formed(), "Rotation retained a live binding"); phase = 50;
				}
				case 50 -> {
					var f = fixtures.getFirst(); if (f.core().status() != MachineDirectory.State.UNFORMED) return;
					level.setBlockAndUpdate(f.world(new BlockPos(0, 1, 2)), MachineContent.block(StructureRole.INPUT_PORT).defaultBlockState().setValue(MachinePartBlock.FACING, Direction.WEST)); phase++;
				}
				case 51 -> {
					var f = fixtures.getFirst(); if (f.core().status() != MachineDirectory.State.UNFORMED) return;
					level.setBlockAndUpdate(f.world(new BlockPos(0, 1, 2)), MachineContent.block(StructureRole.ENERGY_PORT).defaultBlockState().setValue(MachinePartBlock.FACING, Direction.WEST)); phase++;
				}
				case 52 -> {
					if (!fixtures.getFirst().core().formed()) return;
					report.addProperty("wrongRoleAndFacingRejectedAndRepaired", true);
					for (var fixture : fixtures) fixture.remove(level); fixtures.clear();
					check(MachineWorldService.tracked(server) == 0, "Removed controllers remained queued");
					var template = CombinedApiaryDefinition.DEFINITION.candidates().getFirst();
					fixtures.add(MachineProbeFixture.place(level, template, new BlockPos(200, 128, 200), Direction.NORTH, UUID.randomUUID()));
					fixtures.add(MachineProbeFixture.place(level, template, new BlockPos(207, 128, 200), Direction.NORTH, UUID.randomUUID())); phase = 6;
				}
				case 6 -> {
					if (!fixtures.stream().allMatch(f -> f.core().formed())) return;
					report.addProperty("adjacentStructuresRemainIndependent", true);
					fixtures.removeLast().remove(level);
					fixtures.add(MachineProbeFixture.place(level, fixtures.getFirst().template(), new BlockPos(206, 128, 204), Direction.SOUTH, UUID.randomUUID())); phase++;
				}
				case 7 -> {
					if (!fixtures.stream().allMatch(f -> f.core().status() == MachineDirectory.State.RECOVERY)) return;
					check(fixtures.stream().noneMatch(f -> f.core().formed() || f.core().getBlockState().getValue(MachinePartBlock.FORMED)), "Conflict retained formed state");
					report.addProperty("sharedWallConflictsDisableBoth", true);
					for (var fixture : fixtures) fixture.remove(level); fixtures.clear();
					check(MachineWorldService.tracked(server) == 0, "Final controller cleanup failed");
					report.addProperty("noDuplicatedTickersAndCleanup", true); report.addProperty("passed", true); finish(event);
				}
			}
		} catch (Exception error) {
			report.addProperty("passed", false); report.addProperty("failure", error.toString());
			com.mojang.logging.LogUtils.getLogger().error("MACHINE_FORMATION_PROBE_FAILED", error); finish(event);
		}
	}
	private static void finish(ServerTickEvent.Post event) {
		phase = 99; report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2")); report.addProperty("maxStructureSteps", maxSteps);
		report.addProperty("longestStructureStepNanos", longest); report.addProperty("elapsedTicks", event.getServer().getTickCount() - started);
		write(); event.getServer().halt(false);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (Boolean.getBoolean("pbg.multiblock.enabled") && System.getProperty("pbg.multiblock.mode") == null) { report.addProperty("normalShutdown", true); write(); }
	}
	private static void write() {
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception error) { throw new IllegalStateException("Cannot write structure report", error); }
	}
	static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
