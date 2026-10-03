package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.*;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** M04d：正式调度、双机参考、满载、正常落盘及另一 JVM 分次恢复共用一份独立账目。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineProductionProbe {
	private static final JsonObject report = new JsonObject();
	private static final MachineProbeFixture[] structures = new MachineProbeFixture[2];
	private static final MachineProductionFixture[] fixtures = new MachineProductionFixture[2];
	private static final CombinedMachineWork[] held = new CombinedMachineWork[2], shutdown = new CombinedMachineWork[2];
	private static final long[] restartCycles = new long[2], restartJobs = new long[2];
	private static CompoundTag marker;
	private static int phase, started, stableTicks, oldCycle;
	private static boolean enabled() { return Boolean.getBoolean("pbg.multiblock.production"); }
	private static String mode() { return System.getProperty("pbg.multiblock.mode", ""); }

	@SubscribeEvent public static void started(ServerStartedEvent event) {
		if (!enabled()) return;
		var server = event.getServer(); var level = server.overworld();
		try {
			oldCycle = ModConfig.SERVER.apiaryProcessingTime.get(); ModConfig.SERVER.apiaryProcessingTime.set(40);
			marker = mode().equals("read") ? NbtIo.readCompressed(markerFile(server), NbtAccounter.unlimitedHeap()) : new CompoundTag();
			if (mode().equals("read")) check(marker.getLong("writerPid") != ProcessHandle.current().pid(), "Reader reused writer JVM");
			else marker.putLong("writerPid", ProcessHandle.current().pid());
			for (int i = 0; i < 2; i++) {
				var pos = new BlockPos(1008 + i * 80, 128, 1008);
				var template = CombinedApiaryDefinition.DEFINITION.candidates().get(i == 0 ? 0 : 5);
				if (mode().equals("write")) structures[i] = MachineProbeFixture.place(level, template, pos, Direction.NORTH, UUID.randomUUID());
				else {
					var shell = new MachineProbeFixture(template, pos, Direction.NORTH, null);
					MachineLifecycleProbe.force(level, shell, true);
					var core = (MachineControllerEntity) level.getBlockEntity(pos);
					check(core != null && !core.formed() && MachineWorkService.view(core).isEmpty(), "Loaded machine exposed authority before formation");
					structures[i] = new MachineProbeFixture(template, pos, Direction.NORTH, core);
				}
			}
			phase = 1; started = server.getTickCount();
		} catch (Exception failure) { fail(server, failure); }
	}
	@SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
		if (!enabled() || phase == 0 || phase == 99) return;
		var server = event.getServer();
		try {
			check(server.getTickCount() - started < 6000, "Production probe timeout at phase " + phase);
			for (var structure : structures) check(server.overworld().getEntitiesOfClass(ItemEntity.class,
					new AABB(structure.pos()).inflate(20)).isEmpty(), "Machine emitted an item entity");
			if (phase == 1) { attach(server); return; }
			check(MachineWorldService.tracked(server) == 2, "Parts contributed duplicate controller tickers");
			for (var fixture : fixtures) {
				check(fixture.structure.core().formed(), "Production lost formed authority");
				fixture.observe();
			}
			switch (phase) {
				case 2 -> {
					for (var fixture : fixtures) fixture.refuel();
					if (Arrays.stream(fixtures).allMatch(f -> f.ledger.jobsCompleted >= 3 && f.ledger.beeCycles >= 12
							&& f.work().centrifuges().size() == 3 && f.work().centrifuges().values().stream().noneMatch(d -> d.job().paid()))) {
						for (var fixture : fixtures) fixture.fill();
						report.addProperty("realScheduledProductionAndReference", true); phase = 3;
					}
				}
				case 3 -> {
					for (var fixture : fixtures) fixture.refuel();
					if (Arrays.stream(fixtures).allMatch(MachineProductionFixture::held)) {
						var other = fixtures[1].work(); var old = fixtures[0].work();
						fixtures[0].install(0, 1);
						check(fixtures[1].work() == other && fixtures[0].work().bees().equals(old.bees())
								&& fixtures[0].work().centrifuges().equals(old.centrifuges()), "Full-buffer upgrade changed old or foreign work");
						fixtures[0].reference();
						report.addProperty("localUpgradePreservesOldAndOtherWork", true); captureHeld(); phase = 4;
					}
				}
				case 4 -> {
					for (int i = 0; i < 2; i++) check(MachineRestartProbe.sameWork(held[i], fixtures[i].work()), "Full buffer continued billing, sampling or growing");
					if (++stableTicks >= 40) {
						report.addProperty("fullBufferStableFor40Ticks", true);
						if (mode().equals("write")) { checkpoint(server); return; }
						phase = 5;
					}
				}
				case 5 -> {
					for (var fixture : fixtures) { fixture.drain(7, 997); fixture.refuel(); }
					boolean resumed = true;
					for (int i = 0; i < 2; i++) resumed &= fixtures[i].ledger.beeCycles >= restartCycles[i] + 12
							&& fixtures[i].ledger.jobsCompleted >= restartJobs[i] + 3;
					if (resumed) { report.addProperty("partialDeliveryAndProductionResumed", true); phase = 6; }
				}
				case 6 -> {
					boolean stopped = true;
					for (var fixture : fixtures) {
						stopped &= fixture.stopAtBoundary(); fixture.drain(64, Integer.MAX_VALUE); fixture.refuel();
						stopped &= fixture.work().centrifuges().isEmpty() && fixture.work().bees().stream().allMatch(bee -> bee.drained() && bee.progress() == 0);
					}
					if (stopped) {
						for (var fixture : fixtures) fixture.returnAssets();
						report.addProperty("finalProductsEnergyAndReturnedAssetsConserved", true); checkpoint(server); return;
					}
				}
				default -> throw new IllegalStateException("Unknown production phase");
			}
			for (var fixture : fixtures) fixture.ledger.baseline(fixture.work());
		} catch (Exception failure) { fail(server, failure); }
	}
	private static void attach(MinecraftServer server) {
		if (mode().equals("read") && !report.has("checkpointRestoredBeforeFormation")) {
			for (var structure : structures) if (structure.core().assets == null) return;
			for (int i = 0; i < 2; i++) {
				check(!structures[i].core().formed(), "Missed pre-formation load boundary");
				check(MachineRestartProbe.sameWork(decode(marker.getCompound("work" + i)), structures[i].core().assets.work()), "Formal world load changed work");
			}
			report.addProperty("checkpointRestoredBeforeFormation", true); report.addProperty("newJvm", true);
		}
		for (var structure : structures) if (!structure.core().formed()) return;
		if (com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies.peek(server.overworld()) == null) return;
		for (int i = 0; i < 2; i++) {
			var work = structures[i].core().assets.work();
			var ledger = mode().equals("read") ? MachineProductionLedger.restore(marker.getCompound("ledger" + i), work) : new MachineProductionLedger(work);
			fixtures[i] = new MachineProductionFixture(server.overworld(), structures[i], ledger);
			if (mode().equals("write")) fixtures[i].seed(i == 0);
			else {
				check(fixtures[i].held(), "Restored paid results lost backpressure"); fixtures[i].reference();
				restartCycles[i] = ledger.beeCycles; restartJobs[i] = ledger.jobsCompleted;
			}
		}
		check(fixtures[0].work().beeSlots() == 6 && fixtures[1].work().beeSlots() == 6
				&& fixtures[0].work().lanes() == 3 && fixtures[1].work().lanes() == 3, "Layout changed machine capacity");
		report.addProperty("twoLayoutsOneCapacityEach", true);
		if (mode().equals("write")) phase = 2;
		else { captureHeld(); phase = 4; }
	}
	private static void captureHeld() {
		for (int i = 0; i < 2; i++) held[i] = fixtures[i].work();
		stableTicks = 0;
	}
	private static void checkpoint(MinecraftServer server) throws Exception {
		for (int i = 0; i < 2; i++) {
			fixtures[i].ledger.baseline(fixtures[i].work()); shutdown[i] = fixtures[i].work();
			marker.put("work" + i, CombinedWorkCodec.encode(shutdown[i])); marker.put("ledger" + i, fixtures[i].ledger.save());
			MachineWorldService.request(structures[i].core());
			check(MachineWorkService.view(structures[i].core()).isEmpty(), "Shutdown fence retained access");
		}
		if (mode().equals("write")) NbtIo.writeCompressed(marker, markerFile(server));
		report.addProperty("tickAccountingConserved", true); report.addProperty("noWorldDrops", true);
		report.addProperty("passed", true); finish(server);
	}
	private static CombinedMachineWork decode(CompoundTag tag) { return CombinedWorkCodec.decode(tag, key -> {}, key -> 64, food -> {}); }
	private static Path markerFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("multiblock-probe.dat"); }
	private static void fail(MinecraftServer server, Exception failure) {
		report.addProperty("passed", false); report.addProperty("failure", failure.toString());
		com.mojang.logging.LogUtils.getLogger().error("MACHINE_PRODUCTION_PROBE_FAILED phase={}", phase, failure); finish(server);
	}
	private static void finish(MinecraftServer server) {
		phase = 99; report.addProperty("mode", mode()); report.addProperty("production", true);
		report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2")); report.addProperty("pid", ProcessHandle.current().pid());
		report.addProperty("elapsedTicks", server.getTickCount() - started);
		var stats = new JsonArray();
		for (var fixture : fixtures) if (fixture != null) {
			var value = new JsonObject(); var ledger = fixture.ledger;
			value.addProperty("beeCycles", ledger.beeCycles); value.addProperty("jobsStarted", ledger.jobsStarted); value.addProperty("jobsCompleted", ledger.jobsCompleted);
			value.addProperty("observations", ledger.observations); value.addProperty("suppliedFE", ledger.suppliedEnergy); value.addProperty("spentFE", ledger.spentEnergy);
			value.addProperty("importedUnits", ledger.imported); value.addProperty("exportedUnits", ledger.exported); stats.add(value);
		}
		report.add("machines", stats); write(); server.halt(false);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (!enabled()) return;
		try {
			for (int i = 0; i < 2; i++) {
				check(shutdown[i] != null, "Missing final checkpoint");
				var file = event.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(MachineWorkService.dataName(shutdown[i].machine()) + ".dat");
				var data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
				check(MachineRestartProbe.sameWork(shutdown[i], decode(data.getCompound("work"))), "Shutdown did not persist complete work");
			}
			report.addProperty("normalAssetCheckpointSaved", true); report.addProperty("normalShutdown", true);
		} catch (Exception failure) { report.addProperty("passed", false); report.addProperty("shutdownFailure", failure.toString()); }
		finally { ModConfig.SERVER.apiaryProcessingTime.set(oldCycle); write(); }
	}
	private static void write() {
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception failure) { throw new IllegalStateException("Cannot write production report", failure); }
	}
	private MachineProductionProbe() { }
}
