package com.ayoshiko.productivebeesgenesis.domainprobe;

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
	@SubscribeEvent
	public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
		if (!Boolean.getBoolean("pbg.domain.enabled")) return;
		if (System.getProperty("pbg.ownership.mode") != null) return;
		CheckpointReadProbe.close();
		TopologyProbe.close();
		Path file = Path.of("results/domain.json");
		try {
			var report = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			try { NetworkPersistenceProbe.verifyShutdown(event.getServer(), report); }
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
				boolean ownershipComplete = OwnershipProbe.advance(event.getServer(), pendingReport);
				boolean coreOwnershipComplete = CoreOwnershipProbe.advance(event.getServer(), pendingReport);
				boolean faultComplete = OwnershipFaultProbe.advance(event.getServer(), pendingReport);
				boolean beesComplete = BeeNetworkProbe.advance(event.getServer(), pendingReport);
				if (!persistenceComplete || !topologyComplete || !ownershipComplete || !coreOwnershipComplete || !faultComplete || !beesComplete) return;
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
			OwnershipProbe.start(event.getServer());
			CoreOwnershipProbe.start(event.getServer());
			OwnershipFaultProbe.start(event.getServer());
			BeeNetworkProbe.start(event.getServer());
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
	private static void finish(ServerTickEvent.Post event, JsonObject report) {
		try {
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
