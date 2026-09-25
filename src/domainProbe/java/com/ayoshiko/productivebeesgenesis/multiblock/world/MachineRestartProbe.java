package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** writer 正常保存世界，reader 在另一 JVM 的正式加载入口恢复身份后重新形成。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineRestartProbe {
	private static final BlockPos POSITION = new BlockPos(1008, 128, 1008), BAD_POSITION = new BlockPos(1088, 128, 1008);
	private static final JsonObject report = new JsonObject();
	private static MachineProbeFixture fixture, bad;
	private static CompoundTag marker;
	private static int phase, started;
	private static String mode() { return System.getProperty("pbg.multiblock.mode", ""); }
	private static boolean enabled() { return mode().equals("write") || mode().equals("read"); }
	@SubscribeEvent public static void started(ServerStartedEvent event) {
		if (!enabled()) return;
		var server = event.getServer(); var level = server.overworld();
		try {
			var template = CombinedApiaryDefinition.DEFINITION.candidates().getLast();
			if (mode().equals("write")) {
				fixture = MachineProbeFixture.place(level, template, POSITION, Direction.NORTH, UUID.randomUUID());
				bad = MachineProbeFixture.place(level, template, BAD_POSITION, Direction.NORTH, UUID.randomUUID());
				var broken = bad.core().saveWithFullMetadata(level.registryAccess()); broken.remove("owner");
				bad.core().loadWithComponents(broken, level.registryAccess()); bad.core().setChanged();
				marker = new CompoundTag(); marker.putLong("writerPid", ProcessHandle.current().pid());
				marker.putUUID("machine", fixture.core().machineId()); marker.putUUID("owner", fixture.core().ownerId());
				marker.putLong("generation", fixture.core().generation());
			} else {
				marker = NbtIo.readCompressed(markerFile(server), NbtAccounter.unlimitedHeap());
				check(marker.getLong("writerPid") != ProcessHandle.current().pid(), "Restart reused writer JVM");
				// force 仅属于夹具，正式机器服务不持区块票据。
				for (var pos : new BlockPos[]{POSITION, BAD_POSITION}) {
					var shell = new MachineProbeFixture(template, pos, Direction.NORTH, null);
					MachineLifecycleProbe.force(level, shell, true);
				}
				fixture = new MachineProbeFixture(template, POSITION, Direction.NORTH, (MachineControllerEntity) level.getBlockEntity(POSITION));
				bad = new MachineProbeFixture(template, BAD_POSITION, Direction.NORTH, (MachineControllerEntity) level.getBlockEntity(BAD_POSITION));
				check(fixture.core() != null && bad.core() != null, "Saved controllers missing");
				check(!fixture.core().formed() && !bad.core().formed(), "NBT restored formed authority before scanning");
				report.addProperty("noFormedAuthorityBeforeFirstTick", true);
				report.addProperty("newJvm", true);
			}
			phase = 1;
		} catch (Exception failure) { fail(server, failure); }
	}
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		if (!enabled() || phase != 1) return;
		var server = event.getServer();
		try {
			if (started == 0) started = server.getTickCount();
			check(server.getTickCount() - started < 600, "Restart formation timeout");
			check(bad.core().status() == MachineDirectory.State.RECOVERY && !bad.core().readyIdentity(), "Malformed saved identity became usable");
			if (!fixture.core().formed()) return;
			var core = fixture.core();
			check(core.machineId().equals(marker.getUUID("machine")) && core.ownerId().equals(marker.getUUID("owner"))
					&& core.generation() == marker.getLong("generation"), "Saved identity changed");
			check(MachineWorldService.tracked(server) == 1, "Duplicate ticker or malformed controller registered");
			for (var local : fixture.template().features().keySet()) if (!fixture.world(local).equals(POSITION)) {
				check(server.overworld().getBlockEntity(fixture.world(local)) instanceof MachinePartEntity part && part.bound(), "Restored part unbound");
			}
			report.addProperty("identityOwnerAndGenerationRetained", true);
			report.addProperty("formalQueueReformedAndPartsBound", true);
			report.addProperty("malformedIdentityRemainsIsolated", true);
			report.addProperty("singleRegisteredController", true);
			if (mode().equals("write")) NbtIo.writeCompressed(marker, markerFile(server));
			report.addProperty("passed", true); finish(server);
		} catch (Exception failure) { fail(server, failure); }
	}
	private static Path markerFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("multiblock-probe.dat"); }
	private static void fail(MinecraftServer server, Exception failure) {
		report.addProperty("passed", false); report.addProperty("failure", failure.toString());
		com.mojang.logging.LogUtils.getLogger().error("MACHINE_RESTART_PROBE_FAILED", failure); finish(server);
	}
	private static void finish(MinecraftServer server) {
		phase = 99; report.addProperty("mode", mode()); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
		report.addProperty("pid", ProcessHandle.current().pid()); report.addProperty("elapsedTicks", server.getTickCount() - started);
		write(); server.halt(false);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (enabled()) { report.addProperty("normalShutdown", true); write(); fixture = bad = null; marker = null; }
	}
	private static void write() {
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception failure) { throw new IllegalStateException("Cannot write restart report", failure); }
	}
	private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineRestartProbe() { }
}
