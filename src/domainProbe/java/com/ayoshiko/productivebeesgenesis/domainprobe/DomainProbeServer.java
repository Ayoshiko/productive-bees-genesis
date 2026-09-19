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
	@SubscribeEvent
	public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
		if (!Boolean.getBoolean("pbg.domain.enabled")) return;
		CheckpointReadProbe.close();
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
		if (pendingReport != null) {
			try {
				if (!NetworkPersistenceProbe.advance(event.getServer(), pendingReport)) return;
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
			NetworkPersistenceProbe.verify(event.getServer(), report);
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
