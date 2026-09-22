package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticRestartProbe;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 只在显式启用的独立测试源集运行，完成即正常停服。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class DomainProbeServer {
	private static JsonObject pendingReport;
	private static boolean persistenceComplete;
	private static int ownershipPhase;
	private static boolean energyStarted;
	private static boolean runtimeStarted;
	private static boolean automaticCentrifugeStarted;
	private static boolean playerFeedingStarted;
	@SubscribeEvent
	public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
		if (!Boolean.getBoolean("pbg.domain.enabled")) return;
		if (Boolean.getBoolean("pbg.cold.enabled")) return;
		if (System.getProperty("pbg.ownership.mode") != null) return;
		CheckpointReadProbe.close();
		TopologyProbe.close();
		Path file = Path.of("results/domain.json");
		try {
			var report = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			try {
				if (System.getProperty("pbg.automatic.mode") != null) AutomaticRestartProbe.verifyShutdown(event.getServer(), report);
				else if (System.getProperty("pbg.centrifuge.mode") != null) com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeRestartProbe.verifyShutdown(event.getServer(), report);
				else { NetworkPersistenceProbe.verifyShutdown(event.getServer(), report); PlayerFeedingProbe.verifyShutdown(event.getServer(), report); }
			}
			catch (Exception failure) {
				report.addProperty("passed", false); report.addProperty("shutdownFailure", failure.toString());
				LogUtils.getLogger().error("NETWORK_SHUTDOWN_FAILED", failure);
			}
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception failure) { LogUtils.getLogger().error("Cannot finish network shutdown report", failure); }
	}
	@SubscribeEvent
	public static void tick(ServerTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.domain.enabled")) return;
		if (System.getProperty("pbg.automatic.mode") != null) {
			try {
				if (event.getServer().getTickCount() == 40) { pendingReport = new JsonObject(); AutomaticRestartProbe.start(event.getServer()); }
				if (pendingReport != null && AutomaticRestartProbe.advance(event.getServer(), pendingReport)) { finish(event, pendingReport); pendingReport = null; }
			} catch (Exception error) {
				if (pendingReport == null) pendingReport = new JsonObject(); failed(pendingReport, error); finish(event, pendingReport); pendingReport = null;
			}
			return;
		}
		if (Boolean.getBoolean("pbg.cold.enabled")) {
			if (event.getServer().getTickCount() == 40) {
				var report = new JsonObject(); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
				try { ColdInitializationProbe.verify(event.getServer().overworld(), report); report.addProperty("passed", true); }
				catch (Exception failure) { failed(report, failure); }
				finish(event, report);
			}
			return;
		}
		if (System.getProperty("pbg.centrifuge.mode") != null) {
			try {
				if (event.getServer().getTickCount() == 40) { pendingReport = new JsonObject(); com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeRestartProbe.start(event.getServer()); }
				if (pendingReport != null && com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeRestartProbe.advance(event.getServer(), pendingReport)) { finish(event, pendingReport); pendingReport = null; }
			} catch (Exception error) {
				com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeRestartProbe.restoreRecipes(event.getServer());
				if (pendingReport == null) pendingReport = new JsonObject(); failed(pendingReport, error); finish(event, pendingReport); pendingReport = null;
			}
			return;
		}
		if (System.getProperty("pbg.ownership.mode") != null) {
			try {
				if (event.getServer().getTickCount() == 40) { pendingReport = new JsonObject(); OwnershipRestartProbe.start(event.getServer()); }
				if (pendingReport != null && OwnershipRestartProbe.advance(event.getServer(), pendingReport)) { finish(event, pendingReport); pendingReport = null; }
			} catch (Exception error) { if (pendingReport == null) pendingReport = new JsonObject(); failed(pendingReport, error); finish(event, pendingReport); pendingReport = null; }
			return;
		}
		if (pendingReport != null) {
			try {
				if (!persistenceComplete) persistenceComplete = NetworkPersistenceProbe.advance(event.getServer(), pendingReport);
				boolean topologyComplete = TopologyProbe.advance(event.getServer(), pendingReport);
				// 拓扑夹具会破坏同区块结构并修改全服预算，完成后再开始资产接管。
				if (!topologyComplete) return;
				if (!advanceOwnership(event.getServer(), pendingReport) || !persistenceComplete) return;
				// 拓扑夹具会恢复全局开关；供能夹具在它完成后独立验证开关行为。
				if (!energyStarted) { com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkEnergyProbe.start(event.getServer()); energyStarted = true; return; }
				if (!com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkEnergyProbe.advance(event.getServer(), pendingReport)) return;
				if (!runtimeStarted) { RuntimeBeeProbe.start(event.getServer()); runtimeStarted = true; return; }
				if (!RuntimeBeeProbe.advance(event.getServer(), pendingReport)) return;
				if (!automaticCentrifugeStarted) { com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticCentrifugeProbe.start(event.getServer()); automaticCentrifugeStarted = true; return; }
				if (!com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticCentrifugeProbe.advance(event.getServer(), pendingReport)) return;
				if (!playerFeedingStarted) { PlayerFeedingProbe.start(event.getServer()); playerFeedingStarted = true; return; }
				if (!PlayerFeedingProbe.advance(event.getServer(), pendingReport)) return;
				if (System.getProperty("pbg.restore.mode") != null) CheckpointRestoreBenchmark.run(event.getServer());
				pendingReport.addProperty("passed", true); LogUtils.getLogger().info("NETWORK_DOMAIN_COMPLETE");
			} catch (Exception failure) { failed(pendingReport, failure); }
			finish(event, pendingReport); pendingReport = null; return;
		}
		if (event.getServer().getTickCount() != 40) return;
		var report = new JsonObject();
		report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
		try {
			ProductKeyProbe.verify(event.getServer().registryAccess());
			report.addProperty("productKeyRoundTrip", true);
			var policy = ProductPolicyProbe.verify(event.getServer().overworld(), report);
			P1FlowProbe.verify(event.getServer().overworld(), policy, report);
			com.ayoshiko.productivebeesgenesis.apiary.BeeKernelProbe.verify(event.getServer().overworld(), report);
			com.ayoshiko.productivebeesgenesis.mek.CentrifugeKernelProbe.verify(event.getServer().overworld(), report);
			com.ayoshiko.productivebeesgenesis.mek.CentrifugeAdapterProbe.verify(event.getServer().overworld(), report);
			if (System.getProperty("pbg.bee.restartSource") != null) com.ayoshiko.productivebeesgenesis.apiculture.persistence.BeeRestartProbe.read(
					event.getServer().overworld(), Path.of(System.getProperty("pbg.bee.restartSource")), report);
			MemberIsolationProbe.verify(event.getServer().overworld(), report);
			NetworkPersistenceProbe.verify(event.getServer(), report);
			TopologyProbe.start(event.getServer());
			pendingReport = report; return;
		} catch (Exception failure) {
			failed(report, failure);
		}
		finish(event, report);
	}
	private static void failed(JsonObject report, Exception failure) {
		CheckpointReadProbe.close();
		report.addProperty("passed", false); report.addProperty("failure", failure.toString());
		LogUtils.getLogger().error("NETWORK_DOMAIN_FAILED", failure);
	}
	private static boolean advanceOwnership(net.minecraft.server.MinecraftServer server, JsonObject report) {
		// 这些夹具复用区块并故意破坏成员，不能依赖彼此完成的偶然先后顺序。
		switch (ownershipPhase) {
			case 0 -> OwnershipProbe.start(server);
			case 1 -> { if (!OwnershipProbe.advance(server, report)) return false; CoreOwnershipProbe.start(server); }
			case 2 -> { if (!CoreOwnershipProbe.advance(server, report)) return false; OwnershipFaultProbe.start(server); }
			case 3 -> { if (!OwnershipFaultProbe.advance(server, report)) return false; BeeNetworkProbe.start(server); }
			case 4 -> { if (!BeeNetworkProbe.advance(server, report)) return false; com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeNetworkProbe.start(server); }
			case 5 -> { if (!com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeNetworkProbe.advance(server, report)) return false; }
			default -> { return true; }
		}
		ownershipPhase++; return false;
	}
	private static void finish(ServerTickEvent.Post event, JsonObject report) {
		try {
			report.addProperty("javaVersion", System.getProperty("java.version"));
			report.addProperty("os", System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
			var mods = new JsonObject();
			ModList.get().getMods().forEach(mod -> mods.addProperty(mod.getModId(), mod.getVersion().toString()));
			report.add("loadedMods", mods);
			Files.createDirectories(Path.of("results"));
			Files.writeString(Path.of("results/domain.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception failure) {
			LogUtils.getLogger().error("Cannot persist network domain report", failure);
		} finally {
			event.getServer().halt(false);
		}
	}
	static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
