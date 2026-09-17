package com.ayoshiko.productivebeesgenesis.storageprototype.compat;

import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.core.definitions.AEBlocks;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 无生产机器的隔离专服 API 实验，必须显式 Gradle 属性启用。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class D02Server {
	private static D02Server active;
	private static final BlockPos POWER = new BlockPos(0, 80, 0);
	private final MinecraftServer server;
	private final JsonObject report = new JsonObject();
	private final JsonArray saves = new JsonArray();
	private final PersistenceProbe persistence;
	private int ticks;

	private D02Server(MinecraftServer server) throws Exception {
		this.server = server;
		persistence = new PersistenceProbe(server.registryAccess(), Path.of("prototype-data"));
		server.overworld().setChunkForced(0, 0, true);
		server.overworld().setBlockAndUpdate(POWER, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
		report.addProperty("schema", 1);
		report.addProperty("java", System.getProperty("java.version"));
		report.addProperty("heapMaxBytes", Runtime.getRuntime().maxMemory());
		JsonObject mods = new JsonObject();
		ModList.get().getMods().forEach(mod -> mods.addProperty(mod.getModId(), mod.getVersion().toString()));
		report.add("mods", mods);
		report.add("savedData", saves);
	}

	@SubscribeEvent
	public static void started(ServerStartedEvent event) throws Exception {
		if (Boolean.getBoolean("pbg.d02.enabled")) active = new D02Server(event.getServer());
	}

	@SubscribeEvent
	public static void stopped(ServerStoppedEvent event) { active = null; }

	@SubscribeEvent
	public static void tick(ServerTickEvent.Post event) {
		if (active == null) return;
		D02Server run = active;
		try {
			run.step();
		} catch (Exception failure) {
			LogUtils.getLogger().error("D02_FAILED", failure);
			run.report.addProperty("passed", false);
			run.report.addProperty("failure", failure.toString());
			run.finish();
		}
	}

	private void step() throws Exception {
		if (++ticks < 40) return;
		if (ticks == 40) {
			var cell = (CreativeEnergyCellBlockEntity) server.overworld().getBlockEntity(POWER);
			PersistenceProbe.require(cell != null && cell.getMainNode().getNode() != null, "AE node not ready");
			report.add("network", NetworkProbe.run(cell.getMainNode().getNode().getGrid()));
			LogUtils.getLogger().info("D02_NETWORK_OK");
		} else if (ticks == 41) {
			report.add("faults", persistence.faults());
			LogUtils.getLogger().info("D02_FAULTS_OK (expected failed-file diagnostics above)");
		} else if (ticks <= 45) {
			int count = new int[]{1_000, 10_000, 100_000, 1_000_000}[ticks - 42];
			LogUtils.getLogger().info("D02_SAVE_BEGIN keys={}", count);
			saves.add(persistence.benchmark(count));
			LogUtils.getLogger().info("D02_SAVE_OK keys={}", count);
		} else {
			report.addProperty("passed", true);
			LogUtils.getLogger().info("D02_COMPLETE");
			finish();
		}
	}

	private void finish() {
		try {
			Path output = Path.of("results", "d02-api.json");
			Files.createDirectories(output.getParent());
			Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception failure) {
			LogUtils.getLogger().error("Cannot persist D02 evidence", failure);
		} finally {
			active = null;
			server.halt(false);
		}
	}
}
