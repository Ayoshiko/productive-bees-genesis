package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticRestartFixture.require;

/** 三种拓扑 × 三种停服状态；每次运行独占世界，正常退出后另起 JVM 读取副本。 */
public final class AutomaticRestartProbe {
	static final long MAINTENANCE = 7;
	private static final UUID JVM = UUID.randomUUID();
	private static final List<AutomaticRestartFixture> fixtures = new ArrayList<>();
	private static final Map<UUID, NetworkCheckpoint> shutdown = new ConcurrentHashMap<>();
	private static boolean reading;
	private static int started, phase, until;
	private static long producerPid;
	private static com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity unbound;
	private static int maxChecks, observedTicks;
	private static final long[] checks = new long[com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.Service.values().length], longestStepNanos = new long[com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.Service.values().length];
	public static void start(MinecraftServer server) throws Exception {
		reading = "read".equals(System.getProperty("pbg.automatic.mode")); started = server.getTickCount();
		ModConfig.SERVER.beeNetwork.enabled.set(!reading); ModConfig.SERVER.beeNetwork.maintenanceFe.set(MAINTENANCE);
		var level = server.overworld();
		if (reading) {
			var emptyPos = new BlockPos(328, 160, 8); level.setChunkForced(emptyPos.getX() >> 4, 0, true);
			level.setBlockAndUpdate(emptyPos, com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent.CORE.get().defaultBlockState());
			unbound = (com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity) level.getBlockEntity(emptyPos);
			unbound.initializeOwner(UUID.randomUUID());
			var root = NbtIo.readCompressed(manifest(server), NbtAccounter.unlimitedHeap());
			require(!JVM.equals(root.getUUID("producerJvm")), "Automatic restart reused writer JVM"); producerPid = root.getLong("producerPid");
			for (var raw : root.getList("fixtures", Tag.TAG_COMPOUND)) {
				var tag = (CompoundTag) raw; var pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
				level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true); level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
				var fixture = new AutomaticRestartFixture(AutomaticRestartFixture.Kind.valueOf(tag.getString("kind")), AutomaticRestartFixture.Mode.valueOf(tag.getString("mode")), pos);
				fixture.load(level, tag); fixtures.add(fixture);
			}
			require(fixtures.size() == 9, "Incomplete automatic restart matrix");
		} else {
			for (var kind : AutomaticRestartFixture.Kind.values()) for (var mode : AutomaticRestartFixture.Mode.values()) {
				var pos = new BlockPos(168 + fixtures.size() * 16, 160, 8); level.setChunkForced(pos.getX() >> 4, 0, true);
				var fixture = new AutomaticRestartFixture(kind, mode, pos); fixture.create(level); fixtures.add(fixture);
			}
		}
	}
	public static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		var budget = NetworkTickService.budget(server);
		if (budget != null) {
			require(budget.attempts() <= ModConfig.SERVER.beeNetwork.totalSteps.get(), "Automatic restart exceeded shared work budget");
			maxChecks = Math.max(maxChecks, budget.attempts()); observedTicks++;
			for (int i = 0; i < checks.length; i++) {
				checks[i] += budget.used(i); longestStepNanos[i] = Math.max(longestStepNanos[i], budget.longestStepNanos(i));
			}
		}
		require(server.getTickCount() - started < 2500, "Automatic restart timeout at phase " + phase + ": " + fixtures.stream().map(f -> f.kind + "/" + f.mode + "/" + f.ready + "/" + f.finished + "/" + f.core.runtime().status()).toList());
		var level = server.overworld();
		for (var fixture : fixtures) {
			require(fixture.core.ownership().status() != CoreOwnershipController.Status.RECOVERY, fixture.core.ownership().failure());
			require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
					new net.minecraft.world.phys.AABB(fixture.pos).inflate(3)).isEmpty(), "Automatic restart spawned an item entity");
		}
		if (phase == 0) {
			for (var fixture : fixtures) {
				if (fixture.ready || fixture.core.topology() == null || fixture.core.ownership().busy()) continue;
				if (!reading && !fixture.joined) { require(fixture.core.ownership().command(true), "Automatic restart takeover failed"); fixture.joined = true; continue; }
				if (fixture.core.ownership().status() != CoreOwnershipController.Status.MANAGED) continue;
				if (reading) fixture.restored(level);
				else { if (fixture.data == null) fixture.start(level); fixture.prepareShutdown(); }
			}
			if (!fixtures.stream().allMatch(f -> f.ready)) return false;
			if (!reading) {
				// 停在同一明确保存边界；保留核心意图，配置门防止本 tick 剩余回调推进。
				ModConfig.SERVER.beeNetwork.enabled.set(false);
				var root = new CompoundTag(); var list = new ListTag();
				for (var fixture : fixtures) { fixture.redstone(RedstoneControl.DISABLED); fixture.saved = fixture.data.checkpoint(); list.add(fixture.manifest()); }
				root.put("fixtures", list); root.putUUID("producerJvm", JVM); root.putLong("producerPid", ProcessHandle.current().pid()); NbtIo.writeCompressed(root, manifest(server));
				return finish(server, report);
			}
			until = server.getTickCount() + 20; phase = 1; return false;
		}
		if (phase == 1) {
			for (var fixture : fixtures) require(fixture.saved.equals(fixture.data.checkpoint()), "Disabled recovered network advanced or charged maintenance");
			require(unbound.network() == null && unbound.topology() == null && !unbound.ownership().command(true), "Disabled recovery admitted a new network");
			if (server.getTickCount() < until) return false;
			ModConfig.SERVER.beeNetwork.enabled.set(true); until = server.getTickCount() + 30; phase = 2; return false;
		}
		for (var fixture : fixtures) fixture.observe();
		if (phase == 2) {
			for (var fixture : fixtures) if (fixture.mode != AutomaticRestartFixture.Mode.RUNNING) require(fixture.saved.equals(fixture.data.checkpoint()), "Paused/starved restart advanced before release");
			if (server.getTickCount() < until) return false;
			for (var fixture : fixtures) {
				if (fixture.mode == AutomaticRestartFixture.Mode.PAUSED) require(fixture.core.setProductionRunning(true), "Recovered pause could not resume");
				if (fixture.mode == AutomaticRestartFixture.Mode.STARVED) fixture.charge(level, 1_000_000);
			}
			phase = 3;
		}
		if (phase == 3 && fixtures.stream().allMatch(f -> f.finished)) { phase = 4; until = server.getTickCount() + 40; }
		return phase == 4 && server.getTickCount() >= until && finish(server, report);
	}
	private static boolean finish(MinecraftServer server, JsonObject report) {
		var rows = new JsonArray();
		for (var fixture : fixtures) {
			require(!reading || fixture.finished, "Automatic restart finished before every case settled");
			shutdown.put(fixture.core.network().networkId(), fixture.data.checkpoint());
			var row = new JsonObject(); row.addProperty("kind", fixture.kind.name()); row.addProperty("mode", fixture.mode.name());
			row.addProperty("completedBees", fixture.completedBees); row.addProperty("completedJobs", fixture.completedJobs);
			row.addProperty("workFeAfterRestart", fixture.workFe); row.addProperty("maintenanceTicksAfterRestart", fixture.maintenanceTicks); rows.add(row);
		}
		require(shutdown.size() == 9, "Automatic fixtures reused a network identity");
		var services = new JsonArray();
		for (var service : NetworkTickService.Service.values()) {
			var metric = new JsonObject(); metric.addProperty("service", service.name());
			metric.addProperty("checks", checks[service.ordinal()]); metric.addProperty("longestStepNanos", longestStepNanos[service.ordinal()]); services.add(metric);
		}
		report.add("sharedBudgetServices", services); report.addProperty("maxChecksPerTick", maxChecks);
		report.addProperty("observedTicks", observedTicks); report.addProperty("maintenanceFe", MAINTENANCE);
		report.addProperty("oracle", "single-input-independent");
		report.addProperty("noItemEntities", true);
		report.add("automaticRestartCases", rows); report.addProperty("automaticRestartIsNewJvm", reading);
		report.addProperty("producerPid", reading ? producerPid : ProcessHandle.current().pid()); report.addProperty("currentPid", ProcessHandle.current().pid());
		report.addProperty("ae2Loaded", net.neoforged.fml.ModList.get().isLoaded("ae2")); report.addProperty("passed", true); return true;
	}
	public static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		require(shutdown.size() == 9, "Automatic shutdown matrix incomplete");
		for (var entry : shutdown.entrySet()) {
			var path = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + entry.getKey() + ".dat");
			var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			require(NetworkCheckpointCodec.encode(entry.getValue()).equals(tag.getCompound("data")), "Automatic shutdown checkpoint differs from observed authority");
		}
		report.addProperty("normalShutdownCheckpointSaved", true);
	}
	private static Path manifest(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("automatic-probe.dat"); }
	private AutomaticRestartProbe() { }
}
