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
	@SubscribeEvent
	public static void tick(ServerTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.domain.enabled") || event.getServer().getTickCount() != 40) return;
		var report = new JsonObject();
		report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
		try {
			ProductKeyProbe.verify(event.getServer().registryAccess());
			report.addProperty("productKeyRoundTrip", true);
			ProductPolicyProbe.verify(event.getServer().overworld(), report);
			report.addProperty("passed", true);
			LogUtils.getLogger().info("NETWORK_DOMAIN_COMPLETE");
		} catch (Exception failure) {
			report.addProperty("passed", false);
			report.addProperty("failure", failure.toString());
			LogUtils.getLogger().error("NETWORK_DOMAIN_FAILED", failure);
		}
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
